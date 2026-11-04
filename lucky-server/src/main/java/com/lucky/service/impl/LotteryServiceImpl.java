package com.lucky.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.lucky.dto.JoinResultVO;
import com.lucky.dto.LotteryDTO;
import com.lucky.dto.WinnerVO;
import com.lucky.entity.LotteryRound;
import com.lucky.entity.Participant;
import com.lucky.entity.Prize;
import com.lucky.entity.Winner;
import com.lucky.exception.BusinessException;
import com.lucky.mapper.LotteryRoundMapper;
import com.lucky.mapper.ParticipantMapper;
import com.lucky.mapper.PrizeMapper;
import com.lucky.mapper.WinnerMapper;
import com.lucky.service.LotteryService;
import com.lucky.service.SeckillService;
import com.lucky.websocket.LuckyWebSocketHandler;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 抽奖轮次管理
 *
 * 职责划分：
 *   LotteryService  —— 轮次的创建与状态流转（管理端视角）
 *   SeckillService  —— 用户抢奖的并发核心（用户端视角）
 *
 * 原来的「管理员一键随机抽人」逻辑已下线：业务改成现场限时抢奖，
 * 抽奖不再是管理员点一下，而是 2000 人同时点「立即抢奖」，先到先得。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LotteryServiceImpl extends ServiceImpl<LotteryRoundMapper, LotteryRound>
        implements LotteryService {

    private final SeckillService seckillService;
    private final PrizeMapper prizeMapper;
    private final WinnerMapper winnerMapper;
    private final ParticipantMapper participantMapper;
    private final LuckyWebSocketHandler webSocketHandler;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public LotteryRound createRound(LotteryDTO dto) {
        // 用 FOR UPDATE 锁住奖品行，把「统计已分配名额 → 校验 → 落库」串行化。
        // 原实现是先普通查询再 save：两个管理员同时建同一奖品的两个轮次时，
        // 双方都读到相同的已分配名额，都通过校验，最后加起来超过库存。
        Prize prize = prizeMapper.selectOne(new LambdaQueryWrapper<Prize>()
                .eq(Prize::getId, dto.getPrizeId())
                .last("FOR UPDATE"));
        if (prize == null || !dto.getActivityId().equals(prize.getActivityId())) {
            throw new BusinessException("奖品不存在或不属于当前活动");
        }

        // 名额不能超过奖品库存：同一奖品可能分多轮发放，所以要累加已分配名额
        int allocated = list(new LambdaQueryWrapper<LotteryRound>()
                .eq(LotteryRound::getActivityId, dto.getActivityId())
                .eq(LotteryRound::getPrizeId, dto.getPrizeId()))
                .stream()
                .mapToInt(r -> r.getWinnerCount() == null ? 0 : r.getWinnerCount())
                .sum();

        int stock = prize.getStock() == null ? 0 : prize.getStock();
        if (allocated + dto.getWinnerCount() > stock) {
            throw new BusinessException("名额超出奖品库存，剩余可分配 "
                    + Math.max(stock - allocated, 0) + " 个");
        }

        LotteryRound round = new LotteryRound();
        round.setActivityId(dto.getActivityId());
        round.setPrizeId(dto.getPrizeId());
        round.setRoundName(dto.getRoundName());
        round.setWinnerCount(dto.getWinnerCount());
        round.setStatus(0);
        save(round);

        log.info("轮次创建: roundId={}, activityId={}, prizeId={}, 名额={}",
                round.getId(), dto.getActivityId(), dto.getPrizeId(), dto.getWinnerCount());
        return round;
    }

    @Override
    public void startRound(Long roundId) {
        LotteryRound round = getById(roundId);
        if (round == null) {
            throw new BusinessException("轮次不存在");
        }
        if (round.getStatus() == null || round.getStatus() != 0) {
            throw new BusinessException("该轮次已开始或已结束");
        }

        // 先 CAS 改状态，只有抢到「待开始 → 抢奖中」那一次才继续。
        // 原实现是先 preheat 再 CAS：两个管理员同时点「开始」时，
        // 两个请求都会把 Redis 库存重置成满额；如果此时已经有用户抢走名额，
        // 后来的那次重置会把已消耗的库存冲回去，等于凭空多发奖品。
        boolean updated = update(new LambdaUpdateWrapper<LotteryRound>()
                .eq(LotteryRound::getId, roundId)
                .eq(LotteryRound::getStatus, 0)
                .set(LotteryRound::getStatus, 1)
                .set(LotteryRound::getStartTime, LocalDateTime.now()));
        if (!updated) {
            throw new BusinessException("操作冲突，请刷新后重试");
        }

        // 抢占成功后才预热库存；预热失败要把状态退回去，
        // 否则会留下一个「状态是抢奖中、但 Redis 里没有库存」的死轮次
        try {
            seckillService.preheat(roundId);
        } catch (Exception e) {
            update(new LambdaUpdateWrapper<LotteryRound>()
                    .eq(LotteryRound::getId, roundId)
                    .eq(LotteryRound::getStatus, 1)
                    .set(LotteryRound::getStatus, 0));
            log.error("轮次预热失败，已回退状态: roundId={}", roundId, e);
            throw e;
        }

        Prize prize = prizeMapper.selectById(round.getPrizeId());
        Map<String, Object> payload = new HashMap<>();
        payload.put("roundId", roundId);
        payload.put("roundName", round.getRoundName());
        payload.put("prizeName", prize != null ? prize.getName() : "");
        payload.put("prizeImage", prize != null ? prize.getImage() : "");
        payload.put("quota", seckillService.getRemaining(roundId));
        payload.put("startTime", System.currentTimeMillis());

        webSocketHandler.broadcastToActivity(round.getActivityId(), "lottery_start", payload);
        log.info("轮次开始: roundId={}, 名额={}", roundId, payload.get("quota"));
    }

    @Override
    public void endRound(Long roundId) {
        LotteryRound round = getById(roundId);
        if (round == null) {
            throw new BusinessException("轮次不存在");
        }

        boolean updated = update(new LambdaUpdateWrapper<LotteryRound>()
                .eq(LotteryRound::getId, roundId)
                .eq(LotteryRound::getStatus, 1)
                .set(LotteryRound::getStatus, 2)
                .set(LotteryRound::getEndTime, LocalDateTime.now()));
        if (!updated) {
            throw new BusinessException("只有进行中的轮次才能结束");
        }

        Map<String, Object> payload = new HashMap<>();
        payload.put("roundId", roundId);
        payload.put("reason", "管理员结束");
        webSocketHandler.broadcastToActivity(round.getActivityId(), "lottery_round_end", payload);

        log.info("轮次手动结束: roundId={}", roundId);
    }

    @Override
    public JoinResultVO join(Long roundId, Long participantId) {
        return seckillService.join(roundId, participantId);
    }

    @Override
    public Winner getResult(Long roundId, Long participantId) {
        return seckillService.getResult(roundId, participantId);
    }

    @Override
    public Winner claim(String claimCode) {
        if (claimCode == null || claimCode.isBlank()) {
            throw new BusinessException("领奖码不能为空");
        }
        String code = claimCode.trim().toUpperCase();

        Winner winner = winnerMapper.selectOne(new LambdaQueryWrapper<Winner>()
                .eq(Winner::getClaimCode, code)
                .last("LIMIT 1"));

        if (winner == null) {
            throw new BusinessException("领奖码无效");
        }

        LocalDateTime now = LocalDateTime.now();

        // 条件更新：只有仍处于「待领取(0)」才能核销。
        // 原实现是「先查状态 → 判断 → 再更新」，两个人同时扫同一个码时，
        // 双方都会读到 status=0 并都通过判断，结果两次都提示核销成功。
        int affected = winnerMapper.update(null, new LambdaUpdateWrapper<Winner>()
                .eq(Winner::getId, winner.getId())
                .eq(Winner::getStatus, 0)
                .set(Winner::getStatus, 1)
                .set(Winner::getClaimedAt, now));

        if (affected == 0) {
            // 更新没命中，说明状态已经变了，回查一次给出准确提示
            Winner latest = winnerMapper.selectById(winner.getId());
            Integer st = latest == null ? null : latest.getStatus();
            if (st != null && st == 1) {
                throw new BusinessException("该奖品已领取，请勿重复核销");
            }
            if (st != null && st == 2) {
                throw new BusinessException("该中奖记录已放弃");
            }
            throw new BusinessException("核销失败，请刷新后重试");
        }

        winner.setStatus(1);
        winner.setClaimedAt(now);

        log.info("领奖核销成功: winnerId={}, roundId={}, participantId={}, claimCode={}",
                winner.getId(), winner.getRoundId(), winner.getParticipantId(), code);
        return winner;
    }

    @Override
    public Integer getRemaining(Long roundId) {
        return seckillService.getRemaining(roundId);
    }

    @Override
    public List<LotteryRound> getRoundsByActivity(Long activityId) {
        List<LotteryRound> rounds = list(new LambdaQueryWrapper<LotteryRound>()
                .eq(LotteryRound::getActivityId, activityId)
                .orderByAsc(LotteryRound::getCreatedAt));

        if (rounds.isEmpty()) {
            return rounds;
        }

        // 一次性把奖品名带出来，避免前端为每个轮次再查一次奖品
        Set<Long> prizeIds = rounds.stream()
                .map(LotteryRound::getPrizeId)
                .filter(java.util.Objects::nonNull)
                .collect(Collectors.toSet());
        if (!prizeIds.isEmpty()) {
            Map<Long, Prize> prizeMap = prizeMapper.selectBatchIds(prizeIds).stream()
                    .collect(Collectors.toMap(Prize::getId, Function.identity()));
            rounds.forEach(r -> {
                Prize p = prizeMap.get(r.getPrizeId());
                r.setPrizeName(p != null ? p.getName() : "");
            });
        }
        return rounds;
    }

    @Override
    public List<WinnerVO> getWinnersByRound(Long roundId) {
        List<Winner> winners = winnerMapper.selectList(new LambdaQueryWrapper<Winner>()
                .eq(Winner::getRoundId, roundId)
                .orderByAsc(Winner::getId));
        if (winners.isEmpty()) {
            return Collections.emptyList();
        }

        Set<Long> participantIds = winners.stream()
                .map(Winner::getParticipantId)
                .collect(Collectors.toSet());
        Map<Long, Participant> participantMap = participantMapper.selectBatchIds(participantIds)
                .stream()
                .collect(Collectors.toMap(Participant::getId, Function.identity()));

        LotteryRound round = getById(roundId);
        Prize prize = round != null ? prizeMapper.selectById(round.getPrizeId()) : null;

        return winners.stream().map(w -> {
            WinnerVO vo = new WinnerVO();
            vo.setId(w.getId());
            vo.setRoundId(w.getRoundId());
            vo.setParticipantId(w.getParticipantId());
            Participant p = participantMap.get(w.getParticipantId());
            vo.setParticipantName(p != null ? p.getName() : "未知");
            vo.setStudentId(p != null ? p.getStudentId() : "");
            vo.setPrizeId(w.getPrizeId());
            vo.setPrizeName(prize != null ? prize.getName() : "");
            vo.setClaimCode(w.getClaimCode());
            vo.setStatus(w.getStatus());
            vo.setCreatedAt(w.getCreatedAt());
            return vo;
        }).collect(Collectors.toList());
    }
}

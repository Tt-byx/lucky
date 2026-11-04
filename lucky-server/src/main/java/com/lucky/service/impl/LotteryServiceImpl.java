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
    public LotteryRound createRound(LotteryDTO dto) {
        Prize prize = prizeMapper.selectById(dto.getPrizeId());
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

        // 先预热库存，预热失败就不允许开轮（宁可开不了，也不能开一个没库存的轮次）
        seckillService.preheat(roundId);

        // CAS 改状态：只有仍处于「待开始」才能改成「抢奖中」，天然防重复点击
        boolean updated = update(new LambdaUpdateWrapper<LotteryRound>()
                .eq(LotteryRound::getId, roundId)
                .eq(LotteryRound::getStatus, 0)
                .set(LotteryRound::getStatus, 1)
                .set(LotteryRound::getStartTime, LocalDateTime.now()));
        if (!updated) {
            throw new BusinessException("操作冲突，请刷新后重试");
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

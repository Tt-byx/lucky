package com.lucky.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.lucky.entity.LotteryRound;
import com.lucky.entity.Participant;
import com.lucky.entity.Winner;
import com.lucky.exception.BusinessException;
import com.lucky.mapper.LotteryRoundMapper;
import com.lucky.mapper.ParticipantMapper;
import com.lucky.mapper.WinnerMapper;
import com.lucky.mq.MessageProducer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 抽奖执行服务（独立类，解决 @Transactional 自调用失效问题）
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LotteryDrawService {

    private final ParticipantMapper participantMapper;
    private final WinnerMapper winnerMapper;
    private final LotteryRoundMapper lotteryRoundMapper;
    private final MessageProducer messageProducer;

    /**
     * 执行抽奖逻辑（在事务内执行）
     */
    @Transactional(rollbackFor = Exception.class)
    public List<Participant> doDraw(Long roundId) {
        LotteryRound round = lotteryRoundMapper.selectById(roundId);
        if (round == null) {
            throw new BusinessException("轮次不存在");
        }

        // 检查轮次状态
        if (round.getStatus() == 1) {
            throw new BusinessException("该轮次已完成抽奖");
        }

        // 获取该活动下所有未中奖的参与者
        List<Participant> candidates = participantMapper.selectList(
                new LambdaQueryWrapper<Participant>()
                        .eq(Participant::getActivityId, round.getActivityId())
                        .eq(Participant::getStatus, 1)
        );

        if (candidates.isEmpty()) {
            throw new BusinessException("没有可抽奖的参与者");
        }

        // 随机打乱
        Collections.shuffle(candidates);

        // 取出中奖者（不超过候选人数）
        int count = Math.min(round.getWinnerCount(), candidates.size());
        List<Participant> winners = new ArrayList<>(candidates.subList(0, count));

        // 批量保存中奖记录
        for (Participant winner : winners) {
            Winner record = new Winner();
            record.setRoundId(roundId);
            record.setParticipantId(winner.getId());
            winnerMapper.insert(record);

            winner.setStatus(0); // 标记已中奖
            participantMapper.updateById(winner);
        }

        // 更新轮次状态
        round.setStatus(1);
        lotteryRoundMapper.updateById(round);

        log.info("抽奖完成: roundId={}, 中奖人数={}", roundId, winners.size());

        return winners;
    }
}

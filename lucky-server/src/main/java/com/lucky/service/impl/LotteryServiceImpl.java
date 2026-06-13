package com.lucky.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.lucky.dto.LotteryDTO;
import com.lucky.entity.LotteryRound;
import com.lucky.entity.Participant;
import com.lucky.entity.Winner;
import com.lucky.mapper.LotteryRoundMapper;
import com.lucky.mapper.WinnerMapper;
import com.lucky.mq.MessageProducer;
import com.lucky.service.LotteryDrawService;
import com.lucky.service.LotteryService;
import com.lucky.exception.BusinessException;
import com.lucky.websocket.LuckyWebSocketHandler;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.concurrent.TimeUnit;

@Slf4j
@Service
@RequiredArgsConstructor
public class LotteryServiceImpl extends ServiceImpl<LotteryRoundMapper, LotteryRound>
        implements LotteryService {

    private final LotteryDrawService lotteryDrawService;
    private final WinnerMapper winnerMapper;
    private final LuckyWebSocketHandler webSocketHandler;
    private final MessageProducer messageProducer;
    private final RedissonClient redissonClient;

    /**
     * 分布式锁前缀
     */
    private static final String LOTTERY_LOCK_PREFIX = "lucky:lottery:lock:";

    @Override
    public LotteryRound createRound(LotteryDTO dto) {
        if (dto.getActivityId() == null) {
            throw new BusinessException("活动不存在，请先创建活动");
        }
        LotteryRound round = new LotteryRound();
        round.setActivityId(dto.getActivityId());
        round.setRoundName(dto.getRoundName());
        round.setWinnerCount(dto.getWinnerCount());
        round.setStatus(0);
        save(round);
        return round;
    }

    @Override
    public List<Participant> draw(Long roundId) {
        // 先通知大屏开始抽奖动画（在锁外执行，避免阻塞）
        webSocketHandler.broadcast("lottery_start", roundId);

        // 获取分布式锁，防止并发抽奖
        String lockKey = LOTTERY_LOCK_PREFIX + roundId;
        RLock lock = redissonClient.getLock(lockKey);

        try {
            // 尝试获取锁，等待3秒，锁持有时间30秒
            if (!lock.tryLock(3, 30, TimeUnit.SECONDS)) {
                throw new BusinessException("抽奖正在进行中，请稍后重试");
            }

            log.info("获取分布式锁成功，开始抽奖: roundId={}", roundId);

            // 获取锁成功，调用独立的事务服务执行抽奖逻辑
            List<Participant> winners = lotteryDrawService.doDraw(roundId);

            // 通过消息队列异步广播抽奖结果（在锁外执行）
            messageProducer.sendLotteryResult(winners);

            return winners;

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new BusinessException("抽奖被中断，请重试");
        } finally {
            // 释放锁（只释放自己持有的锁）
            if (lock.isHeldByCurrentThread()) {
                lock.unlock();
                log.info("释放分布式锁: roundId={}", roundId);
            }
        }
    }

    @Override
    public List<LotteryRound> getRoundsByActivity(Long activityId) {
        return list(new LambdaQueryWrapper<LotteryRound>()
                .eq(LotteryRound::getActivityId, activityId)
                .orderByAsc(LotteryRound::getCreatedAt));
    }

    @Override
    public List<Winner> getWinnersByRound(Long roundId) {
        return winnerMapper.selectList(
                new LambdaQueryWrapper<Winner>()
                        .eq(Winner::getRoundId, roundId)
        );
    }
}

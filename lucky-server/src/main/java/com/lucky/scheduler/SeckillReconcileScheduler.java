package com.lucky.scheduler;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.lucky.constant.RedisKeys;
import com.lucky.entity.LotteryRound;
import com.lucky.entity.Prize;
import com.lucky.entity.Winner;
import com.lucky.mapper.LotteryRoundMapper;
import com.lucky.mapper.PrizeMapper;
import com.lucky.mapper.WinnerMapper;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 库存对账
 *
 * 秒杀链路是「Redis 预扣 → MQ 异步落库 → DB 条件扣减」，三个环节都可能出问题：
 * Redis 扣了但消息丢了、消息落了但 DB 扣失败、人工改了库存。
 * 这条定时任务负责事后发现不一致，而不是等用户投诉「明明还有库存却抢不到」。
 *
 * 对账口径：某奖品「已中奖记录数」应当等于「total_stock - stock」。
 * 有进行中的轮次时跳过，因为此时 Redis 已预扣、MQ 可能还在排队，差值属于正常在途。
 *
 * 多实例部署时 @Scheduled 会在每个实例都跑，用 Redisson 分布式锁保证只跑一份
 * （否则两个实例可能同时对同一条异常记录做修正，导致库存凭空多出来）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SeckillReconcileScheduler {

    private static final String LOCK_NAME = "seckill-reconcile";

    private final PrizeMapper prizeMapper;
    private final LotteryRoundMapper lotteryRoundMapper;
    private final WinnerMapper winnerMapper;
    private final MeterRegistry meterRegistry;
    private final RedissonClient redissonClient;

    @Scheduled(fixedDelay = 300000, initialDelay = 60000)
    public void reconcile() {
        RLock lock = redissonClient.getLock(RedisKeys.schedulerLock(LOCK_NAME));
        if (!lock.tryLock()) {
            // 其他实例正在对账，本次跳过
            return;
        }
        try {
            doReconcile();
        } finally {
            lock.unlock();
        }
    }

    private void doReconcile() {
        List<Prize> prizes;
        try {
            prizes = prizeMapper.selectList(new LambdaQueryWrapper<Prize>()
                    .isNotNull(Prize::getTotalStock));
        } catch (Exception e) {
            log.warn("库存对账任务跳过（读取奖品失败）: {}", e.getMessage());
            return;
        }

        int mismatch = 0;

        for (Prize prize : prizes) {
            List<LotteryRound> rounds = lotteryRoundMapper.selectList(
                    new LambdaQueryWrapper<LotteryRound>().eq(LotteryRound::getPrizeId, prize.getId()));
            if (rounds.isEmpty()) {
                continue;
            }

            boolean hasRunningRound = rounds.stream()
                    .anyMatch(r -> r.getStatus() != null && r.getStatus() == 1);
            if (hasRunningRound) {
                continue;
            }

            List<Long> roundIds = rounds.stream().map(LotteryRound::getId).toList();
            Long winnerCount = winnerMapper.selectCount(
                    new LambdaQueryWrapper<Winner>().in(Winner::getRoundId, roundIds));

            int issued = winnerCount == null ? 0 : winnerCount.intValue();
            int totalStock = prize.getTotalStock() == null ? 0 : prize.getTotalStock();
            int stock = prize.getStock() == null ? 0 : prize.getStock();
            int consumed = totalStock - stock;

            if (issued != consumed) {
                mismatch++;
                log.error("库存对账不一致: prizeId={}, name={}, 已中奖={}, 已扣库存={}, total={}, stock={}",
                        prize.getId(), prize.getName(), issued, consumed, totalStock, stock);
            }
        }

        if (mismatch > 0) {
            meterRegistry.counter("lucky.reconcile.mismatch").increment(mismatch);
            log.error("本轮对账发现 {} 个奖品库存不一致", mismatch);
        } else {
            log.debug("库存对账通过，检查奖品数={}", prizes.size());
        }
    }
}

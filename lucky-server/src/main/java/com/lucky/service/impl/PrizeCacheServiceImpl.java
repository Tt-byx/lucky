package com.lucky.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.lucky.entity.Prize;
import com.lucky.mapper.PrizeMapper;
import com.lucky.service.PrizeCacheService;
import com.lucky.util.RedisUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * 奖品缓存服务实现
 * 热点数据缓存预热：活动开启时将奖品信息预热至 Redis，抽奖优先操作缓存
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PrizeCacheServiceImpl implements PrizeCacheService {

    private final PrizeMapper prizeMapper;
    private final RedisUtil redisUtil;

    /** 奖品列表缓存前缀 */
    private static final String PRIZE_LIST_KEY = "lucky:prize:list:";

    /** 奖品库存缓存前缀 */
    private static final String PRIZE_STOCK_KEY = "lucky:prize:stock:";

    /** 缓存有效期（24小时） */
    private static final long CACHE_EXPIRE_HOURS = 24;

    @Override
    public void preheatPrizeCache(Long activityId) {
        List<Prize> prizes = prizeMapper.selectList(
                new LambdaQueryWrapper<Prize>()
                        .eq(Prize::getActivityId, activityId)
                        .orderByAsc(Prize::getId)
        );

        if (prizes.isEmpty()) {
            log.warn("活动 {} 无奖品配置，跳过缓存预热", activityId);
            return;
        }

        // 预热奖品列表
        String listKey = PRIZE_LIST_KEY + activityId;
        redisUtil.set(listKey, prizes, CACHE_EXPIRE_HOURS, TimeUnit.HOURS);

        // 预热每个奖品的库存
        for (Prize prize : prizes) {
            if (prize.getId() != null && prize.getStock() != null) {
                String stockKey = PRIZE_STOCK_KEY + prize.getId();
                redisUtil.set(stockKey, prize.getStock(), CACHE_EXPIRE_HOURS, TimeUnit.HOURS);
            }
        }

        log.info("奖品缓存预热完成: activityId={}, 奖品数量={}", activityId, prizes.size());
    }

    @Override
    @SuppressWarnings("unchecked")
    public List<Prize> getPrizesByActivity(Long activityId) {
        String listKey = PRIZE_LIST_KEY + activityId;
        Object cached = redisUtil.get(listKey);

        // 空列表也算命中：活动确实没配奖品时缓存一个空集合，避免每次都打到 DB（缓存穿透）
        if (cached instanceof List) {
            return (List<Prize>) cached;
        }

        return loadWithMutex(activityId, listKey);
    }

    /**
     * 互斥重建：只有一个线程去查库，其余线程短暂自旋等待
     * 热点活动开场瞬间如果缓存刚好失效，几千个请求同时查库会把 MySQL 打穿（缓存击穿）
     */
    @SuppressWarnings("unchecked")
    private List<Prize> loadWithMutex(Long activityId, String listKey) {
        String lockKey = "lucky:lock:prize:list:" + activityId;
        boolean locked = false;

        try {
            for (int i = 0; i < 5; i++) {
                Boolean ok = redisUtil.setIfAbsent(lockKey, "1", 5, TimeUnit.SECONDS);
                if (Boolean.TRUE.equals(ok)) {
                    locked = true;
                    break;
                }
                try {
                    Thread.sleep(30);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
                Object cached = redisUtil.get(listKey);
                if (cached instanceof List) {
                    return (List<Prize>) cached;
                }
            }

            List<Prize> prizes = prizeMapper.selectList(
                    new LambdaQueryWrapper<Prize>()
                            .eq(Prize::getActivityId, activityId)
                            .orderByAsc(Prize::getId)
            );

            if (prizes.isEmpty()) {
                // 空结果缓存 1 分钟，足够挡住缓存穿透，又不至于让新配置的奖品迟迟不出现
                redisUtil.set(listKey, prizes, 1, TimeUnit.MINUTES);
            } else {
                redisUtil.set(listKey, prizes, CACHE_EXPIRE_HOURS, TimeUnit.HOURS);
            }
            return prizes;
        } finally {
            if (locked) {
                redisUtil.delete(lockKey);
            }
        }
    }

    @Override
    public int decrementStock(Long prizeId, int count) {
        String stockKey = PRIZE_STOCK_KEY + prizeId;

        // 检查缓存是否存在
        Object cached = redisUtil.get(stockKey);
        if (cached == null) {
            // 缓存未命中，从 DB 加载
            Prize prize = prizeMapper.selectById(prizeId);
            if (prize == null || prize.getStock() == null) {
                return -1;
            }
            redisUtil.set(stockKey, prize.getStock(), CACHE_EXPIRE_HOURS, TimeUnit.HOURS);
        }

        // 原子递减
        Long remaining = redisUtil.decrement(stockKey, count);
        if (remaining == null) return -1;

        if (remaining < 0) {
            // 库存不足，回滚
            redisUtil.increment(stockKey, count);
            log.warn("奖品库存不足: prizeId={}, 请求数量={}", prizeId, count);
            return -1;
        }

        log.debug("奖品库存扣减: prizeId={}, 扣减={}, 剩余={}", prizeId, count, remaining);
        return remaining.intValue();
    }

    @Override
    public int getStock(Long prizeId) {
        String stockKey = PRIZE_STOCK_KEY + prizeId;
        Object cached = redisUtil.get(stockKey);

        if (cached != null) {
            try {
                return Integer.parseInt(cached.toString());
            } catch (NumberFormatException e) {
                log.warn("库存缓存解析失败: prizeId={}", prizeId);
            }
        }

        // 缓存未命中，查询 DB
        Prize prize = prizeMapper.selectById(prizeId);
        if (prize != null && prize.getStock() != null) {
            redisUtil.set(stockKey, prize.getStock(), CACHE_EXPIRE_HOURS, TimeUnit.HOURS);
            return prize.getStock();
        }

        return 0;
    }

    @Override
    public void evictPrizeCache(Long activityId) {
        String listKey = PRIZE_LIST_KEY + activityId;
        redisUtil.delete(listKey);

        // 清除该活动所有奖品的库存缓存
        List<Prize> prizes = prizeMapper.selectList(
                new LambdaQueryWrapper<Prize>()
                        .eq(Prize::getActivityId, activityId)
        );
        for (Prize prize : prizes) {
            if (prize.getId() != null) {
                redisUtil.delete(PRIZE_STOCK_KEY + prize.getId());
            }
        }

        log.info("奖品缓存已清除: activityId={}", activityId);
    }
}

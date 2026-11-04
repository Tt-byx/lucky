package com.lucky.service;

import com.lucky.entity.Prize;

import java.util.List;

/**
 * 奖品缓存服务
 * 活动开启时将奖品数量、奖品等级、剩余库存预热至 Redis 缓存
 * 抽奖逻辑优先操作缓存，避免频繁查询 MySQL，提升抽奖响应速度
 */
public interface PrizeCacheService {

    /**
     * 预热活动奖品缓存至 Redis
     *
     * @param activityId 活动 ID
     */
    void preheatPrizeCache(Long activityId);

    /**
     * 获取活动奖品列表（优先缓存）
     *
     * @param activityId 活动 ID
     * @return 奖品列表
     */
    List<Prize> getPrizesByActivity(Long activityId);

    /**
     * 扣减奖品库存（Redis 原子操作）
     *
     * @param prizeId 奖品 ID
     * @param count   扣减数量
     * @return 扣减后剩余库存，库存不足返回 -1
     */
    int decrementStock(Long prizeId, int count);

    /**
     * 获取奖品剩余库存（优先缓存）
     *
     * @param prizeId 奖品 ID
     * @return 剩余库存
     */
    int getStock(Long prizeId);

    /**
     * 清除活动奖品缓存
     *
     * @param activityId 活动 ID
     */
    void evictPrizeCache(Long activityId);
}

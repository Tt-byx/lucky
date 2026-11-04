package com.lucky.service;

import com.lucky.dto.JoinResultVO;
import com.lucky.entity.Winner;

/**
 * 抢奖（秒杀）核心服务
 *
 * 职责边界：只负责「Redis 预扣 + 发消息」这一小段主链路，保证接口在毫秒级返回；
 * 落库、库存对账、广播全部交给 MQ 消费者异步完成。
 */
public interface SeckillService {

    /**
     * 开轮预热：把轮次名额写入 Redis，并缓存轮次元信息
     */
    void preheat(Long roundId);

    /**
     * 执行抢奖（主链路：校验 → Lua 原子扣减 → 发 MQ）
     */
    JoinResultVO join(Long roundId, Long participantId);

    /**
     * 回滚预扣（MQ 发送失败或消费失败时补偿）
     */
    void rollback(Long roundId, Long participantId, Long activityId);

    /**
     * 查询剩余名额
     */
    Integer getRemaining(Long roundId);

    /**
     * 查询某人在本轮的中奖结果
     */
    Winner getResult(Long roundId, Long participantId);

    /**
     * 清理轮次相关缓存
     */
    void clearRound(Long roundId, Long activityId);
}

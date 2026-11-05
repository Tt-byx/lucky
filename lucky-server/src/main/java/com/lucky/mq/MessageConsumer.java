package com.lucky.mq;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.lucky.config.RabbitConfig;
import org.springframework.transaction.support.TransactionTemplate;
import com.lucky.constant.RedisKeys;
import com.lucky.entity.Danmaku;
import com.lucky.entity.Participant;
import com.lucky.entity.Winner;
import com.lucky.mapper.ParticipantMapper;
import com.lucky.mapper.PrizeMapper;
import com.lucky.mapper.WinnerMapper;
import com.lucky.service.SeckillService;
import com.lucky.websocket.LuckyWebSocketHandler;
import com.rabbitmq.client.Channel;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.support.AmqpHeaders;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * 消息消费者（场次定向广播 + 幂等消费 + 有限重试 + 死信兜底）
 *
 * 相比初版修正了三处：
 * 1. 重试计数改用业务 messageId，不再用 deliveryTag —— deliveryTag 是 channel 级自增序号，
 *    会复用、重启归零，用它当 Redis key 会让重试计数互相污染。
 * 2. 消费端增加 messageId 幂等，同一条消息重复投递只处理一次。
 * 3. 抢奖落库失败时回滚 Redis 预扣并回补 DB 库存，保证 Redis / DB 两侧库存一致。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MessageConsumer {

    private final LuckyWebSocketHandler webSocketHandler;
    private final DanmakuBatchPersister danmakuBatchPersister;
    private final PrizeMapper prizeMapper;
    private final WinnerMapper winnerMapper;
    private final ParticipantMapper participantMapper;
    private final SeckillService seckillService;
    private final TransactionTemplate transactionTemplate;
    private final StringRedisTemplate stringRedisTemplate;

    /** MQ 消费重试计数 Redis key 前缀 */
    private static final String MQ_RETRY_KEY_PREFIX = "lucky:mq:retry:";

    /** 最大重试次数 */
    private static final int MAX_RETRY_COUNT = 3;

    private static final String FIELD_ACTIVITY_ID = "activityId";
    private static final String FIELD_PARTICIPANT_ID = "participantId";
    private static final String FIELD_CONTENT = "content";
    private static final String FIELD_STATUS = "status";

    // ==================== 抢奖消费（核心） ====================

    /**
     * 消费抢奖消息：异步落库 + 广播中奖结果
     *
     * 顺序是刻意设计的：
     *   先扣 DB 库存（条件更新，库存为 0 时不会扣成功）
     *   → 再插中奖记录（唯一索引兜底）
     *   → 最后广播
     * 任何一步失败都要把 Redis 预扣和 DB 库存回滚掉，让用户可以重新抢。
     */
    @RabbitListener(queues = RabbitConfig.LOTTERY_JOIN_QUEUE)
    public void handleJoin(Map<String, Object> data,
                           Channel channel,
                           @Header(AmqpHeaders.DELIVERY_TAG) long deliveryTag) throws IOException {
        String messageId = asString(data.get("messageId"));

        // 幂等：同一条消息重复投递只处理一次
        if (!markConsumed(messageId)) {
            channel.basicAck(deliveryTag, false);
            log.debug("抢奖消息重复投递，已跳过: messageId={}", messageId);
            return;
        }

        Long activityId = asLong(data.get(FIELD_ACTIVITY_ID));
        Long roundId = asLong(data.get("roundId"));
        Long participantId = asLong(data.get(FIELD_PARTICIPANT_ID));
        Long prizeId = asLong(data.get("prizeId"));

        boolean stockDeducted = false;
        try {
            // 1+2. 库存扣减 + 写中奖记录：必须在同一事务里
            // 原来是两步独立操作，进程在中间崩溃会导致「库存扣了但中奖记录没写」
            // 用 TransactionTemplate 包裹，保证原子性
            Winner winner = new Winner();
            winner.setActivityId(activityId);
            winner.setRoundId(roundId);
            winner.setParticipantId(participantId);
            winner.setPrizeId(prizeId);
            winner.setStatus(0);
            winner.setClaimCode(generateClaimCode());
            winner.setCreatedAt(LocalDateTime.now());

            Integer affected;
            try {
                affected = transactionTemplate.execute(status -> {
                    int a = prizeMapper.decreaseStock(prizeId);
                    if (a == 0) {
                        return 0;
                    }
                    winnerMapper.insert(winner);
                    return 1;
                });
            } catch (DuplicateKeyException e) {
                // 同一人重复中奖，把 Redis 名额还回去（库存由事务自动回滚）
                seckillService.rollback(roundId, participantId, activityId);
                channel.basicAck(deliveryTag, false);
                log.warn("重复中奖记录，已回滚: roundId={}, participantId={}", roundId, participantId);
                return;
            }

            if (affected == null || affected == 0) {
                // Redis 预扣成功但 DB 没库存，说明两侧已经不一致，必须回滚并告警
                seckillService.rollback(roundId, participantId, activityId);
                channel.basicAck(deliveryTag, false);
                log.error("DB 库存不足，Redis 预扣与 DB 不一致: roundId={}, prizeId={}, participantId={}",
                        roundId, prizeId, participantId);
                return;
            }
            stockDeducted = true;

            // 3. 广播中奖结果到场次
            // 这里刻意不再查 count(*)：大屏的「已抢到 N 人」用的是自己收到的 winnerList.length，
            // 前端根本不用这个字段。而每条中奖消息都做一次全表 count，1000 人抢中就是 1000 次。
            Map<String, Object> payload = new HashMap<>();
            payload.put("roundId", roundId);
            payload.put("participantId", participantId);

            // 大屏要直接显示「谁抢到了」，所以把姓名学号一起带出去
            Participant p = participantMapper.selectById(participantId);
            payload.put("participantName", p != null ? p.getName() : "幸运儿");
            payload.put("studentId", p != null ? p.getStudentId() : "");

            payload.put("prizeId", prizeId);
            payload.put("claimCode", winner.getClaimCode());
            payload.put("remaining", seckillService.getRemaining(roundId));

            webSocketHandler.broadcastToActivity(activityId, "lottery_winner", payload);

            channel.basicAck(deliveryTag, false);
            log.info("抢奖落库成功: roundId={}, participantId={}, 领奖码={}",
                    roundId, participantId, winner.getClaimCode());

        } catch (Exception e) {
            // 落库链路异常：回滚 DB 库存与 Redis 预扣，让用户重新抢
            if (stockDeducted) {
                prizeMapper.increaseStock(prizeId);
            }
            seckillService.rollback(roundId, participantId, activityId);

            // 已经做过补偿，直接丢弃消息，避免重试把库存重复扣回去
            channel.basicAck(deliveryTag, false);
            log.error("抢奖落库失败，已回滚并丢弃消息: roundId={}, participantId={}, 错误={}",
                    roundId, participantId, e.getMessage(), e);
        }
    }

    // ==================== 弹幕消费 ====================

    @RabbitListener(queues = RabbitConfig.DANMAKU_QUEUE)
    public void handleDanmaku(Map<String, Object> danmakuData,
                              Channel channel,
                              @Header(AmqpHeaders.DELIVERY_TAG) long deliveryTag) throws IOException {
        String messageId = asString(danmakuData.get("messageId"));

        if (!markConsumed(messageId)) {
            channel.basicAck(deliveryTag, false);
            return;
        }

        try {
            Long activityId = asLong(danmakuData.get(FIELD_ACTIVITY_ID));

            Object statusObj = danmakuData.get(FIELD_STATUS);
            int status = statusObj != null ? Integer.parseInt(statusObj.toString()) : 1;

            // 只有审核通过的弹幕才推送。
            // 被敏感词命中的消息也会进这个队列（status=2，用来留审计记录），
            // 原来这里无条件广播，等于「用户收到『已被拒绝』的提示，但违规内容照样打在 2000 人的大屏上」，
            // 敏感词过滤对整个现场是失效的。
            if (status == 1) {
                // 弹幕只推大屏（手机端不接收，理由见 batchBroadcastDanmaku 的注释）。
                // activityId 缺失也统一走这里，handler 内部会按「只推大屏」处理，
                // 不能用 broadcast（那会推给所有手机连接）。
                webSocketHandler.batchBroadcastDanmaku(activityId, "danmaku", danmakuData);
            } else {
                log.info("弹幕未通过审核，仅入库不广播: activityId={}, status={}, content={}",
                        activityId, status, danmakuData.get(FIELD_CONTENT));
            }

            Danmaku danmaku = new Danmaku();
            danmaku.setActivityId(activityId);
            Long pid = asLong(danmakuData.get(FIELD_PARTICIPANT_ID));
            if (pid != null) {
                danmaku.setParticipantId(pid);
            }
            danmaku.setContent((String) danmakuData.get(FIELD_CONTENT));
            danmaku.setStatus(status);

            danmakuBatchPersister.submit(danmaku);

            channel.basicAck(deliveryTag, false);
            removeDedup(messageId);

            log.debug("弹幕消息消费成功: activityId={}, content={}",
                    activityId, danmakuData.get(FIELD_CONTENT));

        } catch (Exception e) {
            removeDedup(messageId);
            handleConsumeFailure(channel, deliveryTag, messageId, "danmaku", e);
        }
    }

    // ==================== 抽奖结果广播 ====================

    @RabbitListener(queues = RabbitConfig.LOTTERY_RESULT_QUEUE)
    public void handleLotteryResult(Object winners,
                                    Channel channel,
                                    @Header(AmqpHeaders.DELIVERY_TAG) long deliveryTag) throws IOException {
        try {
            webSocketHandler.broadcast("lottery_result", winners);
            channel.basicAck(deliveryTag, false);
            log.debug("抽奖结果消息消费成功");
        } catch (Exception e) {
            handleConsumeFailure(channel, deliveryTag, "lottery-result", "lottery", e);
        }
    }

    // ==================== 在线人数 ====================

    @RabbitListener(queues = RabbitConfig.ONLINE_COUNT_QUEUE)
    public void handleOnlineCount(Integer count,
                                  Channel channel,
                                  @Header(AmqpHeaders.DELIVERY_TAG) long deliveryTag) throws IOException {
        try {
            webSocketHandler.broadcast("online_update", count);
            channel.basicAck(deliveryTag, false);
            log.debug("在线人数消息消费成功: count={}", count);
        } catch (Exception e) {
            handleConsumeFailure(channel, deliveryTag, "online", "online", e);
        }
    }

    // ==================== 幂等与重试 ====================

    /**
     * 标记消息已消费
     *
     * @return true 首次消费；false 重复投递
     */
    private boolean markConsumed(String messageId) {
        if (messageId == null || messageId.isEmpty()) {
            return true;
        }
        Boolean first = stringRedisTemplate.opsForValue()
                .setIfAbsent(RedisKeys.mqDedup(messageId), "1", 24, TimeUnit.HOURS);
        return Boolean.TRUE.equals(first);
    }

    /**
     * 移除幂等标记（消费失败时用，让消息重投后还能被处理）
     */
    private void removeDedup(String messageId) {
        if (messageId != null && !messageId.isEmpty()) {
            stringRedisTemplate.delete(RedisKeys.mqDedup(messageId));
        }
    }

    /**
     * 消费失败：有限重试 + 死信兜底
     */
    private void handleConsumeFailure(Channel channel, long deliveryTag, String messageId,
                                      String messageType, Exception e) throws IOException {
        String retryKey = MQ_RETRY_KEY_PREFIX + messageType + ":" + messageId;

        int retryCount = 0;
        String cached = stringRedisTemplate.opsForValue().get(retryKey);
        if (cached != null) {
            try {
                retryCount = Integer.parseInt(cached);
            } catch (NumberFormatException ignored) {
                retryCount = 0;
            }
        }
        retryCount++;
        stringRedisTemplate.opsForValue().set(retryKey, String.valueOf(retryCount), 10, TimeUnit.MINUTES);

        if (retryCount <= MAX_RETRY_COUNT) {
            log.warn("{}消息消费失败，第 {}/{} 次重试: {}", messageType, retryCount, MAX_RETRY_COUNT, e.getMessage());
            channel.basicNack(deliveryTag, false, true);
        } else {
            log.error("{}消息消费失败，重试 {} 次超限，进入死信队列: {}",
                    messageType, MAX_RETRY_COUNT, e.getMessage(), e);
            channel.basicNack(deliveryTag, false, false);
            stringRedisTemplate.delete(retryKey);
        }
    }

    // ==================== 工具方法 ====================

    private String asString(Object value) {
        return value == null ? null : value.toString();
    }

    private Long asLong(Object value) {
        if (value == null) {
            return null;
        }
        try {
            return Long.parseLong(value.toString());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private String generateClaimCode() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 8).toUpperCase();
    }
}

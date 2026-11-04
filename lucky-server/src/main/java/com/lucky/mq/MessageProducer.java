package com.lucky.mq;

import com.lucky.config.RabbitConfig;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * 消息生产者
 *
 * 两条发送策略是刻意区分开的：
 *   sendJoin    —— 失败必须抛出，由调用方回滚 Redis 预扣，绝不能静默吞掉
 *   sendDanmaku —— 失败只记日志，丢一条弹幕不影响主流程
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MessageProducer {

    private final RabbitTemplate rabbitTemplate;

    /**
     * 发送抢奖消息（必须确认到达 broker，失败抛出由调用方回滚预扣）
     *
     * 这里刻意不用裸的 convertAndSend：
     * 它只保证消息写进了 TCP 发送缓冲区，broker 有没有收到是另一回事。
     * 一旦消息在半路丢了（broker 重启、网络中断、队列满了被拒），
     * 调用方拿不到任何异常，Redis 里预扣的那个名额就永远不会还回来，
     * 现场表现就是「明明还有奖，但每个人都提示抢完了」。
     *
     * 所以这里同步等 publisher confirm：nack 或超时都抛异常，让上层回滚。
     */
    public void sendJoin(Map<String, Object> joinData) {
        String messageId = String.valueOf(joinData.get("messageId"));
        CorrelationData correlation = new CorrelationData(messageId);

        rabbitTemplate.convertAndSend(
                RabbitConfig.LOTTERY_JOIN_EXCHANGE,
                RabbitConfig.LOTTERY_JOIN_ROUTING_KEY,
                joinData,
                correlation
        );

        try {
            CorrelationData.Confirm confirm = correlation.getFuture().get(3, TimeUnit.SECONDS);
            if (confirm != null && !confirm.isAck()) {
                throw new AmqpException("抢奖消息被 broker 拒绝: " + confirm.getReason());
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AmqpException("等待抢奖消息确认被中断", e);
        } catch (TimeoutException e) {
            throw new AmqpException("等待抢奖消息确认超时（3 秒），按未送达处理", e);
        } catch (ExecutionException e) {
            throw new AmqpException("等待抢奖消息确认失败", e.getCause());
        }

        log.debug("抢奖消息已确认到达: roundId={}, participantId={}",
                joinData.get("roundId"), joinData.get("participantId"));
    }

    /**
     * 发送弹幕消息
     * 消息体里补一个 messageId，消费端用它做幂等去重与重试计数
     */
    public void sendDanmaku(Map<String, Object> danmakuData) {
        try {
            if (danmakuData.get("messageId") == null) {
                danmakuData.put("messageId", UUID.randomUUID().toString().replace("-", ""));
            }
            rabbitTemplate.convertAndSend(
                    RabbitConfig.DANMAKU_EXCHANGE,
                    RabbitConfig.DANMAKU_ROUTING_KEY,
                    danmakuData
            );
            log.debug("弹幕消息已发送到队列: {}", danmakuData.get("content"));
        } catch (Exception e) {
            log.error("发送弹幕消息失败: {}", e.getMessage(), e);
        }
    }

    /**
     * 发送中奖结果广播消息
     */
    public void sendLotteryResult(Object winners) {
        try {
            rabbitTemplate.convertAndSend(
                    RabbitConfig.LOTTERY_RESULT_EXCHANGE,
                    RabbitConfig.LOTTERY_RESULT_ROUTING_KEY,
                    winners
            );
            log.debug("抽奖结果消息已发送到队列");
        } catch (Exception e) {
            log.error("发送抽奖结果消息失败: {}", e.getMessage(), e);
        }
    }

    /**
     * 发送在线人数更新消息
     */
    public void sendOnlineCount(int count) {
        try {
            rabbitTemplate.convertAndSend(
                    RabbitConfig.ONLINE_COUNT_EXCHANGE,
                    RabbitConfig.ONLINE_COUNT_ROUTING_KEY,
                    count
            );
            log.debug("在线人数消息已发送到队列: {}", count);
        } catch (Exception e) {
            log.error("发送在线人数消息失败: {}", e.getMessage(), e);
        }
    }
}

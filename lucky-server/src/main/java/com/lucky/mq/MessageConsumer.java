package com.lucky.mq;

import com.lucky.config.RabbitConfig;
import com.lucky.websocket.LuckyWebSocketHandler;
import com.rabbitmq.client.Channel;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.support.AmqpHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.Map;

/**
 * 消息消费者（支持手动ACK）
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MessageConsumer {

    private final LuckyWebSocketHandler webSocketHandler;

    /**
     * 消费弹幕消息，广播到所有WebSocket客户端
     */
    @RabbitListener(queues = RabbitConfig.DANMAKU_QUEUE)
    public void handleDanmaku(Map<String, Object> danmakuData,
                              Channel channel,
                              @Header(AmqpHeaders.DELIVERY_TAG) long deliveryTag) throws IOException {
        try {
            webSocketHandler.broadcast("danmaku", danmakuData);
            log.debug("弹幕消息已广播: {}", danmakuData.get("content"));
            // 手动确认消息
            channel.basicAck(deliveryTag, false);
        } catch (Exception e) {
            log.error("处理弹幕消息失败: {}", e.getMessage(), e);
            // 消息处理失败，拒绝并重新入队
            channel.basicNack(deliveryTag, false, true);
        }
    }

    /**
     * 消费抽奖结果消息，广播到所有WebSocket客户端
     */
    @RabbitListener(queues = RabbitConfig.LOTTERY_RESULT_QUEUE)
    public void handleLotteryResult(Object winners,
                                    Channel channel,
                                    @Header(AmqpHeaders.DELIVERY_TAG) long deliveryTag) throws IOException {
        try {
            webSocketHandler.broadcast("lottery_result", winners);
            log.debug("抽奖结果已广播");
            // 手动确认消息
            channel.basicAck(deliveryTag, false);
        } catch (Exception e) {
            log.error("处理抽奖结果消息失败: {}", e.getMessage(), e);
            // 消息处理失败，拒绝并重新入队
            channel.basicNack(deliveryTag, false, true);
        }
    }

    /**
     * 消费在线人数消息，广播到所有WebSocket客户端
     */
    @RabbitListener(queues = RabbitConfig.ONLINE_COUNT_QUEUE)
    public void handleOnlineCount(Integer count,
                                  Channel channel,
                                  @Header(AmqpHeaders.DELIVERY_TAG) long deliveryTag) throws IOException {
        try {
            webSocketHandler.broadcast("online_update", count);
            log.debug("在线人数已广播: {}", count);
            // 手动确认消息
            channel.basicAck(deliveryTag, false);
        } catch (Exception e) {
            log.error("处理在线人数消息失败: {}", e.getMessage(), e);
            // 消息处理失败，拒绝并重新入队
            channel.basicNack(deliveryTag, false, true);
        }
    }
}

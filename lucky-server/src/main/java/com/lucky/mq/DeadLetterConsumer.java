package com.lucky.mq;

import com.lucky.config.RabbitConfig;
import com.rabbitmq.client.Channel;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * 死信队列消费者
 *
 * 各业务队列配了 x-dead-letter-exchange，消费失败超过 3 次的消息会路由到死信队列。
 * 原先没人消费这个队列，进去的消息只能靠人工去 RabbitMQ 控制台翻——等于静默丢失。
 *
 * 现在的策略：**不自动重试**（已经重试 3 次了，自动再试大概率还是失败），
 * 而是把消息**完整记录到 ERROR 日志 + 打指标**，让监控告警能发现积压。
 * 真正需要补数据时，管理员从日志里拿到消息体手工处理。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DeadLetterConsumer {

    private final MeterRegistry meterRegistry;

    @RabbitListener(queues = RabbitConfig.DEAD_LETTER_QUEUE)
    public void onDeadLetter(Message message, Channel channel) throws IOException {
        String body = new String(message.getBody(), StandardCharsets.UTF_8);
        String routingKey = message.getMessageProperties().getReceivedRoutingKey();
        String exchange = message.getMessageProperties().getReceivedExchange();

        // 打 ERROR 级别：这是需要人关注的异常事件，不是普通日志
        log.error("死信消息进入死信队列: exchange={}, routingKey={}, body={}",
                exchange, routingKey, body);

        // 指标：Prometheus 里能看到死信积压速率，配合告警规则自动发现
        meterRegistry.counter("lucky.mq.dead_letter_total").increment();

        // 确认消费（从死信队列移除），避免消息在死信队列里无限堆积。
        // 消息内容已在日志里留档，不会丢。
        channel.basicAck(message.getMessageProperties().getDeliveryTag(), false);
    }
}

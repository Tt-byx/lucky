package com.lucky.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.*;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.HashMap;
import java.util.Map;

/**
 * RabbitMQ 配置（消息可靠性）
 *
 * 关于队列 TTL 的修正说明：
 * 原实现给三个业务队列都设了 x-message-ttl=30s。
 * 这是典型的反模式——消息在队列里排队超过 30 秒就会被直接投进死信队列，
 * 弹幕高峰期一积压，大量「完全正常」的消息会被误杀。
 * TTL 只应该用在延迟队列上，业务队列靠「消费失败 + basicNack(requeue=false)」进死信即可。
 */
@Slf4j
@Configuration
public class RabbitConfig {

    // ==================== 弹幕 ====================
    public static final String DANMAKU_QUEUE = "lucky.danmaku.queue";
    public static final String DANMAKU_EXCHANGE = "lucky.danmaku.exchange";
    public static final String DANMAKU_ROUTING_KEY = "lucky.danmaku.routing";

    // ==================== 抢奖 ====================
    public static final String LOTTERY_JOIN_QUEUE = "lucky.lottery.join.queue";
    public static final String LOTTERY_JOIN_EXCHANGE = "lucky.lottery.join.exchange";
    public static final String LOTTERY_JOIN_ROUTING_KEY = "lucky.lottery.join.routing";

    // ==================== 抽奖结果广播 ====================
    public static final String LOTTERY_RESULT_QUEUE = "lucky.lottery.result.queue";
    public static final String LOTTERY_RESULT_EXCHANGE = "lucky.lottery.result.exchange";
    public static final String LOTTERY_RESULT_ROUTING_KEY = "lucky.lottery.result.routing";

    // ==================== 在线人数 ====================
    public static final String ONLINE_COUNT_QUEUE = "lucky.online.count.queue";
    public static final String ONLINE_COUNT_EXCHANGE = "lucky.online.count.exchange";
    public static final String ONLINE_COUNT_ROUTING_KEY = "lucky.online.count.routing";

    // ==================== 死信 ====================
    public static final String DEAD_LETTER_QUEUE = "lucky.dead.letter.queue";
    public static final String DEAD_LETTER_EXCHANGE = "lucky.dead.letter.exchange";
    public static final String DEAD_LETTER_ROUTING_KEY = "lucky.dead.letter.routing";

    // ==================== 死信队列 ====================

    @Bean
    public Queue deadLetterQueue() {
        return QueueBuilder.durable(DEAD_LETTER_QUEUE).build();
    }

    @Bean
    public DirectExchange deadLetterExchange() {
        return new DirectExchange(DEAD_LETTER_EXCHANGE);
    }

    @Bean
    public Binding deadLetterBinding(Queue deadLetterQueue, DirectExchange deadLetterExchange) {
        return BindingBuilder.bind(deadLetterQueue).to(deadLetterExchange).with(DEAD_LETTER_ROUTING_KEY);
    }

    // ==================== 弹幕队列（带死信，不设 TTL） ====================

    @Bean
    public Queue danmakuQueue() {
        Map<String, Object> args = new HashMap<>();
        args.put("x-dead-letter-exchange", DEAD_LETTER_EXCHANGE);
        args.put("x-dead-letter-routing-key", DEAD_LETTER_ROUTING_KEY);
        return QueueBuilder.durable(DANMAKU_QUEUE).withArguments(args).build();
    }

    @Bean
    public DirectExchange danmakuExchange() {
        return new DirectExchange(DANMAKU_EXCHANGE);
    }

    @Bean
    public Binding danmakuBinding(Queue danmakuQueue, DirectExchange danmakuExchange) {
        return BindingBuilder.bind(danmakuQueue).to(danmakuExchange).with(DANMAKU_ROUTING_KEY);
    }

    // ==================== 抢奖队列（带死信，不设 TTL） ====================

    @Bean
    public Queue lotteryJoinQueue() {
        Map<String, Object> args = new HashMap<>();
        args.put("x-dead-letter-exchange", DEAD_LETTER_EXCHANGE);
        args.put("x-dead-letter-routing-key", DEAD_LETTER_ROUTING_KEY);
        return QueueBuilder.durable(LOTTERY_JOIN_QUEUE).withArguments(args).build();
    }

    @Bean
    public DirectExchange lotteryJoinExchange() {
        return new DirectExchange(LOTTERY_JOIN_EXCHANGE);
    }

    @Bean
    public Binding lotteryJoinBinding(Queue lotteryJoinQueue, DirectExchange lotteryJoinExchange) {
        return BindingBuilder.bind(lotteryJoinQueue).to(lotteryJoinExchange).with(LOTTERY_JOIN_ROUTING_KEY);
    }

    // ==================== 抽奖结果队列（带死信，不设 TTL） ====================

    @Bean
    public Queue lotteryResultQueue() {
        Map<String, Object> args = new HashMap<>();
        args.put("x-dead-letter-exchange", DEAD_LETTER_EXCHANGE);
        args.put("x-dead-letter-routing-key", DEAD_LETTER_ROUTING_KEY);
        return QueueBuilder.durable(LOTTERY_RESULT_QUEUE).withArguments(args).build();
    }

    @Bean
    public DirectExchange lotteryResultExchange() {
        return new DirectExchange(LOTTERY_RESULT_EXCHANGE);
    }

    @Bean
    public Binding lotteryResultBinding(Queue lotteryResultQueue, DirectExchange lotteryResultExchange) {
        return BindingBuilder.bind(lotteryResultQueue).to(lotteryResultExchange).with(LOTTERY_RESULT_ROUTING_KEY);
    }

    // ==================== 在线人数队列（带死信，不设 TTL） ====================

    @Bean
    public Queue onlineCountQueue() {
        Map<String, Object> args = new HashMap<>();
        args.put("x-dead-letter-exchange", DEAD_LETTER_EXCHANGE);
        args.put("x-dead-letter-routing-key", DEAD_LETTER_ROUTING_KEY);
        return QueueBuilder.durable(ONLINE_COUNT_QUEUE).withArguments(args).build();
    }

    @Bean
    public DirectExchange onlineCountExchange() {
        return new DirectExchange(ONLINE_COUNT_EXCHANGE);
    }

    @Bean
    public Binding onlineCountBinding(Queue onlineCountQueue, DirectExchange onlineCountExchange) {
        return BindingBuilder.bind(onlineCountQueue).to(onlineCountExchange).with(ONLINE_COUNT_ROUTING_KEY);
    }

    // ==================== 消息转换器 ====================

    @Bean
    public MessageConverter jsonMessageConverter() {
        return new Jackson2JsonMessageConverter();
    }

    // ==================== RabbitTemplate（支持 Publisher Confirm） ====================

    @Bean
    public RabbitTemplate rabbitTemplate(ConnectionFactory connectionFactory) {
        RabbitTemplate template = new RabbitTemplate(connectionFactory);
        template.setMessageConverter(jsonMessageConverter());

        template.setConfirmCallback((correlationData, ack, cause) -> {
            if (!ack) {
                log.error("消息发送失败: correlationData={}, cause={}", correlationData, cause);
            } else {
                log.debug("消息发送成功: correlationData={}", correlationData);
            }
        });

        template.setReturnsCallback(returned -> {
            log.error("消息退回: exchange={}, routingKey={}, replyText={}",
                    returned.getExchange(), returned.getRoutingKey(), returned.getReplyText());
        });

        template.setMandatory(true);

        return template;
    }
}

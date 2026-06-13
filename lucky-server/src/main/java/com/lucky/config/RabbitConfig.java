package com.lucky.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.*;
import org.springframework.amqp.rabbit.config.SimpleRabbitListenerContainerFactory;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.HashMap;
import java.util.Map;

/**
 * RabbitMQ配置类（支持消息可靠性）
 */
@Slf4j
@Configuration
public class RabbitConfig {

    /**
     * 弹幕队列
     */
    public static final String DANMAKU_QUEUE = "lucky.danmaku.queue";

    /**
     * 弹幕交换机
     */
    public static final String DANMAKU_EXCHANGE = "lucky.danmaku.exchange";

    /**
     * 弹幕路由键
     */
    public static final String DANMAKU_ROUTING_KEY = "lucky.danmaku.routing";

    /**
     * 抽奖结果队列
     */
    public static final String LOTTERY_RESULT_QUEUE = "lucky.lottery.result.queue";

    /**
     * 抽奖结果交换机
     */
    public static final String LOTTERY_RESULT_EXCHANGE = "lucky.lottery.result.exchange";

    /**
     * 抽奖结果路由键
     */
    public static final String LOTTERY_RESULT_ROUTING_KEY = "lucky.lottery.result.routing";

    /**
     * 在线人数队列
     */
    public static final String ONLINE_COUNT_QUEUE = "lucky.online.count.queue";

    /**
     * 在线人数交换机
     */
    public static final String ONLINE_COUNT_EXCHANGE = "lucky.online.count.exchange";

    /**
     * 在线人数路由键
     */
    public static final String ONLINE_COUNT_ROUTING_KEY = "lucky.online.count.routing";

    /**
     * 死信队列
     */
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

    // ==================== 弹幕相关（带死信） ====================

    @Bean
    public Queue danmakuQueue() {
        Map<String, Object> args = new HashMap<>();
        // 绑定死信交换机
        args.put("x-dead-letter-exchange", DEAD_LETTER_EXCHANGE);
        args.put("x-dead-letter-routing-key", DEAD_LETTER_ROUTING_KEY);
        // 消息TTL（30秒）
        args.put("x-message-ttl", 30000);
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

    // ==================== 抽奖结果相关（带死信） ====================

    @Bean
    public Queue lotteryResultQueue() {
        Map<String, Object> args = new HashMap<>();
        args.put("x-dead-letter-exchange", DEAD_LETTER_EXCHANGE);
        args.put("x-dead-letter-routing-key", DEAD_LETTER_ROUTING_KEY);
        args.put("x-message-ttl", 30000);
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

    // ==================== 在线人数相关（带死信） ====================

    @Bean
    public Queue onlineCountQueue() {
        Map<String, Object> args = new HashMap<>();
        args.put("x-dead-letter-exchange", DEAD_LETTER_EXCHANGE);
        args.put("x-dead-letter-routing-key", DEAD_LETTER_ROUTING_KEY);
        args.put("x-message-ttl", 30000);
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

        // 启用 Publisher Confirm
        template.setConfirmCallback((correlationData, ack, cause) -> {
            if (!ack) {
                log.error("消息发送失败: correlationData={}, cause={}", correlationData, cause);
            } else {
                log.debug("消息发送成功: correlationData={}", correlationData);
            }
        });

        // 启用 Publisher Return
        template.setReturnsCallback(returned -> {
            log.error("消息退回: exchange={}, routingKey={}, replyText={}, message={}",
                    returned.getExchange(), returned.getRoutingKey(),
                    returned.getReplyText(), returned.getMessage());
        });

        // 设置 mandatory 为 true（支持消息退回）
        template.setMandatory(true);

        return template;
    }
}

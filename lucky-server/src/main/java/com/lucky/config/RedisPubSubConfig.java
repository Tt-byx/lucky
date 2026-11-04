package com.lucky.config;

import com.lucky.websocket.WsClusterPublisher;
import com.lucky.websocket.WsClusterSubscriber;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;

/**
 * Redis Pub/Sub 配置
 *
 * 用于 WebSocket 跨实例广播：一个实例 publish，所有实例的 subscriber 都收到。
 */
@Configuration
@RequiredArgsConstructor
public class RedisPubSubConfig {

    private final WsClusterSubscriber wsClusterSubscriber;

    @Bean
    public RedisMessageListenerContainer wsMessageListenerContainer(RedisConnectionFactory connectionFactory) {
        RedisMessageListenerContainer container = new RedisMessageListenerContainer();
        container.setConnectionFactory(connectionFactory);
        container.addMessageListener(wsClusterSubscriber, new ChannelTopic(WsClusterPublisher.CHANNEL));
        return container;
    }
}

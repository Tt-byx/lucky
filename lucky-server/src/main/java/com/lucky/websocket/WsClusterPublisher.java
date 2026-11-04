package com.lucky.websocket;

import com.alibaba.fastjson2.JSON;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * 集群广播发布者
 *
 * 所有 WebSocket 广播统一从这里走 Redis Pub/Sub，
 * 保证「用户连在 A 机器、消息在 B 机器产生」时依然能收到。
 *
 * 注意：这里刻意不做「本地先推一次」的优化。
 * 统一由订阅端回调本地推送，语义只有一条路径，排查问题时不会出现
 * 「有的消息推了两次、有的只推了本地」这种玄学现象。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class WsClusterPublisher {

    /** 全局广播频道 */
    public static final String CHANNEL = "lucky:ws:broadcast";

    private final StringRedisTemplate stringRedisTemplate;

    public void publishToActivity(Long activityId, String type, Object data) {
        publish(WsBroadcastMessage.SCOPE_ACTIVITY, activityId, null, type, data);
    }

    public void publishToUser(Long participantId, String type, Object data) {
        publish(WsBroadcastMessage.SCOPE_USER, null, participantId, type, data);
    }

    public void publishToAll(String type, Object data) {
        publish(WsBroadcastMessage.SCOPE_ALL, null, null, type, data);
    }

    public void publishDanmaku(Long activityId, String type, Object data) {
        publish(WsBroadcastMessage.SCOPE_DANMAKU, activityId, null, type, data);
    }

    private void publish(String scope, Long activityId, Long participantId, String type, Object data) {
        WsBroadcastMessage message = new WsBroadcastMessage();
        message.setScope(scope);
        message.setActivityId(activityId);
        message.setParticipantId(participantId);
        message.setType(type);
        message.setData(data);

        try {
            stringRedisTemplate.convertAndSend(CHANNEL, JSON.toJSONString(message));
        } catch (Exception e) {
            // Redis 不可用时降级为「只推本机」由调用方决定是否兜底，这里只记录
            log.error("WebSocket 集群广播发送失败: scope={}, type={}", scope, type, e);
        }
    }
}

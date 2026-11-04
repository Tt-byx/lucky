package com.lucky.websocket;

import com.alibaba.fastjson2.JSON;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;

/**
 * 集群广播订阅者
 *
 * 每个实例都订阅同一个频道，收到消息后只推「本机持有的连接」。
 * 因为每个连接只会被一台实例持有，所以全局看每条消息只推一次。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class WsClusterSubscriber implements MessageListener {

    private final LuckyWebSocketHandler webSocketHandler;

    @Override
    public void onMessage(Message message, byte[] pattern) {
        String body = new String(message.getBody(), StandardCharsets.UTF_8);

        WsBroadcastMessage msg;
        try {
            msg = JSON.parseObject(body, WsBroadcastMessage.class);
        } catch (Exception e) {
            log.warn("WebSocket 广播消息解析失败: {}", body, e);
            return;
        }
        if (msg == null || msg.getScope() == null) {
            return;
        }

        try {
            switch (msg.getScope()) {
                case WsBroadcastMessage.SCOPE_ACTIVITY:
                    webSocketHandler.localBroadcastToActivity(msg.getActivityId(), msg.getType(), msg.getData());
                    break;
                case WsBroadcastMessage.SCOPE_USER:
                    // 踢人指令比较特殊：只发消息不够，还要断连接
                    if ("force_disconnect".equals(msg.getType())) {
                        webSocketHandler.localDisconnectUser(msg.getParticipantId());
                    } else {
                        webSocketHandler.localSendToUser(msg.getParticipantId(), msg.getType(), msg.getData());
                    }
                    break;
                case WsBroadcastMessage.SCOPE_ALL:
                    webSocketHandler.localBroadcast(msg.getType(), msg.getData());
                    break;
                case WsBroadcastMessage.SCOPE_DANMAKU:
                    webSocketHandler.localBatchBroadcastDanmaku(msg.getActivityId(), msg.getType(), msg.getData());
                    break;
                default:
                    log.warn("未知的广播范围: {}", msg.getScope());
            }
        } catch (Exception e) {
            log.error("本地 WebSocket 推送失败: scope={}, type={}", msg.getScope(), msg.getType(), e);
        }
    }
}

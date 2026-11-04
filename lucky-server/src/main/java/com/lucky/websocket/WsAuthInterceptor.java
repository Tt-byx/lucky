package com.lucky.websocket;

import com.lucky.service.ScanLoginService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;

import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;

/**
 * WebSocket 握手鉴权
 *
 * 初版是 `/ws/lucky?participantId=123` 直接连，等于谁拿到 ID 就能冒充谁：
 * 可以顶别人的身份收定向消息、甚至替别人抢奖。
 *
 * 规则：
 *   带 participantId 的连接（手机端）必须携带有效的扫码令牌，且令牌反查出的 ID 要与声明一致；
 *   不带 participantId 的连接（大屏）只接收场次广播，允许匿名接入。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class WsAuthInterceptor implements HandshakeInterceptor {

    private final ScanLoginService scanLoginService;

    @Override
    public boolean beforeHandshake(ServerHttpRequest request, ServerHttpResponse response,
                                   WebSocketHandler wsHandler, Map<String, Object> attributes) {
        if (!(request instanceof ServletServerHttpRequest servletRequest)) {
            return false;
        }
        HttpServletRequest req = servletRequest.getServletRequest();

        String participantIdStr = req.getParameter("participantId");
        if (participantIdStr == null || participantIdStr.isBlank()) {
            // 大屏端：只订阅广播，不需要身份
            return true;
        }

        String token = req.getParameter("token");
        Long fromToken = scanLoginService.validateToken(token);
        if (fromToken == null) {
            log.warn("WebSocket 握手拒绝：令牌无效, 声称 participantId={}", participantIdStr);
            return false;
        }
        if (!participantIdStr.equals(String.valueOf(fromToken))) {
            log.warn("WebSocket 握手拒绝：令牌与身份不匹配, 声称={}, 实际={}", participantIdStr, fromToken);
            return false;
        }

        // 把校验通过的身份写进 attributes，Handler 只认这个值，不再信任 query
        attributes.put("participantId", fromToken);
        return true;
    }

    @Override
    public void afterHandshake(ServerHttpRequest request, ServerHttpResponse response,
                               WebSocketHandler wsHandler, Exception exception) {
    }
}

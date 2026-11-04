package com.lucky.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;
import com.lucky.websocket.LuckyWebSocketHandler;
import com.lucky.websocket.WsAuthInterceptor;
import lombok.RequiredArgsConstructor;

@Configuration
@EnableWebSocket
@RequiredArgsConstructor
public class WebSocketConfig implements WebSocketConfigurer {

    private final LuckyWebSocketHandler luckyWebSocketHandler;
    private final WsAuthInterceptor wsAuthInterceptor;

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(luckyWebSocketHandler, "/ws/lucky")
                .addInterceptors(wsAuthInterceptor)
                .setAllowedOrigins("*");
    }
}

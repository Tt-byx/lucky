package com.lucky.websocket;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.lucky.util.RedisUtil;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.*;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.io.IOException;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * WebSocket 处理器（支持心跳、异步广播）
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class LuckyWebSocketHandler extends TextWebSocketHandler {

    private final RedisUtil redisUtil;

    // Redis key前缀
    private static final String ONLINE_USERS_KEY = "lucky:online:users";
    private static final String ONLINE_COUNT_KEY = "lucky:online:count";
    private static final String ONLINE_HEARTBEAT_KEY = "lucky:online:heartbeat:";

    // session 映射（本地内存，用于发送消息）
    private final ConcurrentHashMap<String, WebSocketSession> sessionMap = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Long> sessionUserMap = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Long, Set<String>> userSessionMap = new ConcurrentHashMap<>();

    // 在线人数（本地计数器，减少 Redis 调用）
    private final AtomicInteger localOnlineCount = new AtomicInteger(0);

    // 异步广播线程池
    private ExecutorService broadcastExecutor;

    @PostConstruct
    public void init() {
        broadcastExecutor = new ThreadPoolExecutor(
                10, 50, 60L, TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(1000),
                new ThreadFactory() {
                    private final AtomicInteger counter = new AtomicInteger(0);
                    @Override
                    public Thread newThread(Runnable r) {
                        Thread thread = new Thread(r, "ws-broadcast-" + counter.incrementAndGet());
                        thread.setDaemon(true);
                        return thread;
                    }
                }
        );
    }

    @PreDestroy
    public void destroy() {
        if (broadcastExecutor != null) {
            broadcastExecutor.shutdown();
        }
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        sessionMap.put(session.getId(), session);

        // 解析 query 参数中的 participantId
        URI uri = session.getUri();
        if (uri != null && uri.getQuery() != null) {
            Map<String, String> params = parseQuery(uri.getQuery());
            String pidStr = params.get("participantId");
            if (pidStr != null) {
                try {
                    Long participantId = Long.parseLong(pidStr);
                    sessionUserMap.put(session.getId(), participantId);
                    userSessionMap.computeIfAbsent(participantId, k -> ConcurrentHashMap.newKeySet())
                            .add(session.getId());

                    // 将用户添加到Redis在线用户集合
                    redisUtil.setAdd(ONLINE_USERS_KEY, participantId.toString());
                    localOnlineCount.incrementAndGet();
                    redisUtil.set(ONLINE_COUNT_KEY, localOnlineCount.get());

                    // 记录心跳时间
                    updateHeartbeat(session.getId());

                    log.info("WebSocket 用户绑定: session={}, participantId={}", session.getId(), participantId);
                } catch (NumberFormatException e) {
                    log.warn("无效的 participantId: {}", pidStr);
                }
            }
        }

        log.info("WebSocket 连接建立: {}", session.getId());
        broadcastOnlineCount();
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        Long participantId = sessionUserMap.remove(session.getId());
        sessionMap.remove(session.getId());

        // 清除心跳记录
        redisUtil.delete(ONLINE_HEARTBEAT_KEY + session.getId());

        if (participantId != null) {
            Set<String> sids = userSessionMap.get(participantId);
            if (sids != null) {
                sids.remove(session.getId());
                if (sids.isEmpty()) {
                    userSessionMap.remove(participantId);
                    // 从Redis在线用户集合中移除
                    redisUtil.setRemove(ONLINE_USERS_KEY, participantId.toString());
                    localOnlineCount.decrementAndGet();
                    redisUtil.set(ONLINE_COUNT_KEY, localOnlineCount.get());
                }
            }
        }
        log.info("WebSocket 连接关闭: {}", session.getId());
        broadcastOnlineCount();
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) {
        String payload = message.getPayload();

        // 处理心跳消息
        if ("ping".equals(payload)) {
            try {
                updateHeartbeat(session.getId());
                session.sendMessage(new TextMessage("pong"));
            } catch (IOException e) {
                log.error("发送心跳响应失败: {}", session.getId(), e);
            }
            return;
        }

        log.info("收到消息: {}", payload);
    }

    /**
     * 更新心跳时间
     */
    private void updateHeartbeat(String sessionId) {
        redisUtil.set(ONLINE_HEARTBEAT_KEY + sessionId, System.currentTimeMillis(), 60, TimeUnit.SECONDS);
    }

    /**
     * 定时清理无效连接（每30秒执行一次）
     */
    @Scheduled(fixedRate = 30000)
    public void cleanInvalidConnections() {
        long now = System.currentTimeMillis();
        List<String> toRemove = new ArrayList<>();

        for (Map.Entry<String, WebSocketSession> entry : sessionMap.entrySet()) {
            String sessionId = entry.getKey();
            WebSocketSession session = entry.getValue();

            // 检查连接是否有效
            if (!session.isOpen()) {
                toRemove.add(sessionId);
                continue;
            }

            // 检查心跳是否超时（60秒无心跳）
            Object heartbeat = redisUtil.get(ONLINE_HEARTBEAT_KEY + sessionId);
            if (heartbeat != null) {
                long lastHeartbeat = Long.parseLong(heartbeat.toString());
                if (now - lastHeartbeat > 60000) {
                    toRemove.add(sessionId);
                    log.warn("心跳超时，关闭连接: {}", sessionId);
                }
            }
        }

        // 清理无效连接
        for (String sessionId : toRemove) {
            WebSocketSession session = sessionMap.remove(sessionId);
            if (session != null) {
                try {
                    session.close(CloseStatus.SESSION_NOT_RELIABLE);
                } catch (IOException e) {
                    log.error("关闭连接失败: {}", sessionId, e);
                }
            }

            Long participantId = sessionUserMap.remove(sessionId);
            if (participantId != null) {
                Set<String> sids = userSessionMap.get(participantId);
                if (sids != null) {
                    sids.remove(sessionId);
                    if (sids.isEmpty()) {
                        userSessionMap.remove(participantId);
                        redisUtil.setRemove(ONLINE_USERS_KEY, participantId.toString());
                        localOnlineCount.decrementAndGet();
                    }
                }
            }

            redisUtil.delete(ONLINE_HEARTBEAT_KEY + sessionId);
        }

        if (!toRemove.isEmpty()) {
            log.info("清理无效连接: {}个", toRemove.size());
            redisUtil.set(ONLINE_COUNT_KEY, localOnlineCount.get());
            broadcastOnlineCount();
        }
    }

    private void broadcastOnlineCount() {
        broadcast("online_update", localOnlineCount.get());
    }

    /**
     * 向所有客户端广播消息（异步并发）
     */
    public void broadcast(String type, Object data) {
        JSONObject msg = new JSONObject();
        msg.put("type", type);
        msg.put("data", data);
        String json = msg.toJSONString();

        TextMessage textMessage = new TextMessage(json);
        List<CompletableFuture<Void>> futures = new ArrayList<>();

        for (WebSocketSession session : sessionMap.values()) {
            if (session.isOpen()) {
                CompletableFuture<Void> future = CompletableFuture.runAsync(() -> {
                    synchronized (session) {
                        try {
                            session.sendMessage(textMessage);
                        } catch (IOException | IllegalStateException e) {
                            log.error("发送消息失败: {}", session.getId(), e);
                        }
                    }
                }, broadcastExecutor);
                futures.add(future);
            }
        }

        // 等待所有发送完成（可选，用于确保消息送达）
        if (!futures.isEmpty()) {
            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                    .exceptionally(e -> {
                        log.error("广播消息异常: {}", e.getMessage());
                        return null;
                    });
        }
    }

    /**
     * 获取在线用户数量
     */
    public int getOnlineParticipantCount() {
        return localOnlineCount.get();
    }

    /**
     * 获取所有在线用户的 participantId 集合（从Redis获取）
     */
    public Set<Long> getOnlineParticipantIds() {
        Set<Object> members = redisUtil.setMembers(ONLINE_USERS_KEY);
        Set<Long> ids = new HashSet<>();
        if (members != null) {
            for (Object member : members) {
                try {
                    ids.add(Long.parseLong(member.toString()));
                } catch (NumberFormatException e) {
                    log.warn("无效的在线用户ID: {}", member);
                }
            }
        }
        return ids;
    }

    /**
     * 向指定用户的所有 session 推送消息
     */
    public void sendToUser(Long participantId, String type, Object data) {
        Set<String> sids = userSessionMap.get(participantId);
        if (sids == null) return;

        JSONObject msg = new JSONObject();
        msg.put("type", type);
        msg.put("data", data);
        String json = msg.toJSONString();
        TextMessage textMessage = new TextMessage(json);

        for (String sid : sids) {
            WebSocketSession session = sessionMap.get(sid);
            if (session != null && session.isOpen()) {
                synchronized (session) {
                    try {
                        session.sendMessage(textMessage);
                    } catch (IOException | IllegalStateException e) {
                        log.error("发送消息失败: {}", sid, e);
                    }
                }
            }
        }
    }

    /**
     * 强制断开指定用户的所有连接
     */
    public void disconnectUser(Long participantId) {
        Set<String> sids = userSessionMap.get(participantId);
        if (sids == null) return;

        for (String sid : new ArrayList<>(sids)) {
            WebSocketSession session = sessionMap.get(sid);
            if (session != null && session.isOpen()) {
                try {
                    session.close(CloseStatus.NORMAL);
                } catch (IOException e) {
                    log.error("关闭连接失败: {}", sid, e);
                }
            }
        }
    }

    public int getOnlineCount() {
        return getOnlineParticipantCount();
    }

    private Map<String, String> parseQuery(String query) {
        Map<String, String> params = new HashMap<>();
        for (String pair : query.split("&")) {
            String[] kv = pair.split("=", 2);
            if (kv.length == 2) {
                params.put(URLDecoder.decode(kv[0], StandardCharsets.UTF_8),
                           URLDecoder.decode(kv[1], StandardCharsets.UTF_8));
            }
        }
        return params;
    }
}

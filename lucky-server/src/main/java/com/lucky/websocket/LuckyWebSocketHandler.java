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
 * WebSocket 处理器（场次分组架构 + 心跳保活 + 批量推送）
 *
 * 核心设计：
 * 1. 场次-连接 Map 分组：不同活动场次的用户连接独立存储、隔离推送
 * 2. 摒弃全局所有连接遍历推送的低效逻辑，仅向当前场次在线客户端推送
 * 3. 30s 心跳机制 + 定时清理僵尸连接，释放内存与端口资源
 * 4. 毫秒级批量合并推送，降低单条频繁 IO 与 CPU 占用
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class LuckyWebSocketHandler extends TextWebSocketHandler {

    private final RedisUtil redisUtil;

    // ==================== Redis Key 前缀 ====================
    private static final String ONLINE_USERS_KEY = "lucky:online:users";
    private static final String ONLINE_COUNT_KEY = "lucky:online:count";
    private static final String ONLINE_HEARTBEAT_KEY = "lucky:online:heartbeat:";
    private static final String ACTIVITY_ONLINE_COUNT_KEY = "lucky:activity:online:count:";

    // ==================== 连接管理（场次分组核心） ====================

    /** sessionId -> WebSocketSession，全局会话查找 */
    private final ConcurrentHashMap<String, WebSocketSession> sessionMap = new ConcurrentHashMap<>();

    /** sessionId -> participantId，会话绑定用户 */
    private final ConcurrentHashMap<String, Long> sessionUserMap = new ConcurrentHashMap<>();

    /** participantId -> Set<sessionId>，用户多端连接 */
    private final ConcurrentHashMap<Long, Set<String>> userSessionMap = new ConcurrentHashMap<>();

    /** activityId -> Set<sessionId>，场次分组连接（核心优化：隔离推送） */
    private final ConcurrentHashMap<Long, Set<String>> activitySessionMap = new ConcurrentHashMap<>();

    /** sessionId -> activityId，快速反查会话所属场次 */
    private final ConcurrentHashMap<String, Long> sessionActivityMap = new ConcurrentHashMap<>();

    // ==================== 在线计数 ====================

    /** 全局在线人数（本地计数器，减少 Redis 调用） */
    private final AtomicInteger localOnlineCount = new AtomicInteger(0);

    // ==================== 批量推送缓冲 ====================

    /** 场次 -> 待批量推送的消息队列 */
    private final ConcurrentHashMap<Long, List<String>> batchBuffer = new ConcurrentHashMap<>();

    /** 批量推送阈值：达到该数量立即刷新 */
    private static final int BATCH_THRESHOLD = 50;

    /** 批量推送刷新间隔（毫秒） */
    private static final long BATCH_FLUSH_INTERVAL_MS = 50L;

    // ==================== 异步广播线程池 ====================

    private ExecutorService broadcastExecutor;
    private ScheduledExecutorService batchFlushExecutor;

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
                });

        // 批量推送定时刷新任务（每 50ms 刷新一次缓冲）
        batchFlushExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread thread = new Thread(r, "ws-batch-flush");
            thread.setDaemon(true);
            return thread;
        });
        batchFlushExecutor.scheduleAtFixedRate(this::flushAllBatchBuffers,
                BATCH_FLUSH_INTERVAL_MS, BATCH_FLUSH_INTERVAL_MS, TimeUnit.MILLISECONDS);

        log.info("WebSocket 场次分组处理器初始化完成");
    }

    @PreDestroy
    public void destroy() {
        if (broadcastExecutor != null)
            broadcastExecutor.shutdown();
        if (batchFlushExecutor != null)
            batchFlushExecutor.shutdown();
    }

    // ==================== 连接建立 ====================

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        sessionMap.put(session.getId(), session);

        // 解析 query 参数：participantId + activityId（场次分组核心）
        URI uri = session.getUri();
        if (uri != null && uri.getQuery() != null) {
            Map<String, String> params = parseQuery(uri.getQuery());

            // 解析场次 ID（必须，用于分组隔离）
            String activityIdStr = params.get("activityId");
            Long activityId = null;
            if (activityIdStr != null) {
                try {
                    activityId = Long.parseLong(activityIdStr);
                    sessionActivityMap.put(session.getId(), activityId);
                    // 注册到场次分组
                    activitySessionMap.computeIfAbsent(activityId, k -> ConcurrentHashMap.newKeySet())
                            .add(session.getId());
                    log.info("WebSocket 连接注册到场次: session={}, activityId={}", session.getId(), activityId);
                } catch (NumberFormatException e) {
                    log.warn("无效的 activityId: {}", activityIdStr);
                }
            }

            // 解析用户 ID
            String pidStr = params.get("participantId");
            if (pidStr != null) {
                try {
                    Long participantId = Long.parseLong(pidStr);
                    sessionUserMap.put(session.getId(), participantId);
                    userSessionMap.computeIfAbsent(participantId, k -> ConcurrentHashMap.newKeySet())
                            .add(session.getId());

                    // Redis 在线用户集合
                    redisUtil.setAdd(ONLINE_USERS_KEY, participantId.toString());
                    localOnlineCount.incrementAndGet();
                    redisUtil.set(ONLINE_COUNT_KEY, localOnlineCount.get());

                    // 场次在线人数 +1
                    if (activityId != null) {
                        String activityCountKey = ACTIVITY_ONLINE_COUNT_KEY + activityId;
                        redisUtil.increment(activityCountKey);
                        redisUtil.expire(activityCountKey, 24, TimeUnit.HOURS);
                    }

                    // 记录心跳时间
                    updateHeartbeat(session.getId());

                    log.info("WebSocket 用户绑定: session={}, participantId={}, activityId={}",
                            session.getId(), participantId, activityId);
                } catch (NumberFormatException e) {
                    log.warn("无效的 participantId: {}", pidStr);
                }
            }
        }

        log.info("WebSocket 连接建立: {}", session.getId());
        broadcastOnlineCount();
    }

    // ==================== 连接关闭 ====================

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        String sessionId = session.getId();

        // 从用户映射移除
        Long participantId = sessionUserMap.remove(sessionId);
        sessionMap.remove(sessionId);

        // 从场次分组移除（核心：隔离清理）
        Long activityId = sessionActivityMap.remove(sessionId);
        if (activityId != null) {
            Set<String> activitySessions = activitySessionMap.get(activityId);
            if (activitySessions != null) {
                activitySessions.remove(sessionId);
                if (activitySessions.isEmpty()) {
                    activitySessionMap.remove(activityId);
                }
            }
            // 场次在线人数 -1
            String activityCountKey = ACTIVITY_ONLINE_COUNT_KEY + activityId;
            Object count = redisUtil.get(activityCountKey);
            if (count != null) {
                redisUtil.decrement(activityCountKey);
            }
        }

        // 清除心跳记录
        redisUtil.delete(ONLINE_HEARTBEAT_KEY + sessionId);

        if (participantId != null) {
            Set<String> sids = userSessionMap.get(participantId);
            if (sids != null) {
                sids.remove(sessionId);
                if (sids.isEmpty()) {
                    userSessionMap.remove(participantId);
                    redisUtil.setRemove(ONLINE_USERS_KEY, participantId.toString());
                    localOnlineCount.decrementAndGet();
                    redisUtil.set(ONLINE_COUNT_KEY, localOnlineCount.get());
                }
            }
        }

        log.info("WebSocket 连接关闭: session={}, activityId={}", sessionId, activityId);
        broadcastOnlineCount();
    }

    // ==================== 消息处理 ====================

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

        log.info("收到消息: session={}, payload={}", session.getId(), payload);
    }

    // ==================== 场次定向广播（核心优化） ====================

    /**
     * 向指定场次的所有在线客户端广播消息（场次分组隔离推送）
     * 仅推送至当前活动场次的大屏端和在线用户，大幅减少无效推送
     *
     * @param activityId 活动场次 ID
     * @param type       消息类型
     * @param data       消息数据
     */
    public void broadcastToActivity(Long activityId, String type, Object data) {
        if (activityId == null) {
            log.warn("broadcastToActivity: activityId 为 null，降级为全局广播");
            broadcast(type, data);
            return;
        }

        Set<String> sessionIds = activitySessionMap.get(activityId);
        if (sessionIds == null || sessionIds.isEmpty()) {
            log.debug("场次 {} 无在线连接，跳过推送", activityId);
            return;
        }

        JSONObject msg = new JSONObject();
        msg.put("type", type);
        msg.put("data", data);
        String json = msg.toJSONString();
        TextMessage textMessage = new TextMessage(json);

        // 异步并发推送到该场次所有连接
        List<CompletableFuture<Void>> futures = new ArrayList<>();
        for (String sessionId : sessionIds) {
            WebSocketSession session = sessionMap.get(sessionId);
            if (session != null && session.isOpen()) {
                CompletableFuture<Void> future = CompletableFuture.runAsync(() -> {
                    synchronized (session) {
                        try {
                            session.sendMessage(textMessage);
                        } catch (IOException | IllegalStateException e) {
                            log.error("场次推送失败: session={}, activityId={}", sessionId, activityId, e);
                        }
                    }
                }, broadcastExecutor);
                futures.add(future);
            }
        }

        if (!futures.isEmpty()) {
            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                    .exceptionally(e -> {
                        log.error("场次广播异常: activityId={}, {}", activityId, e.getMessage());
                        return null;
                    });
        }

        log.debug("场次定向广播完成: activityId={}, type={}, 推送连接数={}",
                activityId, type, sessionIds.size());
    }

    /**
     * 批量推送弹幕到指定场次（毫秒级批量合并，降低 IO）
     * 高频时段将多条弹幕合并为一次推送，减少单条频繁 IO
     */
    public void batchBroadcastDanmaku(Long activityId, String type, Object data) {
        if (activityId == null) {
            broadcastToActivity(null, type, data);
            return;
        }

        JSONObject msg = new JSONObject();
        msg.put("type", type);
        msg.put("data", data);
        String json = msg.toJSONString();

        // 加入场次批量缓冲
        List<String> buffer = batchBuffer.computeIfAbsent(activityId,
                k -> Collections.synchronizedList(new ArrayList<>()));
        buffer.add(json);

        // 达到阈值立即刷新
        if (buffer.size() >= BATCH_THRESHOLD) {
            flushBatchBuffer(activityId);
        }
    }

    /**
     * 刷新指定场次的批量缓冲
     */
    private void flushBatchBuffer(Long activityId) {
        List<String> buffer = batchBuffer.get(activityId);
        if (buffer == null || buffer.isEmpty())
            return;

        List<String> messages;
        synchronized (buffer) {
            if (buffer.isEmpty())
                return;
            messages = new ArrayList<>(buffer);
            buffer.clear();
        }

        Set<String> sessionIds = activitySessionMap.get(activityId);
        if (sessionIds == null || sessionIds.isEmpty())
            return;

        // 将多条弹幕打包为一条批量消息
        JSONObject batchMsg = new JSONObject();
        batchMsg.put("type", "danmaku_batch");
        batchMsg.put("data", messages);
        String json = batchMsg.toJSONString();
        TextMessage textMessage = new TextMessage(json);

        for (String sessionId : sessionIds) {
            WebSocketSession session = sessionMap.get(sessionId);
            if (session != null && session.isOpen()) {
                CompletableFuture.runAsync(() -> {
                    synchronized (session) {
                        try {
                            session.sendMessage(textMessage);
                        } catch (IOException | IllegalStateException e) {
                            log.error("批量推送失败: session={}", sessionId, e);
                        }
                    }
                }, broadcastExecutor);
            }
        }
    }

    /**
     * 定时刷新所有场次的批量缓冲
     */
    private void flushAllBatchBuffers() {
        for (Long activityId : batchBuffer.keySet()) {
            flushBatchBuffer(activityId);
        }
    }

    // ==================== 全局广播（仅系统消息使用） ====================

    /**
     * 向所有客户端广播消息（全局广播，仅用于系统级通知）
     * 业务消息应使用 broadcastToActivity 进行场次定向推送
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
                            log.error("全局广播发送失败: {}", session.getId(), e);
                        }
                    }
                }, broadcastExecutor);
                futures.add(future);
            }
        }

        if (!futures.isEmpty()) {
            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                    .exceptionally(e -> {
                        log.error("全局广播异常: {}", e.getMessage());
                        return null;
                    });
        }
    }

    private void broadcastOnlineCount() {
        broadcast("online_update", localOnlineCount.get());
    }

    // ==================== 心跳保活 + 僵尸连接清理 ====================

    private void updateHeartbeat(String sessionId) {
        redisUtil.set(ONLINE_HEARTBEAT_KEY + sessionId, System.currentTimeMillis(), 60, TimeUnit.SECONDS);
    }

    /**
     * 定时清理无效连接（每 30 秒执行一次）
     * 自动检测离线用户，清理断开、僵尸长连接，释放服务内存与端口资源
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

            // 检查心跳是否超时（60 秒无心跳判定为僵尸连接）
            Object heartbeat = redisUtil.get(ONLINE_HEARTBEAT_KEY + sessionId);
            if (heartbeat == null || now - Long.parseLong(heartbeat.toString()) > 60000) {
                toRemove.add(sessionId);
                log.warn("心跳超时，关闭连接: sessionId={}", sessionId);
            }
        }

        // 清理无效连接（同步调用 afterConnectionClosed 逻辑确保所有映射一致）
        for (String sessionId : toRemove) {
            WebSocketSession session = sessionMap.remove(sessionId);
            if (session != null) {
                try {
                    session.close(CloseStatus.SESSION_NOT_RELIABLE);
                } catch (IOException e) {
                    log.error("关闭僵尸连接失败: {}", sessionId, e);
                }
            }

            // 从用户映射清理
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

            // 从场次分组清理（核心：隔离清理）
            Long activityId = sessionActivityMap.remove(sessionId);
            if (activityId != null) {
                Set<String> activitySessions = activitySessionMap.get(activityId);
                if (activitySessions != null) {
                    activitySessions.remove(sessionId);
                    if (activitySessions.isEmpty()) {
                        activitySessionMap.remove(activityId);
                    }
                }
            }

            redisUtil.delete(ONLINE_HEARTBEAT_KEY + sessionId);
        }

        if (!toRemove.isEmpty()) {
            log.info("定时清理僵尸连接: {} 个", toRemove.size());
            redisUtil.set(ONLINE_COUNT_KEY, localOnlineCount.get());
            broadcastOnlineCount();
        }
    }

    // ==================== 查询与用户定向推送 ====================

    public int getOnlineParticipantCount() {
        return localOnlineCount.get();
    }

    /**
     * 获取指定场次的在线人数
     */
    public int getActivityOnlineCount(Long activityId) {
        Set<String> sessions = activitySessionMap.get(activityId);
        return sessions != null ? sessions.size() : 0;
    }

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
        if (sids == null)
            return;

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
                        log.error("用户定向推送失败: sid={}", sid, e);
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
        if (sids == null)
            return;

        for (String sid : new ArrayList<>(sids)) {
            WebSocketSession session = sessionMap.get(sid);
            if (session != null && session.isOpen()) {
                try {
                    session.close(CloseStatus.NORMAL);
                } catch (IOException e) {
                    log.error("强制断开连接失败: sid={}", sid, e);
                }
            }
        }
    }

    public int getOnlineCount() {
        return getOnlineParticipantCount();
    }

    // ==================== 工具方法 ====================

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

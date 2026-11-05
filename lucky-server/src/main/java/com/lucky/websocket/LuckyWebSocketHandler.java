package com.lucky.websocket;

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
 * WebSocket 处理器（场次分组 + 集群广播 + 心跳保活 + 批量推送）
 *
 * 相比初版的两处架构级修改：
 * 1. 广播改走 Redis Pub/Sub。原来 sessionMap 在 JVM 内存里，
 *    部署两台机器时，A 机器消费到的消息只推给 A 的连接，B 上的用户永远收不到。
 *    现在 publish 到频道，各实例收到后只推自己持有的连接，可以水平扩容。
 * 2. 在线人数从「每次连接/断开都全局广播」改成「每 2 秒检查变化后才推」。
 *    2000 人陆续进场时，原实现是 2000 次广播 × 平均 1000 连接 ≈ 200 万次 sendMessage，
 *    而且每次都会往只有 1000 容量的线程池队列里塞任务，直接触发拒绝策略。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class LuckyWebSocketHandler extends TextWebSocketHandler {

    private final RedisUtil redisUtil;
    private final WsClusterPublisher clusterPublisher;
    private final io.micrometer.core.instrument.MeterRegistry meterRegistry;

    // ==================== Redis Key ====================
    private static final String ONLINE_USERS_KEY = "lucky:online:users";
    private static final String ONLINE_COUNT_KEY = "lucky:online:count";
    private static final String ONLINE_HEARTBEAT_KEY = "lucky:online:heartbeat:";
    private static final String ACTIVITY_ONLINE_COUNT_KEY = "lucky:activity:online:count:";

    // ==================== 连接管理（场次分组） ====================

    private final ConcurrentHashMap<String, WebSocketSession> sessionMap = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Long> sessionUserMap = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Long, Set<String>> userSessionMap = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Long, Set<String>> activitySessionMap = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Long> sessionActivityMap = new ConcurrentHashMap<>();

    /**
     * 没有声明场次的连接（大屏、监控端）。
     *
     * 大屏在 onMounted 时就建连，此时活动信息还没拉回来，拿不到 activityId。
     * 如果只按场次推送，大屏就永远收不到弹幕和抢奖广播——所以这类连接按「全量监听」处理。
     * 弹幕是这条规则的唯一例外：只推这里（unscoped），不推手机连接。
     */
    private final Set<String> unscopedSessions = ConcurrentHashMap.newKeySet();

    // ==================== 在线计数 ====================

    /** 本机在线人数，仅用于日志与本地状态判断；全局人数从 Redis Set 取 */
    private final AtomicInteger localOnlineCount = new AtomicInteger(0);

    /** 上一次广播出去的在线人数，用于「变化才推」 */
    private final AtomicInteger lastBroadcastCount = new AtomicInteger(-1);

    // ==================== 批量推送缓冲 ====================

    private final ConcurrentHashMap<Long, List<String>> batchBuffer = new ConcurrentHashMap<>();
    private static final int BATCH_THRESHOLD = 50;
    private static final long BATCH_FLUSH_INTERVAL_MS = 50L;

    // ==================== 线程池 ====================

    private ExecutorService broadcastExecutor;
    private ScheduledExecutorService batchFlushExecutor;

    @PostConstruct
    public void init() {
        broadcastExecutor = new ThreadPoolExecutor(
                10, 50, 60L, TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(5000),
                new ThreadFactory() {
                    private final AtomicInteger counter = new AtomicInteger(0);

                    @Override
                    public Thread newThread(Runnable r) {
                        Thread thread = new Thread(r, "ws-broadcast-" + counter.incrementAndGet());
                        thread.setDaemon(true);
                        return thread;
                    }
                },
                // 队列满时降级为调用线程执行，宁可慢一点也不丢消息
                new ThreadPoolExecutor.CallerRunsPolicy());

        batchFlushExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread thread = new Thread(r, "ws-batch-flush");
            thread.setDaemon(true);
            return thread;
        });
        batchFlushExecutor.scheduleAtFixedRate(this::flushAllBatchBuffers,
                BATCH_FLUSH_INTERVAL_MS, BATCH_FLUSH_INTERVAL_MS, TimeUnit.MILLISECONDS);

        log.info("WebSocket 场次分组处理器初始化完成（集群广播已启用）");

        // 暴露本机连接数指标，多实例时可以对比各节点负载是否均衡
        if (meterRegistry != null) {
            io.micrometer.core.instrument.Gauge
                    .builder("lucky.ws.local_connections", localOnlineCount, AtomicInteger::get)
                    .description("本实例持有的 WebSocket 连接数")
                    .register(meterRegistry);
        }
    }

    @PreDestroy
    public void destroy() {
        if (broadcastExecutor != null) {
            broadcastExecutor.shutdown();
        }
        if (batchFlushExecutor != null) {
            batchFlushExecutor.shutdown();
        }
    }

    // ==================== 连接建立 ====================

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        sessionMap.put(session.getId(), session);

        URI uri = session.getUri();
        Map<String, String> params = (uri != null && uri.getQuery() != null)
                ? parseQuery(uri.getQuery())
                : Collections.emptyMap();

        String activityIdStr = params.get("activityId");
        Long activityId = null;
        if (activityIdStr != null) {
            try {
                activityId = Long.parseLong(activityIdStr);
                sessionActivityMap.put(session.getId(), activityId);
                activitySessionMap.computeIfAbsent(activityId, k -> ConcurrentHashMap.newKeySet())
                        .add(session.getId());
            } catch (NumberFormatException e) {
                log.warn("无效的 activityId: {}", activityIdStr);
            }
        }

        // 没有场次的连接 = 大屏/监控端，按全量监听处理，否则收不到任何场次广播
        if (activityId == null) {
            unscopedSessions.add(session.getId());
        }

        // 所有连接都要先写一次心跳记录。
        // 大屏连接没有 participantId，原先只在下面的 if 分支里写心跳，
        // 导致大屏永远没有心跳记录，30 秒后的清理任务会把它当僵尸连接踢掉
        // （现场表现：大屏每分钟断线重连一次，期间收不到任何弹幕和抢奖广播）。
        updateHeartbeat(session.getId());

        // 用户身份只认握手拦截器写进 attributes 的值，不再信任 query 里的 participantId
        Object pidAttr = session.getAttributes().get("participantId");
        if (pidAttr instanceof Long participantId) {
            sessionUserMap.put(session.getId(), participantId);
            userSessionMap.computeIfAbsent(participantId, k -> ConcurrentHashMap.newKeySet())
                    .add(session.getId());

            redisUtil.setAdd(ONLINE_USERS_KEY, participantId.toString());
            localOnlineCount.incrementAndGet();

            if (activityId != null) {
                String activityCountKey = ACTIVITY_ONLINE_COUNT_KEY + activityId;
                redisUtil.increment(activityCountKey);
                redisUtil.expire(activityCountKey, 24, TimeUnit.HOURS);
            }
        }

        log.info("WebSocket 连接建立: session={}, activityId={}, 本机在线={}",
                session.getId(), activityId, localOnlineCount.get());
    }

    // ==================== 连接关闭 ====================

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        doClose(session.getId());
        log.info("WebSocket 连接关闭: session={}, 本机在线={}", session.getId(), localOnlineCount.get());
    }

    private void doClose(String sessionId) {
        Long participantId = sessionUserMap.remove(sessionId);
        sessionMap.remove(sessionId);
        unscopedSessions.remove(sessionId);

        Long activityId = sessionActivityMap.remove(sessionId);
        if (activityId != null) {
            Set<String> activitySessions = activitySessionMap.get(activityId);
            if (activitySessions != null) {
                activitySessions.remove(sessionId);
                if (activitySessions.isEmpty()) {
                    activitySessionMap.remove(activityId);
                }
            }
            String activityCountKey = ACTIVITY_ONLINE_COUNT_KEY + activityId;
            Object count = redisUtil.get(activityCountKey);
            if (count != null) {
                redisUtil.decrement(activityCountKey);
            }
        }

        redisUtil.delete(ONLINE_HEARTBEAT_KEY + sessionId);

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
    }

    // ==================== 消息处理 ====================

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) {
        String payload = message.getPayload();

        if ("ping".equals(payload)) {
            try {
                updateHeartbeat(session.getId());
                session.sendMessage(new TextMessage("pong"));
            } catch (IOException e) {
                log.error("发送心跳响应失败: {}", session.getId(), e);
            }
            return;
        }

        log.debug("收到消息: session={}, payload={}", session.getId(), payload);
    }

    // ==================== 对外广播（走集群） ====================

    /**
     * 向指定场次广播（集群范围）
     */
    public void broadcastToActivity(Long activityId, String type, Object data) {
        if (activityId == null) {
            broadcast(type, data);
            return;
        }
        clusterPublisher.publishToActivity(activityId, type, data);
    }

    /**
     * 弹幕推送（集群范围）。
     *
     * 手机端不接收弹幕推送：2000 人同时在线时，每条弹幕广播给全部手机连接是
     * 「条数 × 在线人数」的流量放大（峰值可达百 Mbps 级公网出带宽），而手机端
     * 小屏每秒能读的弹幕有限，全量推送是纯浪费。弹幕只推大屏（unscoped 连接）；
     * 手机端保留发送入口，自己的发言由前端本地回显，发送反馈不受影响。
     *
     * activityId 为 null（理论不应发生）也统一走这里，由本地侧按「只推大屏」处理，
     * 不能落到 broadcast（那会推给所有手机连接）。
     */
    public void batchBroadcastDanmaku(Long activityId, String type, Object data) {
        clusterPublisher.publishDanmaku(activityId, type, data);
    }

    /**
     * 全局广播（集群范围）
     */
    public void broadcast(String type, Object data) {
        clusterPublisher.publishToAll(type, data);
    }

    /**
     * 向指定用户推送（集群范围，用户可能连在别的实例上）
     */
    public void sendToUser(Long participantId, String type, Object data) {
        clusterPublisher.publishToUser(participantId, type, data);
    }

    // ==================== 本地推送（仅由订阅端调用） ====================

    /**
     * 只推本机持有的该场次连接
     */
    public void localBroadcastToActivity(Long activityId, String type, Object data) {
        if (activityId == null) {
            localBroadcast(type, data);
            return;
        }

        Set<String> sessionIds = activitySessionMap.get(activityId);
        boolean hasScoped = sessionIds != null && !sessionIds.isEmpty();
        // 大屏这类无场次连接也要收，否则大屏看不到弹幕和抢奖过程
        if (!hasScoped && unscopedSessions.isEmpty()) {
            return;
        }

        TextMessage textMessage = buildMessage(type, data);

        if (hasScoped) {
            for (String sessionId : sessionIds) {
                pushOne(sessionId, textMessage, "场次推送失败: activityId=" + activityId);
            }
        }
        for (String sessionId : unscopedSessions) {
            pushOne(sessionId, textMessage, "大屏推送失败");
        }
    }

    /** 向单个连接异步推送；session 上的 synchronized 是因为 Tomcat 的 WS 会话不支持并发发送 */
    private void pushOne(String sessionId, TextMessage textMessage, String errorPrefix) {
        WebSocketSession session = sessionMap.get(sessionId);
        if (session == null || !session.isOpen()) {
            return;
        }
        CompletableFuture.runAsync(() -> {
            synchronized (session) {
                try {
                    session.sendMessage(textMessage);
                } catch (IOException | IllegalStateException e) {
                    log.error("{}, session={}", errorPrefix, sessionId, e);
                }
            }
        }, broadcastExecutor);
    }

    /** 只推本机的大屏连接（无 participantId、无场次的 unscoped 连接）。弹幕的唯一接收方。 */
    private void pushToUnscoped(TextMessage textMessage, String errorPrefix) {
        for (String sessionId : unscopedSessions) {
            pushOne(sessionId, textMessage, errorPrefix);
        }
    }

    /**
     * 只推本机持有的该用户连接
     */
    public void localSendToUser(Long participantId, String type, Object data) {
        Set<String> sids = userSessionMap.get(participantId);
        if (sids == null || sids.isEmpty()) {
            return;
        }

        TextMessage textMessage = buildMessage(type, data);
        for (String sid : sids) {
            WebSocketSession session = sessionMap.get(sid);
            if (session != null && session.isOpen()) {
                CompletableFuture.runAsync(() -> {
                    synchronized (session) {
                        try {
                            session.sendMessage(textMessage);
                        } catch (IOException | IllegalStateException e) {
                            log.error("用户定向推送失败: sid={}", sid, e);
                        }
                    }
                }, broadcastExecutor);
            }
        }
    }

    /**
     * 只推本机所有连接
     */
    public void localBroadcast(String type, Object data) {
        TextMessage textMessage = buildMessage(type, data);
        for (WebSocketSession session : sessionMap.values()) {
            if (session.isOpen()) {
                CompletableFuture.runAsync(() -> {
                    synchronized (session) {
                        try {
                            session.sendMessage(textMessage);
                        } catch (IOException | IllegalStateException e) {
                            log.error("全局广播发送失败: {}", session.getId(), e);
                        }
                    }
                }, broadcastExecutor);
            }
        }
    }

    /**
     * 弹幕加入本机批量缓冲。
     *
     * 只服务大屏：缓冲里的内容刷新时只推 unscoped 连接，
     * 手机连接（activitySessionMap）不再参与，否则就是全量广播。
     */
    public void localBatchBroadcastDanmaku(Long activityId, String type, Object data) {
        if (activityId == null) {
            // 无场次弹幕同样只推大屏。不能走 localBroadcastToActivity(null) →
            // localBroadcast，那会把弹幕推给本机所有连接（包括手机端）。
            pushToUnscoped(buildMessage(type, data), "大屏弹幕推送失败");
            return;
        }

        JSONObject msg = new JSONObject();
        msg.put("type", type);
        msg.put("data", data);

        List<String> buffer = batchBuffer.computeIfAbsent(activityId,
                k -> Collections.synchronizedList(new ArrayList<>()));
        buffer.add(msg.toJSONString());

        if (buffer.size() >= BATCH_THRESHOLD) {
            flushBatchBuffer(activityId);
        }
    }

    // ==================== 批量缓冲刷新 ====================

    private void flushBatchBuffer(Long activityId) {
        List<String> buffer = batchBuffer.get(activityId);
        if (buffer == null || buffer.isEmpty()) {
            return;
        }

        List<String> messages;
        synchronized (buffer) {
            if (buffer.isEmpty()) {
                return;
            }
            messages = new ArrayList<>(buffer);
            buffer.clear();
        }

        // 弹幕只推大屏（unscoped 连接）。原实现把 batch 推给 activitySessionMap
        // （全部手机连接）是流量放大的根源；大屏没有场次，只按场次推会一条都收不到。
        // 当前没有大屏在线时缓冲已清空，直接丢弃，不堆积、不延迟。
        if (unscopedSessions.isEmpty()) {
            return;
        }

        JSONObject batchMsg = new JSONObject();
        batchMsg.put("type", "danmaku_batch");
        batchMsg.put("data", messages);
        TextMessage textMessage = new TextMessage(batchMsg.toJSONString());

        for (String sessionId : unscopedSessions) {
            pushOne(sessionId, textMessage, "大屏弹幕推送失败");
        }
    }

    private void flushAllBatchBuffers() {
        for (Long activityId : batchBuffer.keySet()) {
            flushBatchBuffer(activityId);
        }
    }

    // ==================== 在线人数（降频推送） ====================

    /**
     * 每 2 秒检查一次全局在线人数，变化了才广播
     *
     * 全局人数从 Redis Set 的基数取，多实例部署时也是准确的。
     */
    @Scheduled(fixedRate = 2000)
    public void broadcastOnlineCountIfChanged() {
        int current;
        try {
            Long size = redisUtil.setSize(ONLINE_USERS_KEY);
            current = size == null ? 0 : size.intValue();
        } catch (Exception e) {
            log.warn("读取全局在线人数失败: {}", e.getMessage());
            return;
        }

        redisUtil.set(ONLINE_COUNT_KEY, current);

        if (current != lastBroadcastCount.get()) {
            lastBroadcastCount.set(current);
            broadcast("online_update", current);
        }
    }

    // ==================== 心跳与僵尸连接清理 ====================

    /**
     * 心跳过期时间。
     * 前端每 25 秒发一次 ping，这里给 90 秒的宽限，
     * 能容忍一次网络抖动或一次 GC 停顿而不至于把正常连接误杀。
     */
    private static final long HEARTBEAT_TTL_SECONDS = 90;

    /** 判定僵尸连接的超时时间，必须小于心跳 TTL，否则 key 先过期就永远判不出来 */
    private static final long HEARTBEAT_TIMEOUT_MS = 90_000;

    private void updateHeartbeat(String sessionId) {
        redisUtil.set(ONLINE_HEARTBEAT_KEY + sessionId, System.currentTimeMillis(),
                HEARTBEAT_TTL_SECONDS, TimeUnit.SECONDS);
    }

    @Scheduled(fixedRate = 30000)
    public void cleanInvalidConnections() {
        long now = System.currentTimeMillis();
        List<String> toRemove = new ArrayList<>();

        for (Map.Entry<String, WebSocketSession> entry : sessionMap.entrySet()) {
            String sessionId = entry.getKey();
            WebSocketSession session = entry.getValue();

            if (!session.isOpen()) {
                toRemove.add(sessionId);
                continue;
            }

            Object heartbeat = redisUtil.get(ONLINE_HEARTBEAT_KEY + sessionId);
            if (heartbeat == null) {
                toRemove.add(sessionId);
                log.warn("心跳记录缺失，关闭连接: sessionId={}", sessionId);
                continue;
            }

            // 单个坏值不能让整轮清理中断，否则所有僵尸连接会一直留着
            long last;
            try {
                last = Long.parseLong(heartbeat.toString());
            } catch (NumberFormatException e) {
                toRemove.add(sessionId);
                log.warn("心跳值格式异常，关闭连接: sessionId={}, value={}", sessionId, heartbeat);
                continue;
            }

            if (now - last > HEARTBEAT_TIMEOUT_MS) {
                toRemove.add(sessionId);
                log.warn("心跳超时，关闭连接: sessionId={}, 距上次={}ms", sessionId, now - last);
            }
        }

        for (String sessionId : toRemove) {
            WebSocketSession session = sessionMap.get(sessionId);
            if (session != null) {
                try {
                    session.close(CloseStatus.SESSION_NOT_RELIABLE);
                } catch (IOException e) {
                    log.error("关闭僵尸连接失败: {}", sessionId, e);
                }
            }
            doClose(sessionId);
        }

        if (!toRemove.isEmpty()) {
            log.info("定时清理僵尸连接: {} 个", toRemove.size());
        }
    }

    // ==================== 查询 ====================

    public int getOnlineCount() {
        Long size = redisUtil.setSize(ONLINE_USERS_KEY);
        return size == null ? 0 : size.intValue();
    }

    public int getOnlineParticipantCount() {
        return localOnlineCount.get();
    }

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
     * 强制断开指定用户的所有连接（集群范围）
     */
    public void disconnectUser(Long participantId) {
        sendToUser(participantId, "force_disconnect", "您已被移出直播间");
    }

    /**
     * 本机断开指定用户（由订阅端收到 force_disconnect 后调用）
     */
    public void localDisconnectUser(Long participantId) {
        Set<String> sids = userSessionMap.get(participantId);
        if (sids == null) {
            return;
        }
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

    // ==================== 工具方法 ====================

    private TextMessage buildMessage(String type, Object data) {
        JSONObject msg = new JSONObject();
        msg.put("type", type);
        msg.put("data", data);
        return new TextMessage(msg.toJSONString());
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

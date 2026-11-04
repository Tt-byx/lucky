package com.lucky.websocket;

import lombok.Data;

/**
 * 集群广播消息
 *
 * WebSocket 连接分散在各个实例的 JVM 内存里，单机广播只能在本地生效。
 * 所以所有广播先发到 Redis 频道，每个实例都订阅这个频道，收到后只推自己持有的连接，
 * 这样无论用户连在哪台机器上都能收到消息。
 */
@Data
public class WsBroadcastMessage {

    /** 推给某个活动场次的所有连接 */
    public static final String SCOPE_ACTIVITY = "activity";
    /** 推给某个用户的所有连接（多端登录） */
    public static final String SCOPE_USER = "user";
    /** 推给所有连接（系统级通知） */
    public static final String SCOPE_ALL = "all";
    /** 弹幕：走批量缓冲后推送 */
    public static final String SCOPE_DANMAKU = "danmaku";

    private String scope;
    private Long activityId;
    private Long participantId;
    private String type;
    private Object data;
}

package com.lucky.constant;

/**
 * Redis Key 统一管理
 *
 * 集中定义避免前缀散落在各个 Service 里，也方便按活动/轮次做批量清理。
 */
public final class RedisKeys {

    private RedisKeys() {
    }

    /** 本轮剩余名额（String，值是整数） */
    public static String roundStock(Long roundId) {
        return "lucky:round:stock:" + roundId;
    }

    /** 本轮已抢到的人（Set） */
    public static String roundUsers(Long roundId) {
        return "lucky:round:users:" + roundId;
    }

    /** 本活动已中奖的人（Set，跨轮次去重） */
    public static String activityWinners(Long activityId) {
        return "lucky:activity:winners:" + activityId;
    }

    /** 轮次元信息（Hash：activityId / prizeId / status / quota / roundName） */
    public static String roundMeta(Long roundId) {
        return "lucky:round:meta:" + roundId;
    }

    /** 轮次结束操作的互斥标记 */
    public static String roundFinishLock(Long roundId) {
        return "lucky:round:finish:lock:" + roundId;
    }

    /** 参与者抽奖资格缓存（String，1 可参与 / 0 不可参与） */
    public static String participantQualification(Long participantId) {
        return "lucky:participant:qual:" + participantId;
    }

    /** 参与者禁言/移出状态缓存（String，normal / muted / banned） */
    public static String participantStatus(Long participantId) {
        return "lucky:participant:status:" + participantId;
    }

    /**
     * 参与者对象缓存（Jackson 序列化的 Participant）
     *
     * 弹幕链路每条消息都要拿发送者姓名，原先每次都 selectById。
     * 姓名是几乎不变的静态数据，缓存住可以把这条高频路径的 DB 查询降到 0。
     * 禁言/踢人时必须删除该 key，否则状态变更要等缓存过期才生效。
     */
    public static String participantObject(Long participantId) {
        return "lucky:participant:obj:" + participantId;
    }

    /** MQ 消费幂等标记 */
    public static String mqDedup(String messageId) {
        return "lucky:mq:dedup:" + messageId;
    }

    /**
     * 抢奖点击频率限制（String 计数器，1 秒窗口，自动过期）
     *
     * 放在 Lua 脚本里做，零额外网络往返：
     * 抢奖按钮被脚本连点时，靠它把请求挡在扣减之前，避免白白消耗 Redis 和 MQ。
     */
    public static String joinRate(Long participantId) {
        return "lucky:join:rate:" + participantId;
    }
}

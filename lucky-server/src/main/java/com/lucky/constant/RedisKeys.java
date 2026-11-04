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

    /** MQ 消费幂等标记 */
    public static String mqDedup(String messageId) {
        return "lucky:mq:dedup:" + messageId;
    }
}

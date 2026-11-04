package com.lucky.annotation;

import java.lang.annotation.*;

/**
 * Redis+Lua 分布式限流注解
 * 基于固定窗口计数器算法，原子化执行杜绝并发超发
 * 应用于 Controller 方法上，限制单 IP / 单用户在时间窗口内的调用次数
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface RateLimit {

    /**
     * 限流 key 前缀，用于区分不同业务场景
     * 如：danmaku_send、login、lottery_draw
     */
    String prefix();

    /**
     * 时间窗口内最大请求次数
     */
    int maxCount() default 10;

    /**
     * 时间窗口（秒）
     */
    int windowSeconds() default 60;

    /**
     * 限流维度：
     * IP - 按客户端 IP 限流
     * USER - 按用户标识限流（需从请求参数或 header 中提取）
     * CUSTOM - 自定义 key（通过 SpEL 表达式指定）
     */
    LimitType type() default LimitType.IP;

    /**
     * 当 type = CUSTOM 时，用于提取 key 的 SpEL 表达式
     * 例如：#dto.participantId 表示取方法参数 dto 的 participantId 字段
     */
    String keyExpression() default "";

    /**
     * 触发限流时的提示消息
     */
    String message() default "请求过于频繁，请稍后再试";

    enum LimitType {
        IP,
        USER,
        CUSTOM
    }
}

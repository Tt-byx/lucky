package com.lucky.annotation;

import java.lang.annotation.*;

/**
 * 接口幂等性注解
 * 用于防止重复提交
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface Idempotent {

    /**
     * 幂等 Key 的前缀
     */
    String prefix() default "";

    /**
     * 幂等 Key 的过期时间（秒）
     */
    int expireSeconds() default 5;

    /**
     * 错误提示信息
     */
    String message() default "请勿重复提交";
}

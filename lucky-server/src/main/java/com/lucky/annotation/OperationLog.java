package com.lucky.annotation;

import java.lang.annotation.*;

/**
 * 操作日志注解
 * 用于自动记录操作日志
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface OperationLog {

    /**
     * 操作类型
     */
    String operationType();

    /**
     * 目标类型
     */
    String targetType() default "";

    /**
     * 操作描述（支持 SpEL 表达式）
     */
    String detail() default "";
}

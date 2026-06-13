package com.lucky.aspect;

import com.lucky.annotation.Idempotent;
import com.lucky.exception.BusinessException;
import com.lucky.util.RedisUtil;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.lang.reflect.Method;
import java.util.concurrent.TimeUnit;

/**
 * 接口幂等性切面
 */
@Slf4j
@Aspect
@Component
@RequiredArgsConstructor
public class IdempotentAspect {

    private final RedisUtil redisUtil;

    @Around("@annotation(com.lucky.annotation.Idempotent)")
    public Object around(ProceedingJoinPoint joinPoint) throws Throwable {
        // 获取方法信息
        MethodSignature signature = (MethodSignature) joinPoint.getSignature();
        Method method = signature.getMethod();
        Idempotent annotation = method.getAnnotation(Idempotent.class);

        // 生成幂等 Key
        String idempotentKey = generateIdempotentKey(annotation, method, joinPoint.getArgs());

        // 检查是否重复提交
        if (redisUtil.hasKey(idempotentKey)) {
            log.warn("重复提交: key={}", idempotentKey);
            throw new BusinessException(annotation.message());
        }

        // 设置幂等标记
        redisUtil.set(idempotentKey, "1", annotation.expireSeconds(), TimeUnit.SECONDS);

        try {
            // 执行目标方法
            return joinPoint.proceed();
        } catch (Exception e) {
            // 执行失败，删除幂等标记（允许重试）
            redisUtil.delete(idempotentKey);
            throw e;
        }
    }

    /**
     * 生成幂等 Key
     */
    private String generateIdempotentKey(Idempotent annotation, Method method, Object[] args) {
        StringBuilder key = new StringBuilder("idempotent:");

        // 添加前缀
        if (!annotation.prefix().isEmpty()) {
            key.append(annotation.prefix()).append(":");
        } else {
            key.append(method.getDeclaringClass().getSimpleName())
               .append(":")
               .append(method.getName())
               .append(":");
        }

        // 添加请求参数
        for (Object arg : args) {
            if (arg != null) {
                key.append(arg.toString()).append(":");
            }
        }

        // 添加请求信息（IP + Session）
        ServletRequestAttributes attributes = (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
        if (attributes != null) {
            HttpServletRequest request = attributes.getRequest();
            String ip = getClientIp(request);
            key.append(ip);
        }

        return key.toString();
    }

    /**
     * 获取客户端IP
     */
    private String getClientIp(HttpServletRequest request) {
        String ip = request.getHeader("X-Forwarded-For");
        if (ip == null || ip.isEmpty() || "unknown".equalsIgnoreCase(ip)) {
            ip = request.getHeader("Proxy-Client-IP");
        }
        if (ip == null || ip.isEmpty() || "unknown".equalsIgnoreCase(ip)) {
            ip = request.getHeader("WL-Proxy-Client-IP");
        }
        if (ip == null || ip.isEmpty() || "unknown".equalsIgnoreCase(ip)) {
            ip = request.getRemoteAddr();
        }
        if (ip != null && ip.contains(",")) {
            ip = ip.split(",")[0].trim();
        }
        return ip;
    }
}

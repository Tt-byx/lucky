package com.lucky.aspect;

import com.lucky.annotation.RateLimit;
import com.lucky.exception.BusinessException;
import jakarta.annotation.PostConstruct;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.scripting.support.ResourceScriptSource;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.lang.reflect.Field;
import java.util.Collections;
import java.util.List;

/**
 * Redis+Lua 分布式限流切面
 * 基于固定窗口计数器算法，Lua 脚本原子化执行
 * 支持按 IP / 用户 / 自定义 SpEL key 三种限流维度
 */
@Slf4j
@Aspect
@Component
@RequiredArgsConstructor
public class RateLimitAspect {

    private final StringRedisTemplate stringRedisTemplate;

    private DefaultRedisScript<Long> rateLimitScript;

    @PostConstruct
    public void init() {
        rateLimitScript = new DefaultRedisScript<>();
        rateLimitScript.setScriptSource(new ResourceScriptSource(new ClassPathResource("lua/rate_limit.lua")));
        rateLimitScript.setResultType(Long.class);
        log.info("Redis+Lua 限流脚本加载完成");
    }

    @Around("@annotation(rateLimit)")
    public Object around(ProceedingJoinPoint joinPoint, RateLimit rateLimit) throws Throwable {
        // 构建限流 key
        String limitKey = buildLimitKey(joinPoint, rateLimit);

        // 执行 Lua 限流脚本（原子操作）
        List<String> keys = Collections.singletonList(limitKey);
        Long result = stringRedisTemplate.execute(
                rateLimitScript,
                keys,
                String.valueOf(rateLimit.maxCount()),
                String.valueOf(rateLimit.windowSeconds())
        );

        if (result == null || result == 0) {
            log.warn("请求被限流: key={}, maxCount={}/{}s", limitKey, rateLimit.maxCount(), rateLimit.windowSeconds());
            throw new BusinessException(429, rateLimit.message());
        }

        return joinPoint.proceed();
    }

    /**
     * 构建限流 key
     */
    private String buildLimitKey(ProceedingJoinPoint joinPoint, RateLimit rateLimit) {
        String baseKey = "rate_limit:" + rateLimit.prefix();

        switch (rateLimit.type()) {
            case IP:
                return baseKey + ":ip:" + getClientIp();
            case USER:
                String userId = extractUserId(joinPoint);
                return baseKey + ":user:" + userId;
            case CUSTOM:
                String customKey = evaluateSpEL(joinPoint, rateLimit.keyExpression());
                return baseKey + ":" + customKey;
            default:
                return baseKey + ":ip:" + getClientIp();
        }
    }

    /**
     * 获取客户端真实 IP（处理反向代理场景）
     *
     * 取值顺序很关键，不能先用 X-Forwarded-For 的第一个：
     * nginx 用的是 `$proxy_add_x_forwarded_for`，它会「追加」而不是覆盖，
     * 所以攻击者只要在请求头里自带 `X-Forwarded-For: 1.2.3.4`，
     * 取第一个就会拿到伪造值，每换一个值就是一个全新的限流桶，限流直接失效。
     *
     * 正确顺序：
     *   1. X-Real-IP           —— nginx 用 $remote_addr 直接设置，客户端改不了
     *   2. X-Forwarded-For 末尾 —— 追加段的最后一个是最近一跳看到的真实来源
     *   3. getRemoteAddr       —— 没有代理时的兜底
     */
    private String getClientIp() {
        try {
            ServletRequestAttributes attributes =
                    (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
            if (attributes == null) return "unknown";

            HttpServletRequest request = attributes.getRequest();

            String realIp = request.getHeader("X-Real-IP");
            if (isValidIp(realIp)) {
                return realIp.trim();
            }

            String forwarded = request.getHeader("X-Forwarded-For");
            if (isValidIp(forwarded)) {
                String[] parts = forwarded.split(",");
                for (int i = parts.length - 1; i >= 0; i--) {
                    if (isValidIp(parts[i])) {
                        return parts[i].trim();
                    }
                }
            }

            return request.getRemoteAddr();
        } catch (Exception e) {
            return "unknown";
        }
    }

    private boolean isValidIp(String ip) {
        return ip != null && !ip.isEmpty() && !"unknown".equalsIgnoreCase(ip.trim());
    }

    /**
     * 从方法参数中提取用户 ID（查找名为 participantId / userId / id 的字段）
     */
    private String extractUserId(ProceedingJoinPoint joinPoint) {
        Object[] args = joinPoint.getArgs();
        for (Object arg : args) {
            if (arg == null) continue;
            // 直接是 Long 类型
            if (arg instanceof Long) return arg.toString();
            // 从 DTO 中反射提取
            try {
                Field field = findField(arg.getClass(), "participantId");
                if (field == null) field = findField(arg.getClass(), "userId");
                if (field == null) field = findField(arg.getClass(), "id");
                if (field != null) {
                    field.setAccessible(true);
                    Object value = field.get(arg);
                    if (value != null) return value.toString();
                }
            } catch (Exception ignored) {
            }
        }
        return "anonymous";
    }

    private Field findField(Class<?> clazz, String fieldName) {
        while (clazz != null && clazz != Object.class) {
            try {
                return clazz.getDeclaredField(fieldName);
            } catch (NoSuchFieldException e) {
                clazz = clazz.getSuperclass();
            }
        }
        return null;
    }

    /**
     * 简易 SpEL 表达式求值（支持 #param.field 格式）
     */
    private String evaluateSpEL(ProceedingJoinPoint joinPoint, String expression) {
        if (expression == null || expression.isEmpty()) return "default";

        try {
            MethodSignature signature = (MethodSignature) joinPoint.getSignature();
            String[] paramNames = signature.getParameterNames();
            Object[] args = joinPoint.getArgs();

            // 解析 #paramName.field 格式
            if (expression.startsWith("#")) {
                String expr = expression.substring(1);
                String[] parts = expr.split("\\.", 2);
                String paramName = parts[0];

                for (int i = 0; i < paramNames.length; i++) {
                    if (paramNames[i].equals(paramName) && args[i] != null) {
                        if (parts.length == 1) {
                            return args[i].toString();
                        }
                        // 反射取字段
                        Field field = findField(args[i].getClass(), parts[1]);
                        if (field != null) {
                            field.setAccessible(true);
                            Object value = field.get(args[i]);
                            return value != null ? value.toString() : "null";
                        }
                    }
                }
            }
        } catch (Exception e) {
            log.warn("SpEL 表达式解析失败: {}", expression, e);
        }
        return "default";
    }
}

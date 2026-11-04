package com.lucky.security;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.DigestUtils;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

/**
 * JWT 黑名单
 *
 * JWT 是无状态的，签发后服务端无法主动作废。
 * 管理员在后台点「退出登录」时，除了前端清 token，还要把 token 拉黑，
 * 否则这个 token 在过期前仍然有效——尤其是后台令牌 24 小时有效期，风险不小。
 *
 * 实现上用 token 的 MD5 做 key，TTL 设为 token 的剩余有效期，
 * 过期后 key 自动清理，不会无限膨胀。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class JwtBlacklistService {

    private static final String KEY_PREFIX = "lucky:jwt:blacklist:";

    private final StringRedisTemplate stringRedisTemplate;

    public void revoke(String token, long remainingSeconds) {
        if (token == null || token.isBlank()) {
            return;
        }
        long ttl = remainingSeconds > 0 ? remainingSeconds : 60;
        stringRedisTemplate.opsForValue()
                .set(KEY_PREFIX + fingerprint(token), "1", ttl, TimeUnit.SECONDS);
        log.info("JWT 已加入黑名单，剩余有效期 {} 秒", ttl);
    }

    public boolean isRevoked(String token) {
        if (token == null || token.isBlank()) {
            return false;
        }
        return Boolean.TRUE.equals(stringRedisTemplate.hasKey(KEY_PREFIX + fingerprint(token)));
    }

    private String fingerprint(String token) {
        return DigestUtils.md5DigestAsHex(token.getBytes(StandardCharsets.UTF_8));
    }
}

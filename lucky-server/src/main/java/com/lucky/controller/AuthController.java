package com.lucky.controller;

import com.lucky.annotation.RateLimit;
import com.lucky.constant.RedisKeys;
import com.lucky.dto.LoginDTO;
import com.lucky.dto.LoginVO;
import com.lucky.dto.Result;
import com.lucky.security.AdminUserDetails;
import com.lucky.security.JwtBlacklistService;
import com.lucky.util.JwtUtil;
import com.lucky.util.RedisUtil;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

import java.util.concurrent.TimeUnit;

/**
 * 认证控制器
 *
 * 登录接口有两层防护（防暴力破解）：
 * 1. @RateLimit 按 IP 限流：单 IP 10 次 / 5 分钟
 * 2. 账号级锁定：同一用户名连续失败 5 次，锁定 15 分钟
 */
@Slf4j
@Tag(name = "认证管理", description = "用户登录认证")
@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    /** 同一用户名连续失败多少次后锁定 */
    private static final int MAX_LOGIN_FAIL = 5;

    /** 锁定持续时间（分钟） */
    private static final int LOCK_MINUTES = 15;

    private final AuthenticationManager authenticationManager;
    private final JwtUtil jwtUtil;
    private final JwtBlacklistService jwtBlacklistService;
    private final RedisUtil redisUtil;

    @Operation(summary = "管理员登录", description = "管理员使用用户名和密码登录，获取JWT Token")
    @RateLimit(
            prefix = "admin_login",
            maxCount = 10,
            windowSeconds = 300,
            type = RateLimit.LimitType.IP,
            message = "登录尝试过于频繁，请 5 分钟后再试"
    )
    @PostMapping("/login")
    public Result<LoginVO> login(@Valid @RequestBody LoginDTO loginDTO) {
        String username = loginDTO.getUsername();

        // 第二层防护：账号级锁定（防定向爆破单个账号）
        // Redis 不可用时跳过锁定检查（fail-open），不能因为限流组件故障挡住正常登录
        String failKey = RedisKeys.loginFailCount(username);
        try {
            Object failCountObj = redisUtil.get(failKey);
            long failCount = failCountObj == null ? 0 : Long.parseLong(failCountObj.toString());
            if (failCount >= MAX_LOGIN_FAIL) {
                log.warn("账号被锁定（连续失败 {} 次）: {}", failCount, username);
                return Result.error("账号已被锁定，请 " + LOCK_MINUTES + " 分钟后再试");
            }
        } catch (Exception e) {
            log.warn("登录锁定检查 Redis 不可用，fail-open: {}", e.getMessage());
        }

        try {
            // 认证
            Authentication authentication = authenticationManager.authenticate(
                    new UsernamePasswordAuthenticationToken(
                            loginDTO.getUsername(),
                            loginDTO.getPassword()
                    )
            );

            // 设置到SecurityContext
            SecurityContextHolder.getContext().setAuthentication(authentication);

            // 获取用户信息
            AdminUserDetails userDetails = (AdminUserDetails) authentication.getPrincipal();

            // 生成JWT Token
            String token = jwtUtil.generateToken(
                    userDetails.getUsername(),
                    userDetails.getRole()
            );

            // 登录成功，清除失败计数（Redis 挂了跳过，不影响登录）
            try {
                redisUtil.delete(failKey);
            } catch (Exception ignored) {
            }

            // 返回登录信息
            LoginVO loginVO = LoginVO.builder()
                    .token(token)
                    .username(userDetails.getUsername())
                    .role(userDetails.getRole())
                    .build();

            return Result.ok(loginVO);
        } catch (Exception e) {
            // 记录失败次数，达到阈值后锁定（Redis 挂了跳过计数）
            try {
                Long newCount = redisUtil.increment(failKey);
                if (newCount != null && newCount == 1) {
                    redisUtil.expire(failKey, LOCK_MINUTES, TimeUnit.MINUTES);
                }
                if (newCount != null && newCount >= MAX_LOGIN_FAIL) {
                    log.warn("账号连续失败 {} 次，已锁定 {} 分钟: {}", newCount, LOCK_MINUTES, username);
                    return Result.error("连续登录失败次数过多，账号已被锁定 " + LOCK_MINUTES + " 分钟");
                }
            } catch (Exception redisEx) {
                log.warn("登录失败计数 Redis 不可用: {}", redisEx.getMessage());
            }
            log.warn("登录失败: {}", username);
            return Result.error("用户名或密码错误");
        }
    }

    @Operation(summary = "退出登录", description = "把当前 JWT 加入黑名单，立即失效")
    @PostMapping("/logout")
    public Result<Void> logout(@RequestHeader(value = "Authorization", required = false) String authorization) {
        if (authorization == null || authorization.isBlank()) {
            return Result.ok();
        }
        String token = authorization.startsWith("Bearer ")
                ? authorization.substring(7)
                : authorization;
        jwtBlacklistService.revoke(token, jwtUtil.getRemainingSeconds(token));
        SecurityContextHolder.clearContext();
        return Result.ok();
    }

    @Operation(summary = "获取当前用户信息", description = "获取当前已认证用户的信息")
    @GetMapping("/me")
    public Result<LoginVO> getCurrentUser() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.getPrincipal() instanceof AdminUserDetails) {
            AdminUserDetails userDetails = (AdminUserDetails) authentication.getPrincipal();
            LoginVO loginVO = LoginVO.builder()
                    .username(userDetails.getUsername())
                    .role(userDetails.getRole())
                    .build();
            return Result.ok(loginVO);
        }
        return Result.error("未登录");
    }
}

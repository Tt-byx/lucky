package com.lucky.controller;

import com.lucky.dto.LoginDTO;
import com.lucky.dto.LoginVO;
import com.lucky.dto.Result;
import com.lucky.security.AdminUserDetails;
import com.lucky.security.JwtBlacklistService;
import com.lucky.util.JwtUtil;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

/**
 * 认证控制器
 */
@Tag(name = "认证管理", description = "用户登录认证")
@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthenticationManager authenticationManager;
    private final JwtUtil jwtUtil;
    private final JwtBlacklistService jwtBlacklistService;

    @Operation(summary = "管理员登录", description = "管理员使用用户名和密码登录，获取JWT Token")
    @PostMapping("/login")
    public Result<LoginVO> login(@Valid @RequestBody LoginDTO loginDTO) {
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

            // 返回登录信息
            LoginVO loginVO = LoginVO.builder()
                    .token(token)
                    .username(userDetails.getUsername())
                    .role(userDetails.getRole())
                    .build();

            return Result.ok(loginVO);
        } catch (Exception e) {
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

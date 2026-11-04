package com.lucky.controller;

import com.lucky.dto.Result;
import com.lucky.dto.ScanLoginDTO;
import com.lucky.dto.ScanLoginVO;
import com.lucky.service.ScanLoginService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

/**
 * 扫码登录控制器
 * 高并发接入优化：Redis 临时令牌 + 限流拦截 + 异步入库
 */
@Tag(name = "扫码登录", description = "用户扫码登录、令牌校验、登出")
@RestController
@RequestMapping("/api/scan")
@RequiredArgsConstructor
public class ScanLoginController {

    private final ScanLoginService scanLoginService;

    @Operation(summary = "扫码登录", description = "用户扫码后提交身份信息，获取场次专属临时令牌")
    @PostMapping("/login")
    public Result<ScanLoginVO> login(@Valid @RequestBody ScanLoginDTO dto) {
        return Result.ok(scanLoginService.login(dto));
    }

    @Operation(summary = "校验令牌", description = "校验临时令牌有效性，返回参与者ID")
    @GetMapping("/validate")
    public Result<Long> validate(@RequestHeader("Authorization") String token) {
        // 支持 Bearer 前缀
        if (token.startsWith("Bearer ")) {
            token = token.substring(7);
        }
        Long participantId = scanLoginService.validateToken(token);
        if (participantId == null) {
            return Result.error(401, "令牌无效或已过期");
        }
        return Result.ok(participantId);
    }

    @Operation(summary = "登出", description = "销毁临时令牌")
    @PostMapping("/logout")
    public Result<Void> logout(@RequestHeader("Authorization") String token) {
        if (token.startsWith("Bearer ")) {
            token = token.substring(7);
        }
        scanLoginService.logout(token);
        return Result.ok();
    }
}

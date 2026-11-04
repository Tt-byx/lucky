package com.lucky.controller;

import com.lucky.dto.DanmakuDTO;
import com.lucky.dto.Result;
import com.lucky.entity.Danmaku;
import com.lucky.service.DanmakuService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 弹幕接口
 *
 * 审核链路已下线：现场 2000 人发弹幕，人工过审不现实。
 * 当前策略是「全自动通过 + 敏感词拦截 + 事后封禁」，被拦截的弹幕仍会异步入库留档，
 * 管理员可以在用户管理里查看某人发过的弹幕，再决定禁言或移出直播间。
 */
@Tag(name = "弹幕", description = "发送弹幕、查询弹幕记录")
@RestController
@RequestMapping("/api/danmaku")
@RequiredArgsConstructor
public class DanmakuController {

    private final DanmakuService danmakuService;

    @Operation(summary = "发送弹幕", description = "命中敏感词返回 403，但仍会异步入库留档")
    @PostMapping("/send")
    public Result<Danmaku> send(@Valid @RequestBody DanmakuDTO dto) {
        Danmaku danmaku = danmakuService.sendDanmaku(dto);
        if (danmaku.getStatus() != null && danmaku.getStatus() == 2) {
            return Result.error(403, "弹幕包含敏感词，已被拒绝");
        }
        return Result.ok(danmaku);
    }

    @Operation(summary = "查询已通过的弹幕", description = "用于管理后台查看用户发言记录")
    @GetMapping("/approved")
    public Result<List<Danmaku>> approved(@RequestParam Long activityId) {
        return Result.ok(danmakuService.getApprovedDanmakus(activityId));
    }
}

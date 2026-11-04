package com.lucky.controller;

import com.lucky.dto.DanmakuDTO;
import com.lucky.dto.Result;
import com.lucky.entity.Danmaku;
import com.lucky.service.DanmakuService;
import com.lucky.service.ScanLoginService;
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
    private final ScanLoginService scanLoginService;

    @Operation(summary = "发送弹幕", description = "命中敏感词返回 403，但仍会异步入库留档")
    @PostMapping("/send")
    public Result<Danmaku> send(@Valid @RequestBody DanmakuDTO dto,
                                @RequestHeader(value = "Authorization", required = false) String authorization) {
        // 身份校验：只要声明了 participantId，就必须持有匹配的扫码令牌。
        // 否则任何人拿到别人的 participantId 就能冒充他发弹幕（改稿、刷屏、甩锅）。
        // participantId 为空时视为匿名弹幕，不校验。
        if (dto.getParticipantId() != null) {
            Long fromToken = scanLoginService.validateToken(trimBearer(authorization));
            if (fromToken == null) {
                return Result.error(401, "身份令牌缺失或已过期");
            }
            if (!fromToken.equals(dto.getParticipantId())) {
                return Result.error(403, "令牌与发送者身份不匹配");
            }
        }

        Danmaku danmaku = danmakuService.sendDanmaku(dto);
        if (danmaku.getStatus() != null && danmaku.getStatus() == 2) {
            return Result.error(403, "弹幕包含敏感词，已被拒绝");
        }
        return Result.ok(danmaku);
    }

    private static String trimBearer(String token) {
        if (token == null || token.isEmpty()) {
            return null;
        }
        return token.startsWith("Bearer ") ? token.substring(7) : token;
    }

    @Operation(summary = "查询已通过的弹幕", description = "用于管理后台查看用户发言记录，可按参与者过滤，只返回最近 200 条")
    @GetMapping("/approved")
    public Result<List<Danmaku>> approved(@RequestParam Long activityId,
                                          @RequestParam(required = false) Long participantId) {
        return Result.ok(danmakuService.getApprovedDanmakus(activityId, participantId));
    }

    /**
     * 统计每个参与者的弹幕数
     *
     * 管理端在参与者列表里显示「发了多少条」。原先前端对每个参与者
     * 都拉一次全量弹幕再 filter，2000 人就是 2000 次请求。
     * 这里一次 group by 给出全部结果：{participantId: count}。
     */
    @Operation(summary = "统计每个参与者的弹幕数", description = "返回 {participantId: count}")
    @GetMapping("/count-by-user")
    public Result<java.util.Map<Long, Long>> countByUser(@RequestParam Long activityId) {
        return Result.ok(danmakuService.countByParticipant(activityId));
    }
}

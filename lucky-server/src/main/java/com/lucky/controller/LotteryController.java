package com.lucky.controller;

import com.lucky.dto.JoinResultVO;
import com.lucky.dto.LotteryDTO;
import com.lucky.dto.Result;
import com.lucky.dto.WinnerVO;
import com.lucky.entity.LotteryRound;
import com.lucky.entity.Winner;
import com.lucky.service.LotteryService;
import com.lucky.service.ScanLoginService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 抽奖接口
 *
 * 角色划分：
 *   管理端 —— 创建轮次、开始抢奖、结束抢奖（JWT 保护）
 *   用户端 —— 抢奖、查结果（扫码临时令牌保护，用户没有 JWT）
 */
@Tag(name = "抽奖", description = "轮次管理、限时抢奖、中奖结果")
@RestController
@RequestMapping("/api/lottery")
@RequiredArgsConstructor
public class LotteryController {

    private final LotteryService lotteryService;
    private final ScanLoginService scanLoginService;

    // ==================== 管理端 ====================

    @Operation(summary = "创建抽奖轮次", description = "必须绑定奖品，名额不得超过奖品剩余库存")
    @PostMapping("/round")
    public Result<LotteryRound> createRound(@Valid @RequestBody LotteryDTO dto) {
        return Result.ok(lotteryService.createRound(dto));
    }

    @Operation(summary = "开始本轮抢奖", description = "预热库存并广播倒计时")
    @PostMapping("/round/{roundId}/start")
    public Result<Void> startRound(@PathVariable Long roundId) {
        lotteryService.startRound(roundId);
        return Result.ok();
    }

    @Operation(summary = "结束本轮抢奖")
    @PostMapping("/round/{roundId}/end")
    public Result<Void> endRound(@PathVariable Long roundId) {
        lotteryService.endRound(roundId);
        return Result.ok();
    }

    @Operation(summary = "查询活动的所有轮次")
    @GetMapping("/rounds")
    public Result<List<LotteryRound>> getRounds(@RequestParam Long activityId) {
        return Result.ok(lotteryService.getRoundsByActivity(activityId));
    }

    @Operation(summary = "查询本轮中奖名单")
    @GetMapping("/winners")
    public Result<List<WinnerVO>> getWinners(@RequestParam Long roundId) {
        return Result.ok(lotteryService.getWinnersByRound(roundId));
    }

    @Operation(summary = "查询本轮剩余名额")
    @GetMapping("/remaining")
    public Result<Integer> getRemaining(@RequestParam Long roundId) {
        return Result.ok(lotteryService.getRemaining(roundId));
    }

    // ==================== 用户端 ====================

    /**
     * 抢奖（秒杀入口）
     *
     * 必须携带扫码登录返回的临时令牌。这样即使有人拿到 participantId，
     * 也无法替别人抢奖——身份由 Redis 里的令牌反查，不信任入参。
     */
    @Operation(summary = "立即抢奖", description = "秒杀接口，需携带扫码临时令牌")
    @PostMapping("/join/{roundId}")
    public Result<JoinResultVO> join(@PathVariable Long roundId,
                                     @RequestHeader(value = "Authorization", required = false) String authorization) {
        Long participantId = resolveParticipant(authorization);
        if (participantId == null) {
            return Result.error(401, "登录已失效，请重新扫码进入");
        }
        return Result.ok(lotteryService.join(roundId, participantId));
    }

    @Operation(summary = "查询自己本轮是否中奖", description = "未中奖时 data 为 null")
    @GetMapping("/result")
    public Result<Winner> getResult(@RequestParam Long roundId,
                                    @RequestHeader(value = "Authorization", required = false) String authorization) {
        Long participantId = resolveParticipant(authorization);
        if (participantId == null) {
            return Result.error(401, "登录已失效，请重新扫码进入");
        }
        return Result.ok(lotteryService.getResult(roundId, participantId));
    }

    private Long resolveParticipant(String authorization) {
        if (authorization == null || authorization.isBlank()) {
            return null;
        }
        String token = authorization.startsWith("Bearer ")
                ? authorization.substring(7)
                : authorization;
        return scanLoginService.validateToken(token);
    }
}

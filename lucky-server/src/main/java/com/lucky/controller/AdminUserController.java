package com.lucky.controller;

import com.lucky.constant.RedisKeys;
import com.lucky.dto.Result;
import com.lucky.entity.Participant;
import com.lucky.service.ParticipantService;
import com.lucky.util.RedisUtil;
import com.lucky.websocket.LuckyWebSocketHandler;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/admin/user")
@RequiredArgsConstructor
public class AdminUserController {

    private final ParticipantService participantService;
    private final LuckyWebSocketHandler webSocketHandler;
    private final RedisUtil redisUtil;

    /**
     * 获取指定活动的所有参与者列表
     */
    @GetMapping("/list")
    public Result<List<Participant>> list(@RequestParam Long activityId) {
        return Result.ok(participantService.getByActivity(activityId));
    }

    /**
     * 获取当前在线用户列表
     */
    @GetMapping("/online")
    public Result<List<Participant>> onlineUsers(@RequestParam Long activityId) {
        Set<Long> onlineIds = webSocketHandler.getOnlineParticipantIds();
        if (onlineIds.isEmpty()) {
            return Result.ok(Collections.emptyList());
        }
        List<Participant> all = participantService.getByActivity(activityId);
        List<Participant> online = all.stream()
                .filter(p -> onlineIds.contains(p.getId()))
                .collect(Collectors.toList());
        return Result.ok(online);
    }

    /**
     * 禁言/取消禁言
     *
     * 关键：必须同时清掉 Redis 里的状态缓存，否则禁言要等缓存过期（最长 1 小时）才生效。
     * 这是初版的一个真实 bug：管理员点了禁言，用户还能继续发弹幕。
     */
    @PutMapping("/{id}/mute")
    public Result<Void> toggleMute(@PathVariable Long id, @RequestParam boolean muted) {
        Participant p = participantService.getById(id);
        if (p == null) {
            return Result.error("用户不存在");
        }

        p.setIsMuted(muted ? 1 : 0);
        participantService.updateById(p);

        redisUtil.delete(RedisKeys.participantStatus(id));

        if (muted) {
            webSocketHandler.sendToUser(id, "muted", "您已被管理员禁言");
        }
        return Result.ok();
    }

    /**
     * 移除/恢复直播间
     */
    @PutMapping("/{id}/ban")
    public Result<Void> toggleBan(@PathVariable Long id, @RequestParam boolean banned) {
        Participant p = participantService.getById(id);
        if (p == null) {
            return Result.error("用户不存在");
        }

        p.setIsBanned(banned ? 1 : 0);
        participantService.updateById(p);

        redisUtil.delete(RedisKeys.participantStatus(id));
        // 被移出直播间的同时失去抽奖资格，两个缓存都要清
        redisUtil.delete(RedisKeys.participantQualification(id));

        if (banned) {
            webSocketHandler.sendToUser(id, "banned", "您已被移出直播间");
            webSocketHandler.disconnectUser(id);
        }
        return Result.ok();
    }

    /**
     * 移除/恢复抽奖资格
     *
     * 只改 lottery_status，不动「是否中奖」——中奖事实由 winner 表表达，
     * 恢复资格不会把已经中过奖的人变回「未中奖」。
     */
    @PutMapping("/{id}/lottery")
    public Result<Void> toggleLottery(@PathVariable Long id, @RequestParam boolean removed) {
        Participant p = participantService.getById(id);
        if (p == null) {
            return Result.error("用户不存在");
        }

        p.setLotteryStatus(removed ? 3 : 1);
        participantService.updateById(p);

        redisUtil.delete(RedisKeys.participantQualification(id));

        if (removed) {
            webSocketHandler.sendToUser(id, "lottery_removed", "您已被移除抽奖资格");
        }
        return Result.ok();
    }
}

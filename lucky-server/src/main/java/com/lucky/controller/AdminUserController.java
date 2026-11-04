package com.lucky.controller;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
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
     *
     * 原实现是先把该活动全部参与者（2000 条）查出来，再在内存里 filter 在线的那几十个。
     * 管理端每刷新一次就拉 2000 行，纯浪费。这里改成按在线 ID 反查，只取需要的那几条。
     */
    @GetMapping("/online")
    public Result<List<Participant>> onlineUsers(@RequestParam Long activityId) {
        Set<Long> onlineIds = webSocketHandler.getOnlineParticipantIds();
        if (onlineIds.isEmpty()) {
            return Result.ok(Collections.emptyList());
        }
        // 在线集合是全局的，可能混入其他活动的人，所以仍要按 activityId 过滤
        List<Participant> online = participantService.listByIds(onlineIds).stream()
                .filter(p -> activityId.equals(p.getActivityId()))
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

        // 只更新 is_muted 这一列。
        // 原实现是查出整个对象、改一个字段、再 updateById 全字段覆盖，
        // 如果同一时刻有人改了该用户的 lottery_status，会被这次写入悄悄冲掉。
        participantService.update(new LambdaUpdateWrapper<Participant>()
                .eq(Participant::getId, id)
                .set(Participant::getIsMuted, muted ? 1 : 0));

        // 两套缓存都要清：对象缓存被弹幕链路用来取姓名和禁言状态
        redisUtil.delete(RedisKeys.participantStatus(id));
        redisUtil.delete(RedisKeys.participantObject(id));

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

        // 同样只改 is_banned 这一列，避免全字段覆盖冲掉并发修改
        participantService.update(new LambdaUpdateWrapper<Participant>()
                .eq(Participant::getId, id)
                .set(Participant::getIsBanned, banned ? 1 : 0));

        redisUtil.delete(RedisKeys.participantStatus(id));
        redisUtil.delete(RedisKeys.participantObject(id));
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

        // 只改 lottery_status，不动「是否中奖」——中奖事实由 winner 表表达
        participantService.update(new LambdaUpdateWrapper<Participant>()
                .eq(Participant::getId, id)
                .set(Participant::getLotteryStatus, removed ? 3 : 1));

        redisUtil.delete(RedisKeys.participantQualification(id));

        if (removed) {
            webSocketHandler.sendToUser(id, "lottery_removed", "您已被移除抽奖资格");
        }
        return Result.ok();
    }
}

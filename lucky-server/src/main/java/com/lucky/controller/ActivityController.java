package com.lucky.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.lucky.dto.CreateActivityDTO;
import com.lucky.dto.Result;
import com.lucky.entity.Activity;
import com.lucky.entity.Danmaku;
import com.lucky.entity.LotteryRound;
import com.lucky.entity.Participant;
import com.lucky.entity.Prize;
import com.lucky.entity.Winner;
import com.lucky.mapper.DanmakuMapper;
import com.lucky.mapper.LotteryRoundMapper;
import com.lucky.mapper.ParticipantMapper;
import com.lucky.mapper.PrizeMapper;
import com.lucky.mapper.WinnerMapper;
import com.lucky.service.ActivityService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@Tag(name = "活动管理", description = "活动的创建、查询、更新、删除")
@RestController
@RequestMapping("/api/activity")
@RequiredArgsConstructor
public class ActivityController {

    private final ActivityService activityService;
    private final ParticipantMapper participantMapper;
    private final PrizeMapper prizeMapper;
    private final LotteryRoundMapper lotteryRoundMapper;
    private final WinnerMapper winnerMapper;
    private final DanmakuMapper danmakuMapper;

    @Operation(summary = "创建活动", description = "创建一个新的抽奖活动")
    @PostMapping
    public Result<Activity> create(@Valid @RequestBody CreateActivityDTO dto) {
        return Result.ok(activityService.createActivity(dto));
    }

    @Operation(summary = "获取当前活动", description = "获取当前进行中的活动")
    @GetMapping("/current")
    public Result<Activity> getCurrent() {
        Activity activity = activityService.getCurrentActivity();
        if (activity == null) {
            return Result.error("暂无活动");
        }
        return Result.ok(activity);
    }

    /**
     * 更新活动状态
     *
     * 两个改动：
     * 1. 只更新 status 这一列。原实现是 getById → 改字段 → updateById 全字段写回，
     *    同时有人改了活动的开始/结束时间会被悄悄冲掉。
     * 2. 校验状态流转方向：已结束的活动不能再改回去，进行中的不能退回未开始。
     *    否则会出现「活动显示已结束，但用户还能扫码进入」这种自相矛盾的状态。
     */
    @Operation(summary = "更新活动状态", description = "更新活动的状态（0未开始/1进行中/2已结束）")
    @PutMapping("/{id}/status")
    public Result<Void> updateStatus(
            @Parameter(description = "活动ID", required = true)
            @PathVariable Long id,
            @Parameter(description = "活动状态：0未开始 1进行中 2已结束", required = true)
            @RequestParam Integer status) {
        // 显式校验：@Validated 在 Controller 方法参数上依赖代理，实测不生效，这里直接判更可靠
        if (status == null || status < 0 || status > 2) {
            return Result.error(400, "状态只能是 0（未开始）/1（进行中）/2（已结束）");
        }
        Activity activity = activityService.getById(id);
        if (activity == null) {
            return Result.error("活动不存在");
        }

        Integer current = activity.getStatus();
        if (current != null && current == 2) {
            return Result.error("活动已结束，状态不能再修改");
        }
        if (current != null && current == 1 && status == 0) {
            return Result.error("活动进行中，不能改回未开始");
        }

        activityService.update(new LambdaUpdateWrapper<Activity>()
                .eq(Activity::getId, id)
                .set(Activity::getStatus, status));
        return Result.ok();
    }

    @Operation(summary = "获取历史活动", description = "获取所有历史活动列表")
    @GetMapping("/history")
    public Result<List<Activity>> getHistory() {
        return Result.ok(activityService.getHistoryActivities());
    }

    /**
     * 删除活动
     *
     * 原实现是直接 removeById，删掉之后该活动下的参与者、奖品、轮次、中奖记录、弹幕
     * 全部变成孤儿（activity_id 指向一个不存在的活动），而且**不可恢复**。
     * 现场最坏的情况是手滑删了正在进行的活动，2000 人的数据全部丢失。
     * 这里强制要求先清理完关联数据才允许删除。
     */
    @Operation(summary = "删除活动", description = "根据ID删除活动，必须先清理其下的参与者/奖品/轮次/中奖记录/弹幕")
    @DeleteMapping("/{id}")
    public Result<Void> delete(
            @Parameter(description = "活动ID", required = true)
            @PathVariable Long id) {
        long participants = count(participantMapper.selectCount(
                new LambdaQueryWrapper<Participant>().eq(Participant::getActivityId, id)));
        long prizes = count(prizeMapper.selectCount(
                new LambdaQueryWrapper<Prize>().eq(Prize::getActivityId, id)));
        long rounds = count(lotteryRoundMapper.selectCount(
                new LambdaQueryWrapper<LotteryRound>().eq(LotteryRound::getActivityId, id)));
        long winners = count(winnerMapper.selectCount(
                new LambdaQueryWrapper<Winner>().eq(Winner::getActivityId, id)));
        long danmakus = count(danmakuMapper.selectCount(
                new LambdaQueryWrapper<Danmaku>().eq(Danmaku::getActivityId, id)));

        if (participants > 0 || prizes > 0 || rounds > 0 || winners > 0 || danmakus > 0) {
            return Result.error("该活动下还有 "
                    + participants + " 个参与者、" + prizes + " 个奖品、" + rounds + " 个轮次、"
                    + winners + " 条中奖记录、" + danmakus + " 条弹幕。"
                    + "请先清理完这些数据再删除，避免留下孤儿记录。");
        }

        activityService.removeById(id);
        return Result.ok();
    }

    private static long count(Long value) {
        return value == null ? 0L : value;
    }
}

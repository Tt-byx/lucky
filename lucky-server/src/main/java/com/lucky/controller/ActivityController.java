package com.lucky.controller;

import com.lucky.dto.CreateActivityDTO;
import com.lucky.dto.Result;
import com.lucky.entity.Activity;
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

    @Operation(summary = "更新活动状态", description = "更新活动的状态（0未开始/1进行中/2已结束）")
    @PutMapping("/{id}/status")
    public Result<Void> updateStatus(
            @Parameter(description = "活动ID", required = true)
            @PathVariable Long id,
            @Parameter(description = "活动状态", required = true)
            @RequestParam Integer status) {
        Activity activity = activityService.getById(id);
        if (activity == null) {
            return Result.error("活动不存在");
        }
        activity.setStatus(status);
        activityService.updateById(activity);
        return Result.ok();
    }

    @Operation(summary = "获取历史活动", description = "获取所有历史活动列表")
    @GetMapping("/history")
    public Result<List<Activity>> getHistory() {
        return Result.ok(activityService.getHistoryActivities());
    }

    @Operation(summary = "删除活动", description = "根据ID删除活动")
    @DeleteMapping("/{id}")
    public Result<Void> delete(
            @Parameter(description = "活动ID", required = true)
            @PathVariable Long id) {
        activityService.removeById(id);
        return Result.ok();
    }
}

package com.lucky.controller;

import com.lucky.dto.Result;
import com.lucky.entity.Prize;
import com.lucky.service.PrizeService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 奖品管理Controller
 */
@Tag(name = "奖品管理", description = "奖品的增删改查")
@RestController
@RequestMapping("/api/prize")
@RequiredArgsConstructor
public class PrizeController {

    private final PrizeService prizeService;

    @Operation(summary = "添加奖品")
    @PostMapping
    public Result<Prize> add(@RequestBody Prize prize) {
        prizeService.save(prize);
        return Result.ok(prize);
    }

    @Operation(summary = "获取活动下的奖品列表")
    @GetMapping("/list")
    public Result<List<Prize>> list(@RequestParam Long activityId) {
        return Result.ok(prizeService.getByActivity(activityId));
    }

    @Operation(summary = "更新奖品")
    @PutMapping("/{id}")
    public Result<Void> update(@PathVariable Long id, @RequestBody Prize prize) {
        prize.setId(id);
        prizeService.updateById(prize);
        return Result.ok();
    }

    @Operation(summary = "删除奖品")
    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        prizeService.removeById(id);
        return Result.ok();
    }
}

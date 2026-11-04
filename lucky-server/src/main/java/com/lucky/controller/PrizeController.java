package com.lucky.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.lucky.dto.Result;
import com.lucky.entity.LotteryRound;
import com.lucky.entity.Prize;
import com.lucky.entity.Winner;
import com.lucky.mapper.ActivityMapper;
import com.lucky.mapper.LotteryRoundMapper;
import com.lucky.mapper.WinnerMapper;
import com.lucky.service.PrizeService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
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
    private final ActivityMapper activityMapper;
    private final LotteryRoundMapper lotteryRoundMapper;
    private final WinnerMapper winnerMapper;

    @Operation(summary = "添加奖品")
    @PostMapping
    public Result<Prize> add(@Valid @RequestBody Prize prize) {
        // 必须挂在真实存在的活动下，否则会创建出「幽灵奖品」——活动列表里根本看不到它
        if (prize.getActivityId() == null || activityMapper.selectById(prize.getActivityId()) == null) {
            return Result.error("活动不存在，请先创建活动");
        }
        prizeService.save(prize);
        return Result.ok(prize);
    }

    @Operation(summary = "获取活动下的奖品列表")
    @GetMapping("/list")
    public Result<List<Prize>> list(@RequestParam Long activityId) {
        return Result.ok(prizeService.getByActivity(activityId));
    }

    /**
     * 更新奖品
     *
     * 原实现是「查出整个对象 → 改字段 → updateById 全字段写回」，有两个问题：
     *   1. 全字段覆盖：同一时刻别人改了别的字段会被悄悄冲掉
     *   2. 库存可以随便改，包括改成小于「已发放数量」，抢到奖的人会无货可领
     * 这里改成条件更新，只改允许的字段，并校验库存边界。
     */
    @Operation(summary = "更新奖品")
    @PutMapping("/{id}")
    public Result<Void> update(@PathVariable Long id, @Valid @RequestBody Prize prize) {
        Prize existing = prizeService.getById(id);
        if (existing == null) {
            return Result.error("奖品不存在");
        }

        Integer newStock = prize.getStock() != null ? prize.getStock() : existing.getStock();
        Integer newTotal = prize.getTotalStock() != null ? prize.getTotalStock() : existing.getTotalStock();
        if (newStock == null) {
            newStock = 0;
        }
        if (newTotal == null) {
            newTotal = 0;
        }

        // 显式判负：部分更新时 @Min 对没传的字段不生效，这里用合并后的值再校验一次
        if (newStock < 0 || newTotal < 0) {
            return Result.error("库存和总数都不能为负数");
        }

        // 已发放数量以 winner 表为准，比 Redis 里的预扣值更权威
        Long issued = winnerMapper.selectCount(
                new LambdaQueryWrapper<Winner>().eq(Winner::getPrizeId, id));
        long issuedCount = issued == null ? 0L : issued;

        if (newStock < issuedCount) {
            return Result.error("库存不能少于已发放的 " + issuedCount + " 个");
        }
        if (newStock > newTotal) {
            return Result.error("库存 " + newStock + " 不能超过总数 " + newTotal);
        }

        prizeService.update(new LambdaUpdateWrapper<Prize>()
                .eq(Prize::getId, id)
                .set(prize.getName() != null, Prize::getName, prize.getName())
                .set(prize.getImage() != null, Prize::getImage, prize.getImage())
                .set(prize.getStock() != null, Prize::getStock, prize.getStock())
                .set(prize.getTotalStock() != null, Prize::getTotalStock, prize.getTotalStock()));

        return Result.ok();
    }

    /**
     * 删除奖品
     *
     * 有轮次或中奖记录时不允许删除：删掉之后 lottery_round.prize_id 和 winner.prize_id
     * 会指向一个不存在的奖品，大屏显示中奖者时拿不到奖品名，库存也永远对不上账。
     */
    @Operation(summary = "删除奖品")
    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        Long rounds = lotteryRoundMapper.selectCount(
                new LambdaQueryWrapper<LotteryRound>().eq(LotteryRound::getPrizeId, id));
        Long winners = winnerMapper.selectCount(
                new LambdaQueryWrapper<Winner>().eq(Winner::getPrizeId, id));

        long roundCount = rounds == null ? 0L : rounds;
        long winnerCount = winners == null ? 0L : winners;
        if (roundCount > 0 || winnerCount > 0) {
            return Result.error("该奖品已有 " + roundCount + " 个轮次、" + winnerCount
                    + " 条中奖记录，不能删除");
        }

        prizeService.removeById(id);
        return Result.ok();
    }
}

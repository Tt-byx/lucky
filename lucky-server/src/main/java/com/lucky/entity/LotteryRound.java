package com.lucky.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import lombok.Data;
import java.time.LocalDateTime;

/**
 * 抽奖轮次
 *
 * 业务语义：一个轮次 = 一次限时抢奖活动，绑定一个奖品。
 * 状态机：0待开始 → 1抢奖中 → 2已结束（库存耗尽或管理员手动结束）；3已取消为异常兜底。
 */
@Data
@TableName("lottery_round")
public class LotteryRound {

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long activityId;

    /** 绑定的奖品ID，名额与库存都由该奖品约束 */
    private Long prizeId;

    private String roundName;

    /** 本轮名额，创建时不得超过 prize.stock */
    private Integer winnerCount;

    /** 0待开始 1抢奖中 2已结束 3已取消 */
    private Integer status;

    /** 抢奖开始时间 */
    private LocalDateTime startTime;

    /** 抢奖结束时间 */
    private LocalDateTime endTime;

    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    @Version
    private Integer version;

    /** 奖品名称（非数据库字段，查询时填充，前端直接展示） */
    @TableField(exist = false)
    private String prizeName;
}

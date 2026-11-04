package com.lucky.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 中奖记录（抢奖订单）
 *
 * 一条记录 = 一次成功的抢奖。数据库层有两道唯一约束：
 * uk_round_participant   同一轮次同一人只能有一条
 * uk_activity_participant 同一活动同一人只能中一次
 */
@Data
@TableName("winner")
public class Winner {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 冗余活动ID，用于跨轮次唯一约束 */
    private Long activityId;

    private Long roundId;

    private Long participantId;

    private Long prizeId;

    /** 0待领取 1已领取 2已放弃 */
    private Integer status;

    /** 领奖码，中奖时生成 */
    private String claimCode;

    private LocalDateTime claimedAt;

    private LocalDateTime createdAt;
}

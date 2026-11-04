package com.lucky.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 奖品实体
 *
 * total_stock 是配置总量（不可变），stock 是剩余库存（抢奖实时扣减）。
 * 两者分离的价值：既能做库存对账，也能在出现超卖时快速定位是哪一轮扣多了。
 */
@Data
@TableName("prize")
public class Prize {

    @TableId(type = IdType.AUTO)
    private Long id;

    /**
     * 活动ID
     */
    private Long activityId;

    /**
     * 奖品名称
     */
    private String name;

    /**
     * 奖品图片URL
     */
    private String image;

    /**
     * 奖品描述
     */
    private String description;

    /**
     * 总库存（配置值，不可变）
     */
    private Integer totalStock;

    /**
     * 剩余库存（抢奖实时扣减）
     */
    private Integer stock;

    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}

package com.lucky.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 奖品实体
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
     * 库存数量
     */
    private Integer stock;

    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}

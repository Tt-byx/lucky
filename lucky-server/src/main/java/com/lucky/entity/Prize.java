package com.lucky.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 奖品实体
 *
 * total_stock 是配置总量（不可变），stock 是剩余库存（抢奖实时扣减）。
 * 两者分离的价值：既能做库存对账，也能在出现超卖时快速定位是哪一轮扣多了。
 *
 * 校验注解与数据库列长度保持一致（name 100、image/description 500）。
 * 原来实体上一个校验都没有，超长文本直接撞到数据库列长度报 SQLException，
 * 返回给用户的是「服务器内部错误」，看不出是参数问题。
 */
@Data
@TableName("prize")
public class Prize {

    @TableId(type = IdType.AUTO)
    private Long id;

    /**
     * 活动ID
     *
     * 不加 @NotNull：update 是部分更新，前端只传要改的字段，
     * 强制要求 activityId 会把「只改个名字」也挡下来。
     * 创建时的空值校验由 PrizeController.add 显式完成（还会查活动是否存在）。
     */
    private Long activityId;

    /**
     * 奖品名称
     */
    @Size(max = 100, message = "奖品名称不能超过100个字符")
    private String name;

    /**
     * 奖品图片URL
     */
    @Size(max = 500, message = "奖品图片URL不能超过500个字符")
    private String image;

    /**
     * 奖品描述
     */
    @Size(max = 500, message = "奖品描述不能超过500个字符")
    private String description;

    /**
     * 总库存（配置值，不可变）
     */
    @Min(value = 0, message = "总库存不能为负数")
    private Integer totalStock;

    /**
     * 剩余库存（抢奖实时扣减）
     */
    @Min(value = 0, message = "剩余库存不能为负数")
    private Integer stock;

    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}

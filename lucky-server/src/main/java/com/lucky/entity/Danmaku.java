package com.lucky.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("danmaku")
public class Danmaku {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long activityId;

    private Long participantId;

    private String content;

    private Integer status; // 0待审核 1已通过 2已拒绝

    private LocalDateTime createdAt;

    /** 非持久化字段：批量入库重试次数 */
    @TableField(exist = false)
    private Integer retryCount;
}

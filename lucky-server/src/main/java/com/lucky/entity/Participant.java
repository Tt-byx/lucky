package com.lucky.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import java.time.LocalDateTime;

@Data
@TableName("participant")
public class Participant {

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long activityId;
    private String name;
    private String phone;
    private String studentId;

    /**
     * 抽奖资格：1可参与 3已移除
     * 注意：中奖与否不写在这里，由 winner 表表达，避免一个字段承载两种语义
     */
    private Integer lotteryStatus;

    private Integer isMuted;
    private Integer isBanned;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}

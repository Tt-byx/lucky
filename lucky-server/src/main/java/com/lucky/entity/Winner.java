package com.lucky.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 中奖记录实体
 */
@Data
@TableName("winner")
public class Winner {

    @TableId(type = IdType.AUTO)
    private Long id;

    /**
     * 轮次ID
     */
    private Long roundId;

    /**
     * 参与者ID
     */
    private Long participantId;

    /**
     * 是否已通知 0否 1是
     */
    private Integer notified;

    /**
     * 是否已领奖 0否 1是
     */
    private Integer claimed;

    /**
     * 领奖时间
     */
    private LocalDateTime claimedAt;

    private LocalDateTime createdAt;
}

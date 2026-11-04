package com.lucky.dto;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 中奖结果展示对象
 *
 * 大屏需要直接显示「谁中了什么奖」，所以把参与者和奖品信息拼好再返回，
 * 避免前端拿到一堆 ID 还要自己再查一遍。
 */
@Data
public class WinnerVO {

    private Long id;
    private Long roundId;
    private Long participantId;
    private String participantName;
    private String studentId;
    private Long prizeId;
    private String prizeName;
    private String claimCode;
    private Integer status;
    private LocalDateTime createdAt;
}

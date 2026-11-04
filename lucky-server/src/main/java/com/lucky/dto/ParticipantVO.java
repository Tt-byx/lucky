package com.lucky.dto;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 参与者VO - 用于前端展示
 */
@Data
public class ParticipantVO {

    private Long id;
    private Long activityId;
    private String name;
    private String studentId;
    private Integer status;
    private String statusText; // 抽奖资格：可参与/已移除抽奖
    private Integer isMuted;
    private Integer isBanned;
    private LocalDateTime createdAt;
}

package com.lucky.dto;

import lombok.Builder;
import lombok.Data;

/**
 * 扫码登录响应 VO
 * 返回场次专属临时令牌及用户信息
 */
@Data
@Builder
public class ScanLoginVO {

    /** 场次专属临时令牌（Redis 存储，绑定活动ID+用户ID+有效期） */
    private String token;

    /** 参与者 ID */
    private Long participantId;

    /** 活动 ID */
    private Long activityId;

    /** 用户姓名 */
    private String name;

    /** 学号 */
    private String studentId;

    /** 令牌有效期（秒） */
    private Long expiresIn;
}

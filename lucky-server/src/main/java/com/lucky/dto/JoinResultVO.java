package com.lucky.dto;

import lombok.Builder;
import lombok.Data;

/**
 * 抢奖结果
 *
 * code 语义与 Lua 脚本返回值保持一致，前端可直接按 code 分支处理：
 *   1  抢奖成功（已进入异步落库队列）
 *   0  名额已抢完
 *  -1  本轮已抢到，重复点击
 *  -2  本活动已中过奖
 *  -3  本轮未开始 / 已结束
 *  -4  无抽奖资格
 *  -5  系统繁忙（MQ 异常，已回滚预扣）
 */
@Data
@Builder
public class JoinResultVO {

    private Integer code;
    private String message;
    private Boolean won;

    private Long roundId;
    private Long participantId;
    private Long prizeId;
    private String prizeName;

    /** 抢奖成功后剩余名额，用于大屏实时展示 */
    private Integer remaining;
}

package com.lucky.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 抽奖轮次状态枚举
 */
@Getter
@AllArgsConstructor
public enum LotteryStatus {

    NOT_STARTED(0, "未开始"),
    COMPLETED(1, "已完成");

    private final int code;
    private final String desc;

    /**
     * 根据 code 获取枚举
     */
    public static LotteryStatus fromCode(int code) {
        for (LotteryStatus status : values()) {
            if (status.code == code) {
                return status;
            }
        }
        throw new IllegalArgumentException("未知的抽奖状态: " + code);
    }

    /**
     * 判断是否已完成
     */
    public boolean isCompleted() {
        return this == COMPLETED;
    }
}

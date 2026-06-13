package com.lucky.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 活动状态枚举
 */
@Getter
@AllArgsConstructor
public enum ActivityStatus {

    NOT_STARTED(0, "未开始"),
    IN_PROGRESS(1, "进行中"),
    ENDED(2, "已结束");

    private final int code;
    private final String desc;

    /**
     * 根据 code 获取枚举
     */
    public static ActivityStatus fromCode(int code) {
        for (ActivityStatus status : values()) {
            if (status.code == code) {
                return status;
            }
        }
        throw new IllegalArgumentException("未知的活动状态: " + code);
    }
}

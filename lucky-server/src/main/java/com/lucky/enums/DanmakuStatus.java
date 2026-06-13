package com.lucky.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 弹幕状态枚举
 */
@Getter
@AllArgsConstructor
public enum DanmakuStatus {

    PENDING(0, "待审核"),
    APPROVED(1, "已通过"),
    REJECTED(2, "已拒绝");

    private final int code;
    private final String desc;

    /**
     * 根据 code 获取枚举
     */
    public static DanmakuStatus fromCode(int code) {
        for (DanmakuStatus status : values()) {
            if (status.code == code) {
                return status;
            }
        }
        throw new IllegalArgumentException("未知的弹幕状态: " + code);
    }

    /**
     * 判断是否已通过
     */
    public boolean isApproved() {
        return this == APPROVED;
    }

    /**
     * 判断是否待审核
     */
    public boolean isPending() {
        return this == PENDING;
    }
}

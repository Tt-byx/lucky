package com.lucky.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 参与者状态枚举
 */
@Getter
@AllArgsConstructor
public enum ParticipantStatus {

    ACTIVE(1, "正常参与"),
    WON(0, "已中奖"),
    REMOVED(3, "已移除抽奖");

    private final int code;
    private final String desc;

    /**
     * 根据 code 获取枚举
     */
    public static ParticipantStatus fromCode(int code) {
        for (ParticipantStatus status : values()) {
            if (status.code == code) {
                return status;
            }
        }
        throw new IllegalArgumentException("未知的参与者状态: " + code);
    }

    /**
     * 判断是否可以参与抽奖
     */
    public boolean canDraw() {
        return this == ACTIVE;
    }
}

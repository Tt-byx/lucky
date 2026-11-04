package com.lucky.dto;

import com.lucky.entity.Activity;
import com.lucky.entity.Danmaku;
import com.lucky.entity.Participant;

/**
 * VO转换工具类
 */
public class VOConverter {

    /**
     * Activity -> ActivityVO
     */
    public static ActivityVO toActivityVO(Activity activity) {
        if (activity == null) {
            return null;
        }
        ActivityVO vo = new ActivityVO();
        vo.setId(activity.getId());
        vo.setName(activity.getName());
        vo.setStatus(activity.getStatus());
        vo.setStatusText(getActivityStatusText(activity.getStatus()));
        vo.setCreatedAt(activity.getCreatedAt());
        return vo;
    }

    /**
     * Participant -> ParticipantVO
     */
    public static ParticipantVO toParticipantVO(Participant participant) {
        if (participant == null) {
            return null;
        }
        ParticipantVO vo = new ParticipantVO();
        vo.setId(participant.getId());
        vo.setActivityId(participant.getActivityId());
        vo.setName(participant.getName());
        vo.setStudentId(participant.getStudentId());
        vo.setStatus(participant.getLotteryStatus());
        vo.setStatusText(getParticipantStatusText(participant.getLotteryStatus()));
        vo.setIsMuted(participant.getIsMuted());
        vo.setIsBanned(participant.getIsBanned());
        vo.setCreatedAt(participant.getCreatedAt());
        return vo;
    }

    /**
     * Danmaku -> DanmakuVO
     */
    public static DanmakuVO toDanmakuVO(Danmaku danmaku, String participantName) {
        if (danmaku == null) {
            return null;
        }
        DanmakuVO vo = new DanmakuVO();
        vo.setId(danmaku.getId());
        vo.setActivityId(danmaku.getActivityId());
        vo.setParticipantId(danmaku.getParticipantId());
        vo.setParticipantName(participantName != null ? participantName : "匿名");
        vo.setContent(danmaku.getContent());
        vo.setStatus(danmaku.getStatus());
        vo.setStatusText(getDanmakuStatusText(danmaku.getStatus()));
        vo.setCreatedAt(danmaku.getCreatedAt());
        return vo;
    }

    /**
     * 获取活动状态文本
     */
    private static String getActivityStatusText(Integer status) {
        if (status == null) return "未知";
        return switch (status) {
            case 0 -> "未开始";
            case 1 -> "进行中";
            case 2 -> "已结束";
            default -> "未知";
        };
    }

    /**
     * 获取参与者抽奖资格文本
     * 注意：这里只表达「能不能参与」，中奖与否由 winner 表决定，不再混进参与者状态
     */
    private static String getParticipantStatusText(Integer status) {
        if (status == null) return "未知";
        return switch (status) {
            case 1 -> "可参与";
            case 3 -> "已移除抽奖";
            default -> "未知";
        };
    }

    /**
     * 获取弹幕状态文本
     */
    private static String getDanmakuStatusText(Integer status) {
        if (status == null) return "未知";
        return switch (status) {
            case 0 -> "待审核";
            case 1 -> "已通过";
            case 2 -> "已拒绝";
            default -> "未知";
        };
    }
}

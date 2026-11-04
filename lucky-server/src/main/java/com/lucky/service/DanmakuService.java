package com.lucky.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.lucky.dto.DanmakuDTO;
import com.lucky.entity.Danmaku;

import java.util.List;

public interface DanmakuService extends IService<Danmaku> {

    /**
     * 发送弹幕
     *
     * 全自动通过，命中敏感词时返回 status=2 的对象（已异步入库留档）。
     */
    Danmaku sendDanmaku(DanmakuDTO dto);

    /**
     * 获取已通过的弹幕
     */
    List<Danmaku> getApprovedDanmakus(Long activityId);
}

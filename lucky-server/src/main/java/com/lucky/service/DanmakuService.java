package com.lucky.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.lucky.dto.DanmakuDTO;
import com.lucky.entity.Danmaku;

import java.util.List;
import java.util.Map;

public interface DanmakuService extends IService<Danmaku> {

    /**
     * 发送弹幕
     *
     * 全自动通过，命中敏感词时返回 status=2 的对象（已异步入库留档）。
     */
    Danmaku sendDanmaku(DanmakuDTO dto);

    /**
     * 获取已通过的弹幕
     *
     * @param participantId 可选，只查某个参与者的发言；不传则返回整场
     *                      内部只取最近若干条，避免一次拉几千条数据
     */
    List<Danmaku> getApprovedDanmakus(Long activityId, Long participantId);

    /**
     * 统计每个参与者的弹幕数
     *
     * 管理端要在参与者列表里显示「发了多少条」。原来是前端对每个参与者
     * 都拉一次全量弹幕再在内存里过滤，2000 人就是 2000 次请求、浏览器直接卡死。
     * 这里一次 group by 给出全部结果。
     */
    Map<Long, Long> countByParticipant(Long activityId);
}

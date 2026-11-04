package com.lucky.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.lucky.dto.ParticipantDTO;
import com.lucky.entity.Activity;
import com.lucky.entity.Participant;
import com.lucky.mapper.ActivityMapper;
import com.lucky.mapper.ParticipantMapper;
import com.lucky.service.ParticipantService;
import com.lucky.exception.BusinessException;
import com.lucky.util.RedisUtil;
import com.lucky.websocket.LuckyWebSocketHandler;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * 参与者服务实现
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ParticipantServiceImpl extends ServiceImpl<ParticipantMapper, Participant>
        implements ParticipantService {

    private final ActivityMapper activityMapper;
    private final RedisUtil redisUtil;
    private final LuckyWebSocketHandler webSocketHandler;

    /**
     * 参与者数量缓存前缀
     */
    private static final String PARTICIPANT_COUNT_KEY = "lucky:participant:count:";

    @Override
    public Participant register(ParticipantDTO dto) {
        if (dto.getActivityId() == null) {
            throw new BusinessException("活动不存在，请先创建活动");
        }
        // 和扫码登录保持一致：活动必须真实存在且未结束，否则会往库里塞幽灵参与者
        Activity activity = activityMapper.selectById(dto.getActivityId());
        if (activity == null) {
            throw new BusinessException("活动不存在，请扫描现场最新的二维码");
        }
        if (activity.getStatus() != null && activity.getStatus() == 2) {
            throw new BusinessException("本次活动已结束，无法注册");
        }

        Participant participant = new Participant();
        participant.setActivityId(dto.getActivityId());
        participant.setName(dto.getName());
        participant.setPhone(dto.getPhone());
        participant.setStudentId(dto.getStudentId());
        participant.setLotteryStatus(1);

        try {
            save(participant);
        } catch (DuplicateKeyException e) {
            // 捕获数据库唯一约束异常，说明已注册
            log.warn("参与者重复注册: activityId={}, studentId={}", dto.getActivityId(), dto.getStudentId());
            throw new BusinessException("您已经参与过本次活动");
        }

        bumpParticipantCount(dto.getActivityId());

        log.info("参与者注册成功: activityId={}, studentId={}, name={}",
                dto.getActivityId(), dto.getStudentId(), dto.getName());

        return participant;
    }

    /**
     * 参与人数 +1 并广播
     *
     * 原实现有两个问题：
     * 1. 直接 INCR 计数 key。如果该 key 不存在（Redis 重启、活动刚开始），
     *    INCR 会把它创建成 1，之后 getCountByActivity 读到缓存就直接返回 1，
     *    哪怕库里已经有几百人 —— 大屏的「参与人数」会从 1 开始重新数。
     * 2. 广播用的是 sendOnlineCount（在线人数事件），但传的是总人数，
     *    大屏的「在线人数」会被写成总人数。
     */
    private void bumpParticipantCount(Long activityId) {
        String countKey = PARTICIPANT_COUNT_KEY + activityId;
        // 只有缓存确实存在时才自增；不存在就让下面的 getCountByActivity 从 DB 重建
        if (Boolean.TRUE.equals(redisUtil.hasKey(countKey))) {
            redisUtil.increment(countKey);
        }
        int total = getCountByActivity(activityId);
        // 走 participant_update 事件，大屏对应的是「参与人数」而不是「在线人数」
        webSocketHandler.broadcast("participant_update", total);
    }

    @Override
    public List<Participant> getByActivity(Long activityId) {
        return list(new LambdaQueryWrapper<Participant>()
                .eq(Participant::getActivityId, activityId)
                .orderByDesc(Participant::getCreatedAt));
    }

    @Override
    public int getCountByActivity(Long activityId) {
        // 先从 Redis 缓存获取
        String countKey = PARTICIPANT_COUNT_KEY + activityId;
        Object cached = redisUtil.get(countKey);
        if (cached != null) {
            return Integer.parseInt(cached.toString());
        }

        // 缓存未命中，从数据库查询
        int count = (int) count(new LambdaQueryWrapper<Participant>()
                .eq(Participant::getActivityId, activityId));

        // 写入缓存
        redisUtil.set(countKey, count, 24, TimeUnit.HOURS);

        return count;
    }
}

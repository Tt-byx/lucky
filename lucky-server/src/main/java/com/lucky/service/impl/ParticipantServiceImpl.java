package com.lucky.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.lucky.dto.ParticipantDTO;
import com.lucky.entity.Participant;
import com.lucky.mapper.ParticipantMapper;
import com.lucky.mq.MessageProducer;
import com.lucky.service.ParticipantService;
import com.lucky.exception.BusinessException;
import com.lucky.util.RedisUtil;
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

    private final MessageProducer messageProducer;
    private final RedisUtil redisUtil;

    /**
     * 参与者数量缓存前缀
     */
    private static final String PARTICIPANT_COUNT_KEY = "lucky:participant:count:";

    @Override
    public Participant register(ParticipantDTO dto) {
        if (dto.getActivityId() == null) {
            throw new BusinessException("活动不存在，请先创建活动");
        }

        Participant participant = new Participant();
        participant.setActivityId(dto.getActivityId());
        participant.setName(dto.getName());
        participant.setPhone(dto.getPhone());
        participant.setStudentId(dto.getStudentId());
        participant.setStatus(1);

        try {
            save(participant);
        } catch (DuplicateKeyException e) {
            // 捕获数据库唯一约束异常，说明已注册
            log.warn("参与者重复注册: activityId={}, studentId={}", dto.getActivityId(), dto.getStudentId());
            throw new BusinessException("您已经参与过本次活动");
        }

        // 更新 Redis 缓存的参与者数量
        String countKey = PARTICIPANT_COUNT_KEY + dto.getActivityId();
        redisUtil.increment(countKey);
        redisUtil.expire(countKey, 24, TimeUnit.HOURS);

        // 通过消息队列异步广播参与者数量更新
        int total = getCountByActivity(dto.getActivityId());
        messageProducer.sendOnlineCount(total);

        log.info("参与者注册成功: activityId={}, studentId={}, name={}",
                dto.getActivityId(), dto.getStudentId(), dto.getName());

        return participant;
    }

    @Override
    public Participant checkRegistered(Long activityId, String studentId) {
        Participant p = getOne(new LambdaQueryWrapper<Participant>()
                .eq(Participant::getActivityId, activityId)
                .eq(Participant::getStudentId, studentId)
                .last("LIMIT 1"));
        if (p != null && p.getIsBanned() != null && p.getIsBanned() == 1) {
            throw new BusinessException("您已被移出直播间，无法进入");
        }
        return p;
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

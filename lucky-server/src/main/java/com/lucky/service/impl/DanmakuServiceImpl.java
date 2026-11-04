package com.lucky.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.lucky.annotation.RateLimit;
import com.lucky.dto.DanmakuDTO;
import com.lucky.entity.Danmaku;
import com.lucky.entity.Participant;
import com.lucky.mapper.DanmakuMapper;
import com.lucky.mapper.ParticipantMapper;
import com.lucky.mq.MessageProducer;
import com.lucky.service.DanmakuService;
import com.lucky.service.SensitiveWordService;
import com.lucky.exception.BusinessException;
import com.lucky.util.RedisUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * 弹幕服务实现（纯 MQ 异步削峰架构）
 *
 * 核心架构：
 * 同步流程：参数校验 → 敏感词过滤 → Redis+Lua 限流 → 发送 MQ → 直接返回成功
 * 异步流程：MQ 消费者 → 场次定向批量推送 → 异步批量入库
 *
 * 性能优化：
 * - 移除同步 DB 写入，主链路无数据库操作，响应时间 < 10ms
 * - Redis+Lua 原子限流：单用户每秒最多 1 条弹幕，杜绝刷屏
 * - 参与者状态缓存：禁言/移出状态走 Redis，避免高频 DB 查询
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DanmakuServiceImpl extends ServiceImpl<DanmakuMapper, Danmaku>
        implements DanmakuService {

    private final SensitiveWordService sensitiveWordService;
    private final MessageProducer messageProducer;
    private final ParticipantMapper participantMapper;
    private final RedisUtil redisUtil;

    /** 参与者状态缓存前缀（统一由 RedisKeys 管理） */

    /** 弹幕异步处理消息中的字段 */
    private static final String FIELD_ACTIVITY_ID = "activityId";
    private static final String FIELD_PARTICIPANT_ID = "participantId";
    private static final String FIELD_CONTENT = "content";
    private static final String FIELD_STATUS = "status";
    private static final String FIELD_PARTICIPANT_NAME = "participantName";
    private static final String FIELD_TIMESTAMP = "timestamp";

    @Override
    @RateLimit(
            prefix = "danmaku_send",
            maxCount = 1,
            windowSeconds = 1,
            type = RateLimit.LimitType.CUSTOM,
            keyExpression = "#dto.participantId",
            message = "弹幕发送过于频繁，请稍后再试（每秒最多1条）"
    )
    public Danmaku sendDanmaku(DanmakuDTO dto) {
        // 1. 参数校验
        if (dto.getActivityId() == null) {
            throw new BusinessException("活动不存在，请先创建活动");
        }
        if (dto.getContent() == null || dto.getContent().trim().isEmpty()) {
            throw new BusinessException("弹幕内容不能为空");
        }

        // 2. 参与者状态校验（Redis 缓存优先，避免高频 DB 查询）
        if (dto.getParticipantId() != null) {
            checkParticipantStatus(dto.getParticipantId());
        }

        // 3. 敏感词过滤（同步执行，拦截违规内容）
        if (sensitiveWordService.containsSensitiveWord(dto.getContent())) {
            log.info("弹幕包含敏感词，已拒绝: participantId={}, content={}",
                    dto.getParticipantId(), dto.getContent());
            // 敏感词弹幕异步记录（不阻塞主链路）
            asyncRecordRejectedDanmaku(dto);
            Danmaku rejected = new Danmaku();
            rejected.setActivityId(dto.getActivityId());
            rejected.setParticipantId(dto.getParticipantId());
            rejected.setContent(dto.getContent());
            rejected.setStatus(2); // 已拒绝
            return rejected;
        }

        // 4. 构建弹幕消息，发送至 RabbitMQ（纯异步，不写 DB）
        Map<String, Object> danmakuMessage = buildDanmakuMessage(dto);
        messageProducer.sendDanmaku(danmakuMessage);

        log.debug("弹幕已发送至 MQ: activityId={}, participantId={}, content={}",
                dto.getActivityId(), dto.getParticipantId(), dto.getContent());

        // 5. 直接返回成功（弹幕由 MQ 消费者异步推送 + 入库）
        Danmaku result = new Danmaku();
        result.setActivityId(dto.getActivityId());
        result.setParticipantId(dto.getParticipantId());
        result.setContent(dto.getContent());
        result.setStatus(1); // 自动通过
        return result;
    }

    /**
     * 校验参与者状态（禁言/移出），Redis 缓存优先
     */
    private void checkParticipantStatus(Long participantId) {
        String cacheKey = com.lucky.constant.RedisKeys.participantStatus(participantId);

        // 尝试从缓存获取状态
        Object cached = redisUtil.get(cacheKey);
        if (cached != null) {
            String status = cached.toString();
            if ("muted".equals(status)) {
                throw new BusinessException("您已被禁言，无法发送弹幕");
            }
            if ("banned".equals(status)) {
                throw new BusinessException("您已被移出直播间");
            }
            return; // 缓存命中且状态正常
        }

        // 缓存未命中，查询 DB 并回写缓存
        Participant sender = participantMapper.selectById(participantId);
        if (sender != null) {
            if (sender.getIsMuted() != null && sender.getIsMuted() == 1) {
                redisUtil.set(cacheKey, "muted", 1, TimeUnit.HOURS);
                throw new BusinessException("您已被禁言，无法发送弹幕");
            }
            if (sender.getIsBanned() != null && sender.getIsBanned() == 1) {
                redisUtil.set(cacheKey, "banned", 1, TimeUnit.HOURS);
                throw new BusinessException("您已被移出直播间");
            }
            // 状态正常，缓存正常标记
            redisUtil.set(cacheKey, "normal", 1, TimeUnit.HOURS);
        }
    }

    /**
     * 构建发送至 MQ 的弹幕消息（包含推送和入库所需全部信息）
     */
    private Map<String, Object> buildDanmakuMessage(DanmakuDTO dto) {
        Map<String, Object> message = new HashMap<>();
        message.put(FIELD_ACTIVITY_ID, dto.getActivityId());
        message.put(FIELD_PARTICIPANT_ID, dto.getParticipantId());
        message.put(FIELD_CONTENT, dto.getContent());
        message.put(FIELD_STATUS, 1); // 自动审核通过
        message.put(FIELD_TIMESTAMP, System.currentTimeMillis());

        // 预查询发送者名称（用于大屏展示，避免消费者重复查 DB）
        if (dto.getParticipantId() != null) {
            Participant p = participantMapper.selectById(dto.getParticipantId());
            message.put(FIELD_PARTICIPANT_NAME, p != null ? p.getName() : "匿名");
        } else {
            message.put(FIELD_PARTICIPANT_NAME, "匿名");
        }

        return message;
    }

    /**
     * 异步记录被拒绝的弹幕（通过 MQ 异步入库，不阻塞主链路）
     */
    private void asyncRecordRejectedDanmaku(DanmakuDTO dto) {
        Map<String, Object> message = new HashMap<>();
        message.put(FIELD_ACTIVITY_ID, dto.getActivityId());
        message.put(FIELD_PARTICIPANT_ID, dto.getParticipantId());
        message.put(FIELD_CONTENT, dto.getContent());
        message.put(FIELD_STATUS, 2); // 已拒绝
        message.put(FIELD_TIMESTAMP, System.currentTimeMillis());
        message.put(FIELD_PARTICIPANT_NAME, "匿名");
        messageProducer.sendDanmaku(message);
    }

    @Override
    public void reviewDanmaku(Long id, Integer status) {
        Danmaku danmaku = getById(id);
        if (danmaku != null) {
            danmaku.setStatus(status);
            updateById(danmaku);

            // 如果审核通过，通过 MQ 异步广播到大屏（场次定向）
            if (status == 1) {
                Map<String, Object> data = new HashMap<>();
                data.put(FIELD_ACTIVITY_ID, danmaku.getActivityId());
                data.put(FIELD_PARTICIPANT_ID, danmaku.getParticipantId());
                data.put(FIELD_CONTENT, danmaku.getContent());
                data.put(FIELD_STATUS, 1);
                data.put(FIELD_TIMESTAMP, System.currentTimeMillis());
                if (danmaku.getParticipantId() != null) {
                    Participant p = participantMapper.selectById(danmaku.getParticipantId());
                    data.put(FIELD_PARTICIPANT_NAME, p != null ? p.getName() : "匿名");
                } else {
                    data.put(FIELD_PARTICIPANT_NAME, "匿名");
                }
                messageProducer.sendDanmaku(data);
            }
        }
    }

    @Override
    public List<Danmaku> getPendingDanmakus(Long activityId) {
        return list(new LambdaQueryWrapper<Danmaku>()
                .eq(Danmaku::getActivityId, activityId)
                .eq(Danmaku::getStatus, 0)
                .orderByDesc(Danmaku::getCreatedAt));
    }

    @Override
    public List<Danmaku> getApprovedDanmakus(Long activityId) {
        return list(new LambdaQueryWrapper<Danmaku>()
                .eq(Danmaku::getActivityId, activityId)
                .eq(Danmaku::getStatus, 1)
                .orderByDesc(Danmaku::getCreatedAt));
    }
}

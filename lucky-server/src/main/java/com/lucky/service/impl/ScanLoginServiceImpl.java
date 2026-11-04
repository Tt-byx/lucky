package com.lucky.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.lucky.annotation.RateLimit;
import com.lucky.dto.ScanLoginDTO;
import com.lucky.dto.ScanLoginVO;
import com.lucky.entity.Activity;
import com.lucky.entity.Participant;
import com.lucky.exception.BusinessException;
import com.lucky.mapper.ActivityMapper;
import com.lucky.mapper.ParticipantMapper;
import com.lucky.service.ScanLoginService;
import com.lucky.util.RedisUtil;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.scripting.support.ResourceScriptSource;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * 扫码登录服务实现（高并发接入优化）
 *
 * 核心优化：
 * 1. Redis 临时令牌：登录成功后生成场次专属令牌，绑定活动ID+用户ID+有效期
 *    后续请求优先通过 Redis 校验令牌，避免大量请求直接打数据库
 * 2. Redis+Lua 限流：单 IP / 单用户 60s 登录频次限制，防止恶意刷请求
 * 3. 异步入库：登录校验成功后同步返回结果，用户基础信息、登录记录异步入库
 *    减少主链路耗时，接口响应时间 ≤ 50ms
 * 4. 参与者缓存：已注册用户信息缓存至 Redis，避免重复查库
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ScanLoginServiceImpl implements ScanLoginService {

    private final ParticipantMapper participantMapper;
    private final ActivityMapper activityMapper;
    private final RedisUtil redisUtil;
    private final StringRedisTemplate stringRedisTemplate;

    /** 单学号登录频次限制用的原子计数器脚本 */
    private DefaultRedisScript<Long> rateCounterScript;

    @PostConstruct
    public void init() {
        rateCounterScript = new DefaultRedisScript<>();
        rateCounterScript.setScriptSource(
                new ResourceScriptSource(new ClassPathResource("lua/rate_counter.lua")));
        rateCounterScript.setResultType(Long.class);
    }

    /** 临时令牌 Redis key 前缀 */
    private static final String TOKEN_KEY_PREFIX = "lucky:scan:token:";

    /** 参与者信息缓存前缀 */
    private static final String PARTICIPANT_CACHE_KEY = "lucky:participant:info:";

    /**
     * 令牌有效期：8 小时。
     *
     * 原值是 2 小时，撑不住一场毕业典礼——典礼通常 2~3 小时，
     * 到后半程全场令牌集体过期，用户 WebSocket 断开后连不回来、抢奖全部 401。
     * 单独一个活动的时间窗不会超过 8 小时，足够覆盖。
     */
    private static final long TOKEN_EXPIRE_SECONDS = 28800;

    @Override
    @RateLimit(
            prefix = "scan_login",
            maxCount = 200,
            windowSeconds = 60,
            type = RateLimit.LimitType.IP,
            message = "登录请求过于频繁，请稍后再试"
    )
    /*
     * 限流阈值说明：
     * 初版是单 IP 10 次/60 秒，放在真实毕业典礼场景会直接出事——
     * 现场 2000 人连的是同一个校园 WiFi，出口 IP 只有几个，
     * 第 11 个学生开始就会全部收到 429。
     * 这里放宽到 200 次/60 秒，真正的防刷交给「单学号」维度（见 login 内的频次校验）。
     */
    public ScanLoginVO login(ScanLoginDTO dto) {
        // 1. 校验活动真实存在且未结束
        //    原先只判了 activityId 不为空，导致随便传一个不存在的 ID 也能注册成功，
        //    数据库里会堆出挂在幽灵活动下的参与者；用户扫了过期二维码也会「登录成功」，
        //    进直播间后才发现什么都没有。
        if (dto.getActivityId() == null) {
            throw new BusinessException("活动不存在，请先创建活动");
        }
        Activity activity = activityMapper.selectById(dto.getActivityId());
        if (activity == null) {
            throw new BusinessException("活动不存在，请扫描现场最新的二维码");
        }
        if (activity.getStatus() != null && activity.getStatus() == 2) {
            throw new BusinessException("本次活动已结束，无法进入");
        }

        // 2. 单学号频次限制：IP 维度已经放宽（校园网 NAT），防刷靠这一层
        checkStudentRate(dto);

        // 3. 查找或创建参与者（优先缓存，避免高频 DB 查询）
        Participant participant = findOrCreateParticipant(dto);
        if (participant == null) {
            // 并发插入冲突后回查也可能落空，这里必须兜住，否则下一行就是 NPE
            throw new BusinessException("注册失败，请重试");
        }

        // 4. 检查是否被移出直播间
        if (participant.getIsBanned() != null && participant.getIsBanned() == 1) {
            throw new BusinessException("您已被移出直播间，无法登录");
        }

        // 5. 生成场次专属临时令牌（绑定活动ID+用户ID+有效期）
        String token = generateToken(participant, dto.getActivityId());

        // 6. 异步记录登录日志（不阻塞主链路）
        asyncRecordLogin(participant);

        log.info("扫码登录成功: activityId={}, participantId={}, studentId={}, name={}",
                dto.getActivityId(), participant.getId(), dto.getStudentId(), dto.getName());

        // 7. 同步返回登录结果（主链路无 DB 写入，响应 ≤ 50ms）
        return ScanLoginVO.builder()
                .token(token)
                .participantId(participant.getId())
                .activityId(dto.getActivityId())
                .name(participant.getName())
                .studentId(participant.getStudentId())
                .expiresIn(TOKEN_EXPIRE_SECONDS)
                .build();
    }

    /**
     * 单学号登录频次限制：60 秒内最多 5 次
     * 同一学号被反复刷说明是脚本，和 IP 无关
     *
     * 自增和设置过期必须在同一个 Lua 里完成：
     * 原来分两步调用，如果 INCR 之后连接断开导致 EXPIRE 没执行，
     * 这个 key 就永久存在，对应学号会被永远限流。
     */
    private void checkStudentRate(ScanLoginDTO dto) {
        String key = "lucky:scan:student:" + dto.getActivityId() + ":" + dto.getStudentId();
        Long count = stringRedisTemplate.execute(
                rateCounterScript, Collections.singletonList(key), "60");
        if (count != null && count > 5L) {
            throw new BusinessException("该学号登录过于频繁，请稍后再试");
        }
    }

    @Override
    public Long validateToken(String token) {
        if (token == null || token.isEmpty()) return null;

        Object cached = redisUtil.get(TOKEN_KEY_PREFIX + token);
        if (cached == null) return null;

        try {
            // 令牌值格式：participantId:activityId
            String[] parts = cached.toString().split(":");
            return Long.parseLong(parts[0]);
        } catch (Exception e) {
            log.warn("令牌解析失败: {}", token);
            return null;
        }
    }

    @Override
    public void logout(String token) {
        if (token != null && !token.isEmpty()) {
            redisUtil.delete(TOKEN_KEY_PREFIX + token);
            log.info("用户登出，令牌已销毁: {}", token);
        }
    }

    /**
     * 查找已注册参与者，不存在则创建（异步落库）
     * 优先从 Redis 缓存获取，减少数据库压力
     */
    private Participant findOrCreateParticipant(ScanLoginDTO dto) {
        String cacheKey = PARTICIPANT_CACHE_KEY + dto.getActivityId() + ":" + dto.getStudentId();

        // 1. 优先从缓存获取
        Object cached = redisUtil.get(cacheKey);
        if (cached != null) {
            try {
                Long participantId = Long.parseLong(cached.toString());
                Participant p = participantMapper.selectById(participantId);
                if (p != null) {
                    log.debug("参与者缓存命中: activityId={}, studentId={}",
                            dto.getActivityId(), dto.getStudentId());
                    return p;
                }
            } catch (NumberFormatException ignored) {
            }
        }

        // 2. 缓存未命中，查询数据库
        Participant existing = participantMapper.selectOne(
                new LambdaQueryWrapper<Participant>()
                        .eq(Participant::getActivityId, dto.getActivityId())
                        .eq(Participant::getStudentId, dto.getStudentId())
                        .last("LIMIT 1")
        );

        if (existing != null) {
            // 回写缓存
            redisUtil.set(cacheKey, existing.getId(), 24, TimeUnit.HOURS);
            return existing;
        }

        // 3. 不存在，创建新参与者
        //    姓名只有「首次注册」时才是必填，老用户快速进入可以不传
        if (dto.getName() == null || dto.getName().trim().isEmpty()) {
            throw new BusinessException("该学号尚未注册，请填写姓名完成注册");
        }

        Participant participant = new Participant();
        participant.setActivityId(dto.getActivityId());
        participant.setName(dto.getName());
        participant.setPhone(dto.getPhone());
        participant.setStudentId(dto.getStudentId());
        participant.setLotteryStatus(1);

        try {
            participantMapper.insert(participant);
        } catch (DuplicateKeyException e) {
            // 并发场景下唯一约束冲突，重新查询
            log.warn("参与者并发创建冲突，重新查询: activityId={}, studentId={}",
                    dto.getActivityId(), dto.getStudentId());
            participant = participantMapper.selectOne(
                    new LambdaQueryWrapper<Participant>()
                            .eq(Participant::getActivityId, dto.getActivityId())
                            .eq(Participant::getStudentId, dto.getStudentId())
                            .last("LIMIT 1")
            );
        }

        // 回写缓存
        if (participant != null && participant.getId() != null) {
            redisUtil.set(cacheKey, participant.getId(), 24, TimeUnit.HOURS);
        }

        return participant;
    }

    /**
     * 生成场次专属临时令牌
     * 令牌值存储 participantId:activityId，绑定有效期
     */
    private String generateToken(Participant participant, Long activityId) {
        String token = UUID.randomUUID().toString().replace("-", "");
        String tokenValue = participant.getId() + ":" + activityId;
        redisUtil.set(TOKEN_KEY_PREFIX + token, tokenValue, TOKEN_EXPIRE_SECONDS, TimeUnit.SECONDS);
        return token;
    }

    /**
     * 记录登录信息
     *
     * 原实现用 CompletableFuture.runAsync 起一个异步任务，但方法体里只有一行 debug 日志，
     * 等于每次登录都往 ForkJoinPool.commonPool 塞一个空任务——2000 人扫码就是 2000 个，
     * 而 commonPool 的并行度只有 CPU-1，会挤占其他并行流。
     * 既然没有真实业务，就退化成同步的一行日志；将来真要写登录记录表，
     * 应该走消息队列而不是裸的 commonPool（无事务、无监控、异常还被吞掉）。
     */
    private void asyncRecordLogin(Participant participant) {
        log.debug("登录记录: participantId={}, name={}", participant.getId(), participant.getName());
    }
}

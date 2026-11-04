package com.lucky.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.lucky.constant.RedisKeys;
import com.lucky.dto.JoinResultVO;
import com.lucky.entity.LotteryRound;
import com.lucky.entity.Participant;
import com.lucky.entity.Prize;
import com.lucky.entity.Winner;
import com.lucky.exception.BusinessException;
import com.lucky.mapper.LotteryRoundMapper;
import com.lucky.mapper.ParticipantMapper;
import com.lucky.mapper.PrizeMapper;
import com.lucky.mapper.WinnerMapper;
import com.lucky.mq.MessageProducer;
import com.lucky.service.SeckillService;
import com.lucky.websocket.LuckyWebSocketHandler;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.scripting.support.ResourceScriptSource;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * 抢奖核心实现（秒杀模型）
 *
 * 主链路刻意做得很短：
 *   读轮次元信息(Redis Hash) → 校验资格(Redis 缓存) → Lua 原子扣减 → 发 MQ → 立即返回
 *
 * 这条链路上没有一次数据库写操作，接口 RT 稳定在个位数毫秒。
 * 落库、库存对账、WebSocket 广播全部由 MQ 消费者异步完成，库存在 Redis 预扣，
 * 数据库只做最终兜底（UPDATE ... WHERE stock > 0 + 唯一索引）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SeckillServiceImpl implements SeckillService {

    private final StringRedisTemplate stringRedisTemplate;
    private final LotteryRoundMapper lotteryRoundMapper;
    private final PrizeMapper prizeMapper;
    private final ParticipantMapper participantMapper;
    private final WinnerMapper winnerMapper;
    private final MessageProducer messageProducer;
    private final LuckyWebSocketHandler webSocketHandler;
    private final io.micrometer.core.instrument.MeterRegistry meterRegistry;

    /** 轮次缓存有效期：2 小时，足够覆盖一场典礼 */
    private static final long CACHE_HOURS = 2;

    /** 参与者资格缓存有效期：5 分钟，管理员移除资格后最迟 5 分钟生效 */
    private static final long QUALIFICATION_CACHE_MINUTES = 5;

    private DefaultRedisScript<Long> seckillScript;
    private DefaultRedisScript<Long> rollbackScript;

    @PostConstruct
    public void init() {
        seckillScript = loadScript("lua/seckill.lua");
        rollbackScript = loadScript("lua/seckill_rollback.lua");
        log.info("抢奖 Lua 脚本加载完成");
    }

    private DefaultRedisScript<Long> loadScript(String path) {
        DefaultRedisScript<Long> script = new DefaultRedisScript<>();
        script.setScriptSource(new ResourceScriptSource(new ClassPathResource(path)));
        script.setResultType(Long.class);
        return script;
    }

    @Override
    public void preheat(Long roundId) {
        LotteryRound round = lotteryRoundMapper.selectById(roundId);
        if (round == null) {
            throw new BusinessException("轮次不存在");
        }
        Prize prize = prizeMapper.selectById(round.getPrizeId());
        if (prize == null) {
            throw new BusinessException("轮次绑定的奖品不存在");
        }

        int stock = prize.getStock() == null ? 0 : prize.getStock();
        int quota = Math.min(round.getWinnerCount() == null ? 0 : round.getWinnerCount(), stock);
        if (quota <= 0) {
            throw new BusinessException("奖品库存不足，无法开始本轮");
        }

        // 名额快照写入 Redis，后续所有并发都只跟这个值打交道
        stringRedisTemplate.opsForValue()
                .set(RedisKeys.roundStock(roundId), String.valueOf(quota), CACHE_HOURS, TimeUnit.HOURS);
        // 本轮去重集合必须是干净的
        stringRedisTemplate.delete(RedisKeys.roundUsers(roundId));

        Map<String, String> meta = new HashMap<>();
        meta.put("activityId", String.valueOf(round.getActivityId()));
        meta.put("prizeId", String.valueOf(round.getPrizeId()));
        meta.put("status", "1");
        meta.put("quota", String.valueOf(quota));
        meta.put("roundName", round.getRoundName() == null ? "" : round.getRoundName());
        meta.put("prizeName", prize.getName() == null ? "" : prize.getName());
        stringRedisTemplate.opsForHash().putAll(RedisKeys.roundMeta(roundId), meta);
        stringRedisTemplate.expire(RedisKeys.roundMeta(roundId), CACHE_HOURS, TimeUnit.HOURS);

        // 活动级中奖集合跨轮次复用，只续期不清理，否则「一人一活动只能中一次」会失效
        stringRedisTemplate.expire(RedisKeys.activityWinners(round.getActivityId()), 24, TimeUnit.HOURS);

        log.info("轮次预热完成: roundId={}, prizeId={}, 名额={}, 奖品库存={}",
                roundId, prize.getId(), quota, stock);
    }

    @Override
    public JoinResultVO join(Long roundId, Long participantId) {
        if (participantId == null) {
            return fail(-4, "缺少参与者身份");
        }

        // 1. 轮次元信息走 Redis，避免每次抢奖都打 DB
        Map<Object, Object> meta = stringRedisTemplate.opsForHash().entries(RedisKeys.roundMeta(roundId));
        if (meta.isEmpty()) {
            return fail(-3, "本轮未开始");
        }
        if (!"1".equals(String.valueOf(meta.get("status")))) {
            return fail(-3, "本轮已结束");
        }

        Long activityId = Long.valueOf(String.valueOf(meta.get("activityId")));
        Long prizeId = Long.valueOf(String.valueOf(meta.get("prizeId")));
        String prizeName = String.valueOf(meta.get("prizeName"));

        // 2. 资格校验（被移除抽奖资格、被移出直播间、跨活动的人都不能抢）
        if (!isEligible(participantId, activityId)) {
            return fail(-4, "你已被移除抽奖资格");
        }

        // 3. Lua 原子扣减：库存判断 + 两级去重 + DECR + 记录，一次完成
        Long result;
        try {
            result = executeSeckillScript(roundId, activityId, participantId);
        } catch (Exception e) {
            log.error("抢奖脚本执行异常: roundId={}, participantId={}", roundId, participantId, e);
            return fail(-5, "系统繁忙，请稍后重试");
        }

        int code = result == null ? -5 : result.intValue();
        switch (code) {
            case 1:
                break;
            case 0:
                tryFinishRound(roundId, activityId, "名额已抢完");
                return fail(0, "手慢了，名额已抢完");
            case -1:
                return fail(-1, "你本轮已抢到，请勿重复点击");
            case -2:
                return fail(-2, "你已在本活动中过奖");
            default:
                return fail(-3, "本轮未开始");
        }

        // 4. 投递 MQ，异步落库 + 广播
        Map<String, Object> message = new HashMap<>();
        message.put("messageId", UUID.randomUUID().toString().replace("-", ""));
        message.put("activityId", activityId);
        message.put("roundId", roundId);
        message.put("participantId", participantId);
        message.put("prizeId", prizeId);
        message.put("joinTime", System.currentTimeMillis());

        try {
            messageProducer.sendJoin(message);
        } catch (Exception e) {
            // 消息没发出去，必须把 Redis 预扣还回去，否则名额凭空少一个
            log.error("抢奖消息发送失败，执行回滚: roundId={}, participantId={}", roundId, participantId, e);
            rollback(roundId, participantId, activityId);
            return fail(-5, "系统繁忙，请重试");
        }

        Integer remaining = getRemaining(roundId);
        if (remaining != null && remaining <= 0) {
            tryFinishRound(roundId, activityId, "名额已抢完");
        }

        log.info("抢奖成功: roundId={}, participantId={}, 剩余名额={}", roundId, participantId, remaining);
        recordMetric(1);

        return JoinResultVO.builder()
                .code(1)
                .message("抢奖成功，正在为你分配奖品")
                .won(true)
                .roundId(roundId)
                .participantId(participantId)
                .prizeId(prizeId)
                .prizeName(prizeName)
                .remaining(remaining)
                .build();
    }

    @Override
    public void rollback(Long roundId, Long participantId, Long activityId) {
        try {
            executeRollbackScript(roundId, activityId, participantId);
            log.warn("抢奖已回滚: roundId={}, participantId={}", roundId, participantId);
        } catch (Exception e) {
            log.error("抢奖回滚失败，需人工介入: roundId={}, participantId={}", roundId, participantId, e);
        }
    }

    /**
     * 执行抢奖 Lua 脚本
     * 抽成 protected 方法是为了单测能 spy 覆盖返回值，不用去 mock Redis 的 varargs
     */
    protected Long executeSeckillScript(Long roundId, Long activityId, Long participantId) {
        return stringRedisTemplate.execute(
                seckillScript,
                List.of(RedisKeys.roundStock(roundId),
                        RedisKeys.roundUsers(roundId),
                        RedisKeys.activityWinners(activityId)),
                participantId.toString());
    }

    /**
     * 执行回滚 Lua 脚本
     */
    protected Long executeRollbackScript(Long roundId, Long activityId, Long participantId) {
        return stringRedisTemplate.execute(
                rollbackScript,
                List.of(RedisKeys.roundStock(roundId),
                        RedisKeys.roundUsers(roundId),
                        RedisKeys.activityWinners(activityId)),
                participantId.toString());
    }

    @Override
    public Integer getRemaining(Long roundId) {
        String value = stringRedisTemplate.opsForValue().get(RedisKeys.roundStock(roundId));
        if (value == null) {
            return null;
        }
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    @Override
    public Winner getResult(Long roundId, Long participantId) {
        return winnerMapper.selectOne(new LambdaQueryWrapper<Winner>()
                .eq(Winner::getRoundId, roundId)
                .eq(Winner::getParticipantId, participantId)
                .last("LIMIT 1"));
    }

    @Override
    public void clearRound(Long roundId, Long activityId) {
        stringRedisTemplate.delete(List.of(
                RedisKeys.roundStock(roundId),
                RedisKeys.roundUsers(roundId),
                RedisKeys.roundMeta(roundId),
                RedisKeys.roundFinishLock(roundId)
        ));
        if (activityId != null) {
            stringRedisTemplate.delete(RedisKeys.activityWinners(activityId));
        }
        log.info("轮次缓存已清理: roundId={}, activityId={}", roundId, activityId);
    }

    /**
     * 校验参与者是否有抽奖资格
     * Redis 缓存优先，避免每万次抢奖打一万次 DB。
     * 同时校验活动归属，防止拿 A 活动的令牌去抢 B 活动的奖。
     */
    private boolean isEligible(Long participantId, Long activityId) {
        String key = RedisKeys.participantQualification(participantId);
        String cached = stringRedisTemplate.opsForValue().get(key);
        if (cached != null) {
            return "1".equals(cached);
        }

        Participant p = participantMapper.selectById(participantId);
        boolean ok = p != null
                && activityId != null && activityId.equals(p.getActivityId())
                && p.getLotteryStatus() != null && p.getLotteryStatus() == 1
                && (p.getIsBanned() == null || p.getIsBanned() == 0);

        stringRedisTemplate.opsForValue()
                .set(key, ok ? "1" : "0", QUALIFICATION_CACHE_MINUTES, TimeUnit.MINUTES);
        return ok;
    }

    /**
     * 结束本轮
     * 用 SETNX 做一次性标记，避免「最后一个名额」引发的并发重复收尾
     */
    private void tryFinishRound(Long roundId, Long activityId, String reason) {
        Boolean locked = stringRedisTemplate.opsForValue()
                .setIfAbsent(RedisKeys.roundFinishLock(roundId), "1", 60, TimeUnit.SECONDS);
        if (!Boolean.TRUE.equals(locked)) {
            return;
        }

        LotteryRound update = new LotteryRound();
        update.setId(roundId);
        update.setStatus(2);
        update.setEndTime(LocalDateTime.now());
        lotteryRoundMapper.updateById(update);

        stringRedisTemplate.opsForHash().put(RedisKeys.roundMeta(roundId), "status", "2");

        Map<String, Object> payload = new HashMap<>();
        payload.put("roundId", roundId);
        payload.put("reason", reason);
        webSocketHandler.broadcastToActivity(activityId, "lottery_round_end", payload);

        log.info("轮次结束: roundId={}, reason={}", roundId, reason);
    }

    private JoinResultVO fail(int code, String message) {
        recordMetric(code);
        return JoinResultVO.builder()
                .code(code)
                .message(message)
                .won(false)
                .build();
    }

    /**
     * 记录抢奖结果指标
     *
     * 暴露到 /actuator/prometheus，用于观察成功/售罄/重复的比例，
     * 这比翻日志判断「今天抢得顺不顺」直观得多。
     */
    private void recordMetric(int code) {
        if (meterRegistry == null) {
            return;
        }
        String tag = switch (code) {
            case 1 -> "success";
            case 0 -> "sold_out";
            case -1 -> "duplicate";
            case -2 -> "already_won";
            case -3 -> "not_started";
            case -4 -> "no_qualification";
            default -> "error";
        };
        meterRegistry.counter("lucky.seckill.join", "result", tag).increment();
    }
}

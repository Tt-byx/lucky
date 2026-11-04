package com.lucky.service.impl;

import com.lucky.dto.JoinResultVO;
import com.lucky.mapper.LotteryRoundMapper;
import com.lucky.mapper.ParticipantMapper;
import com.lucky.mapper.PrizeMapper;
import com.lucky.mapper.WinnerMapper;
import com.lucky.mq.MessageProducer;
import com.lucky.websocket.LuckyWebSocketHandler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 抢奖服务单测
 *
 * 覆盖的是「业务分支是否走对」，不覆盖 Lua 脚本本身的原子性
 * （那部分靠真实 Redis 的集成压测验证）。
 */
@ExtendWith(MockitoExtension.class)
class SeckillServiceImplTest {

    @Mock
    private StringRedisTemplate stringRedisTemplate;
    @Mock
    private LotteryRoundMapper lotteryRoundMapper;
    @Mock
    private PrizeMapper prizeMapper;
    @Mock
    private ParticipantMapper participantMapper;
    @Mock
    private WinnerMapper winnerMapper;
    @Mock
    private MessageProducer messageProducer;
    @Mock
    private LuckyWebSocketHandler webSocketHandler;

    @Mock
    private HashOperations<String, Object, Object> hashOperations;
    @Mock
    private ValueOperations<String, String> valueOperations;

    private SeckillServiceImpl service;

    /** 真实的内存注册表，比 mock 更好用：断言指标数值也方便 */
    private final io.micrometer.core.instrument.simple.SimpleMeterRegistry meterRegistry =
            new io.micrometer.core.instrument.simple.SimpleMeterRegistry();

    private static final Long ROUND_ID = 1L;
    private static final Long ACTIVITY_ID = 10L;
    private static final Long PARTICIPANT_ID = 100L;

    @BeforeEach
    void setUp() {
        service = spy(new SeckillServiceImpl(
                stringRedisTemplate, lotteryRoundMapper, prizeMapper,
                participantMapper, winnerMapper, messageProducer, webSocketHandler, meterRegistry));
        service.init();

        lenient().when(stringRedisTemplate.opsForHash()).thenReturn(hashOperations);
        lenient().when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);
    }

    private void stubRoundMeta(String status) {
        Map<Object, Object> meta = new HashMap<>();
        meta.put("activityId", String.valueOf(ACTIVITY_ID));
        meta.put("prizeId", "20");
        meta.put("status", status);
        meta.put("prizeName", "测试奖品");
        when(hashOperations.entries(anyString())).thenReturn(meta);
    }

    private void stubQualified() {
        lenient().when(valueOperations.get(contains("participant:qual"))).thenReturn("1");
    }

    private void stubRemaining(String value) {
        lenient().when(valueOperations.get(contains("round:stock"))).thenReturn(value);
    }

    @Test
    @DisplayName("轮次未预热时拒绝抢奖")
    void rejectWhenRoundNotPreheated() {
        when(hashOperations.entries(anyString())).thenReturn(Collections.emptyMap());

        JoinResultVO result = service.join(ROUND_ID, PARTICIPANT_ID);

        assertEquals(-3, result.getCode());
        assertFalse(result.getWon());
    }

    @Test
    @DisplayName("轮次已结束时拒绝抢奖")
    void rejectWhenRoundFinished() {
        stubRoundMeta("2");

        JoinResultVO result = service.join(ROUND_ID, PARTICIPANT_ID);

        assertEquals(-3, result.getCode());
    }

    @Test
    @DisplayName("没有抽奖资格时拒绝抢奖")
    void rejectWhenNotQualified() {
        stubRoundMeta("1");
        when(valueOperations.get(contains("participant:qual"))).thenReturn("0");

        JoinResultVO result = service.join(ROUND_ID, PARTICIPANT_ID);

        assertEquals(-4, result.getCode());
    }

    @Test
    @DisplayName("名额抢完返回 0")
    void returnSoldOutWhenNoQuota() {
        stubRoundMeta("1");
        stubQualified();
        doReturn(0L).when(service).executeSeckillScript(ROUND_ID, ACTIVITY_ID, PARTICIPANT_ID);
        stubRemaining("0");

        JoinResultVO result = service.join(ROUND_ID, PARTICIPANT_ID);

        assertEquals(0, result.getCode());
        assertFalse(result.getWon());
    }

    @Test
    @DisplayName("重复点击返回 -1")
    void returnDuplicateWhenSameRound() {
        stubRoundMeta("1");
        stubQualified();
        doReturn(-1L).when(service).executeSeckillScript(ROUND_ID, ACTIVITY_ID, PARTICIPANT_ID);

        JoinResultVO result = service.join(ROUND_ID, PARTICIPANT_ID);

        assertEquals(-1, result.getCode());
    }

    @Test
    @DisplayName("本活动已中过奖返回 -2")
    void returnCrossRoundWhenAlreadyWon() {
        stubRoundMeta("1");
        stubQualified();
        doReturn(-2L).when(service).executeSeckillScript(ROUND_ID, ACTIVITY_ID, PARTICIPANT_ID);

        JoinResultVO result = service.join(ROUND_ID, PARTICIPANT_ID);

        assertEquals(-2, result.getCode());
    }

    @Test
    @DisplayName("抢奖成功后投递 MQ 并返回剩余名额")
    void sendMqWhenSuccess() {
        stubRoundMeta("1");
        stubQualified();
        doReturn(1L).when(service).executeSeckillScript(ROUND_ID, ACTIVITY_ID, PARTICIPANT_ID);
        stubRemaining("4");

        JoinResultVO result = service.join(ROUND_ID, PARTICIPANT_ID);

        assertEquals(1, result.getCode());
        assertTrue(result.getWon());
        assertEquals(4, result.getRemaining());
        assertEquals("测试奖品", result.getPrizeName());
        verify(messageProducer).sendJoin(anyMap());
    }

    @Test
    @DisplayName("MQ 发送失败时回滚预扣")
    void rollbackWhenMqFails() {
        stubRoundMeta("1");
        stubQualified();
        doReturn(1L).when(service).executeSeckillScript(ROUND_ID, ACTIVITY_ID, PARTICIPANT_ID);
        stubRemaining("4");
        doThrow(new RuntimeException("mq down")).when(messageProducer).sendJoin(anyMap());
        doReturn(1L).when(service).executeRollbackScript(ROUND_ID, ACTIVITY_ID, PARTICIPANT_ID);

        JoinResultVO result = service.join(ROUND_ID, PARTICIPANT_ID);

        assertEquals(-5, result.getCode());
        verify(service).executeRollbackScript(ROUND_ID, ACTIVITY_ID, PARTICIPANT_ID);
    }

    @Test
    @DisplayName("Lua 脚本异常时返回系统繁忙")
    void returnBusyWhenScriptFails() {
        stubRoundMeta("1");
        stubQualified();
        doThrow(new RuntimeException("redis down"))
                .when(service).executeSeckillScript(ROUND_ID, ACTIVITY_ID, PARTICIPANT_ID);

        JoinResultVO result = service.join(ROUND_ID, PARTICIPANT_ID);

        assertEquals(-5, result.getCode());
    }
}

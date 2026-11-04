package com.lucky.scheduler;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.lucky.entity.LotteryRound;
import com.lucky.entity.Prize;
import com.lucky.mapper.LotteryRoundMapper;
import com.lucky.mapper.PrizeMapper;
import com.lucky.mapper.WinnerMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 库存对账单测
 *
 * 对账的价值在于「发现」，不是「修复」，所以这里重点验证：
 * 一致时不误报、不一致时能报出来、有进行中轮次时跳过（避免在途请求被当成不一致）。
 */
@ExtendWith(MockitoExtension.class)
class SeckillReconcileSchedulerTest {

    @Mock
    private PrizeMapper prizeMapper;
    @Mock
    private LotteryRoundMapper lotteryRoundMapper;
    @Mock
    private WinnerMapper winnerMapper;

    private SimpleMeterRegistry meterRegistry;
    private SeckillReconcileScheduler scheduler;

    @BeforeEach
    void setUp() {
        meterRegistry = new SimpleMeterRegistry();
        scheduler = new SeckillReconcileScheduler(prizeMapper, lotteryRoundMapper, winnerMapper, meterRegistry);
    }

    private Prize prize(long id, int total, int stock) {
        Prize p = new Prize();
        p.setId(id);
        p.setName("奖品" + id);
        p.setTotalStock(total);
        p.setStock(stock);
        return p;
    }

    private LotteryRound round(long id, long prizeId, int status) {
        LotteryRound r = new LotteryRound();
        r.setId(id);
        r.setPrizeId(prizeId);
        r.setStatus(status);
        return r;
    }

    private double mismatchCount() {
        var counter = meterRegistry.find("lucky.reconcile.mismatch").counter();
        return counter == null ? 0 : counter.count();
    }

    @Test
    @DisplayName("库存与中奖记录一致时不告警")
    void noAlertWhenConsistent() {
        when(prizeMapper.selectList(any(Wrapper.class))).thenReturn(List.of(prize(1L, 10, 7)));
        when(lotteryRoundMapper.selectList(any(Wrapper.class)))
                .thenReturn(List.of(round(100L, 1L, 2)));
        when(winnerMapper.selectCount(any(Wrapper.class))).thenReturn(3L);

        scheduler.reconcile();

        assertEquals(0, mismatchCount());
    }

    @Test
    @DisplayName("已扣库存与中奖数不一致时告警")
    void alertWhenMismatch() {
        when(prizeMapper.selectList(any(Wrapper.class))).thenReturn(List.of(prize(1L, 10, 6)));
        when(lotteryRoundMapper.selectList(any(Wrapper.class)))
                .thenReturn(List.of(round(100L, 1L, 2)));
        when(winnerMapper.selectCount(any(Wrapper.class))).thenReturn(3L);

        scheduler.reconcile();

        assertEquals(1, mismatchCount());
    }

    @Test
    @DisplayName("有进行中的轮次时跳过对账")
    void skipWhenRoundRunning() {
        when(prizeMapper.selectList(any(Wrapper.class))).thenReturn(List.of(prize(1L, 10, 6)));
        when(lotteryRoundMapper.selectList(any(Wrapper.class)))
                .thenReturn(List.of(round(100L, 1L, 1)));

        scheduler.reconcile();

        assertEquals(0, mismatchCount());
    }

    @Test
    @DisplayName("奖品没有轮次时跳过")
    void skipWhenNoRound() {
        when(prizeMapper.selectList(any(Wrapper.class))).thenReturn(List.of(prize(1L, 10, 10)));
        when(lotteryRoundMapper.selectList(any(Wrapper.class))).thenReturn(List.of());

        scheduler.reconcile();

        assertEquals(0, mismatchCount());
    }

    @Test
    @DisplayName("读取奖品异常时任务不抛出")
    void swallowWhenQueryFails() {
        when(prizeMapper.selectList(any(Wrapper.class))).thenThrow(new RuntimeException("db down"));

        scheduler.reconcile();

        assertEquals(0, mismatchCount());
    }
}

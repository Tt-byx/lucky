package com.lucky.service.impl;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.lucky.dto.LotteryDTO;
import com.lucky.entity.LotteryRound;
import com.lucky.entity.Prize;
import com.lucky.exception.BusinessException;
import com.lucky.mapper.LotteryRoundMapper;
import com.lucky.mapper.ParticipantMapper;
import com.lucky.mapper.PrizeMapper;
import com.lucky.mapper.WinnerMapper;
import com.lucky.service.SeckillService;
import com.lucky.websocket.LuckyWebSocketHandler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 轮次创建的单测：名额与奖品库存的约束是这条链路的业务核心
 */
@ExtendWith(MockitoExtension.class)
class LotteryServiceImplTest {

    @Mock
    private SeckillService seckillService;
    @Mock
    private PrizeMapper prizeMapper;
    @Mock
    private WinnerMapper winnerMapper;
    @Mock
    private ParticipantMapper participantMapper;
    @Mock
    private LuckyWebSocketHandler webSocketHandler;
    @Mock
    private LotteryRoundMapper lotteryRoundMapper;

    private LotteryServiceImpl service;

    private static final Long ACTIVITY_ID = 10L;
    private static final Long PRIZE_ID = 20L;

    @BeforeEach
    void setUp() {
        service = spy(new LotteryServiceImpl(
                seckillService, prizeMapper, winnerMapper, participantMapper, webSocketHandler));
        lenient().doReturn(true).when(service).save(any(LotteryRound.class));
        lenient().doReturn(List.of()).when(service).list(any(Wrapper.class));
    }

    private Prize prize(int stock, long activityId) {
        Prize p = new Prize();
        p.setId(PRIZE_ID);
        p.setActivityId(activityId);
        p.setName("奖品");
        p.setStock(stock);
        p.setTotalStock(stock);
        return p;
    }

    private LotteryDTO dto(int winnerCount) {
        LotteryDTO dto = new LotteryDTO();
        dto.setActivityId(ACTIVITY_ID);
        dto.setPrizeId(PRIZE_ID);
        dto.setRoundName("一等奖");
        dto.setWinnerCount(winnerCount);
        return dto;
    }

    @Test
    @DisplayName("奖品不存在时拒绝创建")
    void rejectWhenPrizeMissing() {
        when(prizeMapper.selectById(PRIZE_ID)).thenReturn(null);

        assertThrows(BusinessException.class, () -> service.createRound(dto(1)));
        verify(service, never()).save(any(LotteryRound.class));
    }

    @Test
    @DisplayName("奖品不属于当前活动时拒绝创建")
    void rejectWhenPrizeBelongsToOtherActivity() {
        when(prizeMapper.selectById(PRIZE_ID)).thenReturn(prize(10, 999L));

        assertThrows(BusinessException.class, () -> service.createRound(dto(1)));
    }

    @Test
    @DisplayName("名额超过奖品库存时拒绝创建")
    void rejectWhenQuotaExceedsStock() {
        when(prizeMapper.selectById(PRIZE_ID)).thenReturn(prize(3, ACTIVITY_ID));

        BusinessException ex = assertThrows(BusinessException.class, () -> service.createRound(dto(5)));
        assertEquals("名额超出奖品库存，剩余可分配 3 个", ex.getMessage());
    }

    @Test
    @DisplayName("同一奖品多轮次时按累计名额校验库存")
    void rejectWhenAccumulatedQuotaExceedsStock() {
        when(prizeMapper.selectById(PRIZE_ID)).thenReturn(prize(5, ACTIVITY_ID));

        LotteryRound existing = new LotteryRound();
        existing.setId(1L);
        existing.setActivityId(ACTIVITY_ID);
        existing.setPrizeId(PRIZE_ID);
        existing.setWinnerCount(4);
        doReturn(List.of(existing)).when(service).list(any(Wrapper.class));

        BusinessException ex = assertThrows(BusinessException.class, () -> service.createRound(dto(2)));
        assertEquals("名额超出奖品库存，剩余可分配 1 个", ex.getMessage());
    }

    @Test
    @DisplayName("名额合法时创建成功且初始状态为待开始")
    void createRoundSuccessfully() {
        when(prizeMapper.selectById(PRIZE_ID)).thenReturn(prize(10, ACTIVITY_ID));

        LotteryRound round = service.createRound(dto(3));

        assertEquals(0, round.getStatus());
        assertEquals(PRIZE_ID, round.getPrizeId());
        assertEquals(3, round.getWinnerCount());
        verify(service).save(any(LotteryRound.class));
    }
}

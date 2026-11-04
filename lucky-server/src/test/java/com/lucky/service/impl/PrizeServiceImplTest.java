package com.lucky.service.impl;

import com.lucky.entity.Prize;
import com.lucky.mapper.PrizeMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * 奖品创建的单测
 *
 * 重点验证 totalStock 的兜底：库存回补条件是 stock < total_stock，
 * 如果 total_stock 落成 0，回补就永远不会生效。
 */
@ExtendWith(MockitoExtension.class)
class PrizeServiceImplTest {

    @Mock
    private PrizeMapper prizeMapper;

    private PrizeServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new PrizeServiceImpl();
        ReflectionTestUtils.setField(service, "baseMapper", prizeMapper);
        when(prizeMapper.insert(any(Prize.class))).thenReturn(1);
    }

    @Test
    @DisplayName("只传 stock 时自动补齐 totalStock")
    void fillTotalStockFromStock() {
        Prize prize = new Prize();
        prize.setName("无线耳机");
        prize.setStock(5);

        assertTrue(service.save(prize));
        assertEquals(5, prize.getTotalStock());
        assertEquals(5, prize.getStock());
    }

    @Test
    @DisplayName("只传 totalStock 时自动补齐 stock")
    void fillStockFromTotalStock() {
        Prize prize = new Prize();
        prize.setName("无线耳机");
        prize.setTotalStock(8);

        assertTrue(service.save(prize));
        assertEquals(8, prize.getStock());
    }

    @Test
    @DisplayName("两个都传时保持原值")
    void keepBothWhenProvided() {
        Prize prize = new Prize();
        prize.setName("无线耳机");
        prize.setTotalStock(10);
        prize.setStock(3);

        assertTrue(service.save(prize));
        assertEquals(10, prize.getTotalStock());
        assertEquals(3, prize.getStock());
    }
}

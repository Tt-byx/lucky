package com.lucky.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.lucky.entity.Prize;
import com.lucky.exception.BusinessException;
import com.lucky.mapper.PrizeMapper;
import com.lucky.service.PrizeService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 奖品服务实现
 */
@Slf4j
@Service
public class PrizeServiceImpl extends ServiceImpl<PrizeMapper, Prize>
        implements PrizeService {

    @Override
    public List<Prize> getByActivity(Long activityId) {
        return list(new LambdaQueryWrapper<Prize>()
                .eq(Prize::getActivityId, activityId)
                .orderByDesc(Prize::getCreatedAt));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean deductStock(Long prizeId, int quantity) {
        Prize prize = getById(prizeId);
        if (prize == null) {
            throw new BusinessException("奖品不存在");
        }

        if (prize.getStock() < quantity) {
            throw new BusinessException("奖品库存不足");
        }

        prize.setStock(prize.getStock() - quantity);
        return updateById(prize);
    }
}

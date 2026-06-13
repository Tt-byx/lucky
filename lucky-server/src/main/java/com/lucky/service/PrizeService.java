package com.lucky.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.lucky.entity.Prize;

import java.util.List;

/**
 * 奖品服务接口
 */
public interface PrizeService extends IService<Prize> {

    /**
     * 获取活动下的奖品列表
     */
    List<Prize> getByActivity(Long activityId);

    /**
     * 扣减库存
     */
    boolean deductStock(Long prizeId, int quantity);
}

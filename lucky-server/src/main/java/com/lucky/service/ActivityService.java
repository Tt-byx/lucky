package com.lucky.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.lucky.dto.CreateActivityDTO;
import com.lucky.entity.Activity;

import java.util.List;

/**
 * 活动服务接口
 */
public interface ActivityService extends IService<Activity> {

    /**
     * 创建活动
     */
    Activity createActivity(CreateActivityDTO dto);

    /**
     * 创建活动（简化版）
     */
    Activity createActivity(String name);

    /**
     * 获取当前活动
     */
    Activity getCurrentActivity();

    /**
     * 获取历史活动
     */
    List<Activity> getHistoryActivities();
}

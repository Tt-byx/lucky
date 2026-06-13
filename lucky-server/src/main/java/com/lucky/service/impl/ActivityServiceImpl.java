package com.lucky.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.lucky.dto.CreateActivityDTO;
import com.lucky.entity.Activity;
import com.lucky.enums.ActivityStatus;
import com.lucky.mapper.ActivityMapper;
import com.lucky.service.ActivityService;
import com.lucky.util.RedisUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * 活动服务实现
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ActivityServiceImpl extends ServiceImpl<ActivityMapper, Activity>
        implements ActivityService {

    private final RedisUtil redisUtil;

    /**
     * 当前活动缓存 Key
     */
    private static final String CURRENT_ACTIVITY_KEY = "lucky:activity:current";

    @Override
    public Activity createActivity(CreateActivityDTO dto) {
        Activity activity = new Activity();
        activity.setName(dto.getName());
        activity.setStatus(ActivityStatus.NOT_STARTED.getCode());
        activity.setStartTime(dto.getStartTime());
        activity.setEndTime(dto.getEndTime());
        save(activity);

        // 清除当前活动缓存
        redisUtil.delete(CURRENT_ACTIVITY_KEY);

        return activity;
    }

    @Override
    public Activity createActivity(String name) {
        Activity activity = new Activity();
        activity.setName(name);
        activity.setStatus(ActivityStatus.NOT_STARTED.getCode());
        save(activity);

        // 清除当前活动缓存
        redisUtil.delete(CURRENT_ACTIVITY_KEY);

        return activity;
    }

    @Override
    public Activity getCurrentActivity() {
        // 先从缓存获取
        Object cached = redisUtil.get(CURRENT_ACTIVITY_KEY);
        if (cached != null && cached instanceof Activity) {
            log.debug("从缓存获取当前活动");
            return (Activity) cached;
        }

        // 缓存未命中，从数据库查询
        Activity activity = getOne(new LambdaQueryWrapper<Activity>()
                .in(Activity::getStatus, ActivityStatus.NOT_STARTED.getCode(), ActivityStatus.IN_PROGRESS.getCode())
                .orderByDesc(Activity::getCreatedAt)
                .last("LIMIT 1"));

        // 写入缓存（5分钟过期）
        if (activity != null) {
            redisUtil.set(CURRENT_ACTIVITY_KEY, activity, 5, TimeUnit.MINUTES);
        }

        return activity;
    }

    @Override
    public List<Activity> getHistoryActivities() {
        return list(new LambdaQueryWrapper<Activity>()
                .eq(Activity::getStatus, ActivityStatus.ENDED.getCode())
                .orderByDesc(Activity::getCreatedAt));
    }
}

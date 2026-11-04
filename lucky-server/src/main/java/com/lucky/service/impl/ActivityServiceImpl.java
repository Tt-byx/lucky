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
        // 时间窗必须自洽，否则定时任务推进状态时会出现「已开始但还没到点」的矛盾状态
        if (dto.getStartTime() != null && dto.getEndTime() != null
                && dto.getStartTime().isAfter(dto.getEndTime())) {
            throw new com.lucky.exception.BusinessException("开始时间不能晚于结束时间");
        }

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

    /**
     * 更新活动后清除「当前活动」缓存。
     * 原实现仅在创建时清除缓存，导致「更新状态 / 删除活动」后，
     * GET /api/activity/current 仍会在最长 5 分钟内返回已被删除或已结束的活动（脏读）。
     */
    @Override
    public boolean updateById(Activity entity) {
        boolean result = super.updateById(entity);
        redisUtil.delete(CURRENT_ACTIVITY_KEY);
        return result;
    }

    /**
     * 删除活动后清除「当前活动」缓存
     */
    @Override
    public boolean removeById(java.io.Serializable id) {
        boolean result = super.removeById(id);
        redisUtil.delete(CURRENT_ACTIVITY_KEY);
        return result;
    }
}

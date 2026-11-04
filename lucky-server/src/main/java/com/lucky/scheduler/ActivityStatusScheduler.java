package com.lucky.scheduler;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.lucky.entity.Activity;
import com.lucky.service.ActivityService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 活动状态自动推进
 *
 * 初版的状态是双轨制：管理员手动改 status，抽奖时又校验 startTime/endTime。
 * 两条轨不同步就会出现「后台显示进行中、开轮却提示活动已结束」。
 *
 * 这里每 30 秒按时间窗把状态推到位，管理员手动改的那条轨仍然保留，
 * 但不会再停留在错误状态上。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ActivityStatusScheduler {

    private static final int NOT_STARTED = 0;
    private static final int IN_PROGRESS = 1;
    private static final int ENDED = 2;

    private final ActivityService activityService;

    @Scheduled(fixedDelay = 30000, initialDelay = 15000)
    public void syncActivityStatus() {
        LocalDateTime now = LocalDateTime.now();

        List<Activity> candidates = activityService.list(new LambdaQueryWrapper<Activity>()
                .in(Activity::getStatus, NOT_STARTED, IN_PROGRESS));

        for (Activity activity : candidates) {
            Integer target = null;
            String reason = null;

            if (activity.getStatus() == NOT_STARTED
                    && activity.getStartTime() != null
                    && !now.isBefore(activity.getStartTime())) {
                target = IN_PROGRESS;
                reason = "到达开始时间";
            } else if (activity.getStatus() == IN_PROGRESS
                    && activity.getEndTime() != null
                    && now.isAfter(activity.getEndTime())) {
                target = ENDED;
                reason = "超过结束时间";
            }

            if (target == null) {
                continue;
            }

            Activity update = new Activity();
            update.setId(activity.getId());
            update.setStatus(target);
            // updateById 内部会清「当前活动」缓存，避免读到旧状态
            activityService.updateById(update);

            log.info("活动状态自动推进: id={}, {} -> {}, 原因={}",
                    activity.getId(), activity.getStatus(), target, reason);
        }
    }
}

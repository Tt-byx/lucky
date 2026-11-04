package com.lucky.scheduler;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.lucky.entity.Activity;
import com.lucky.service.ActivityService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 活动状态自动推进的单测
 *
 * 这条逻辑是为了消除「status 手动改」和「时间窗校验」两条轨不一致的问题，
 * 边界判断必须准，否则会出现活动已经开始却开不了轮。
 */
@ExtendWith(MockitoExtension.class)
class ActivityStatusSchedulerTest {

    @Mock
    private ActivityService activityService;

    @InjectMocks
    private ActivityStatusScheduler scheduler;

    private Activity activity(int status, LocalDateTime start, LocalDateTime end) {
        Activity a = new Activity();
        a.setId(1L);
        a.setStatus(status);
        a.setStartTime(start);
        a.setEndTime(end);
        return a;
    }

    @Test
    @DisplayName("到达开始时间且未开始时，自动改为进行中")
    void promoteToInProgress() {
        Activity a = activity(0, LocalDateTime.now().minusMinutes(1), LocalDateTime.now().plusHours(1));
        when(activityService.list(any(Wrapper.class))).thenReturn(List.of(a));

        scheduler.syncActivityStatus();

        ArgumentCaptor<Activity> captor = ArgumentCaptor.forClass(Activity.class);
        verify(activityService).updateById(captor.capture());
        assertEquals(1, captor.getValue().getStatus());
    }

    @Test
    @DisplayName("超过结束时间且进行中时，自动改为已结束")
    void promoteToEnded() {
        Activity a = activity(1, LocalDateTime.now().minusHours(2), LocalDateTime.now().minusMinutes(1));
        when(activityService.list(any(Wrapper.class))).thenReturn(List.of(a));

        scheduler.syncActivityStatus();

        ArgumentCaptor<Activity> captor = ArgumentCaptor.forClass(Activity.class);
        verify(activityService).updateById(captor.capture());
        assertEquals(2, captor.getValue().getStatus());
    }

    @Test
    @DisplayName("未到开始时间时保持原状态")
    void keepWhenNotStarted() {
        Activity a = activity(0, LocalDateTime.now().plusHours(1), LocalDateTime.now().plusHours(2));
        when(activityService.list(any(Wrapper.class))).thenReturn(List.of(a));

        scheduler.syncActivityStatus();

        verify(activityService, never()).updateById(any(Activity.class));
    }

    @Test
    @DisplayName("没有时间窗配置时不改动状态")
    void keepWhenTimeMissing() {
        Activity a = activity(0, null, null);
        when(activityService.list(any(Wrapper.class))).thenReturn(List.of(a));

        scheduler.syncActivityStatus();

        verify(activityService, never()).updateById(any(Activity.class));
    }
}

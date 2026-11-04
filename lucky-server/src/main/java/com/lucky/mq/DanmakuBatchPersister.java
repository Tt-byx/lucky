package com.lucky.mq;

import com.lucky.entity.Danmaku;
import com.lucky.mapper.DanmakuMapper;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * 弹幕批量入库组件
 *
 * 异步批量写入弹幕记录，降低高频弹幕场景下的数据库 IO 压力
 * 缓冲策略：每 100ms 刷新一次，或缓冲数量达到 100 条时立即刷新
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DanmakuBatchPersister {

    private final DanmakuMapper danmakuMapper;

    /** 弹幕缓冲队列 */
    private final ConcurrentLinkedQueue<Danmaku> buffer = new ConcurrentLinkedQueue<>();

    /** 批量刷新阈值 */
    private static final int BATCH_SIZE = 100;

    /**
     * 提交弹幕到批量缓冲
     */
    public void submit(Danmaku danmaku) {
        if (danmaku.getCreatedAt() == null) {
            danmaku.setCreatedAt(LocalDateTime.now());
        }
        buffer.offer(danmaku);

        // 达到阈值立即刷新
        if (buffer.size() >= BATCH_SIZE) {
            flush();
        }
    }

    /**
     * 定时刷新缓冲（每 100ms）
     */
    @Scheduled(fixedRate = 100)
    public void scheduledFlush() {
        if (!buffer.isEmpty()) {
            flush();
        }
    }

    /**
     * 执行批量入库
     */
    private void flush() {
        List<Danmaku> batch = new ArrayList<>(BATCH_SIZE);
        Danmaku item;
        while ((item = buffer.poll()) != null && batch.size() < BATCH_SIZE) {
            batch.add(item);
        }

        if (batch.isEmpty()) return;

        try {
            // 一条 INSERT 多个 VALUES，一次网络往返完成
            danmakuMapper.insertBatch(batch);
            log.debug("弹幕批量入库完成: {} 条", batch.size());
        } catch (Exception e) {
            log.error("弹幕批量入库失败: {} 条, 错误: {}", batch.size(), e.getMessage(), e);
            // 失败时重新入队（有限重试，避免无限循环）
            for (Danmaku danmaku : batch) {
                if (danmaku.getRetryCount() == null) {
                    danmaku.setRetryCount(0);
                }
                if (danmaku.getRetryCount() < 3) {
                    danmaku.setRetryCount(danmaku.getRetryCount() + 1);
                    buffer.offer(danmaku);
                } else {
                    log.error("弹幕入库重试超限，丢弃: id={}, content={}",
                            danmaku.getId(), danmaku.getContent());
                }
            }
        }
    }

    @PreDestroy
    public void destroy() {
        // 应用关闭时刷新剩余缓冲
        if (!buffer.isEmpty()) {
            flush();
            log.info("应用关闭，弹幕批量入库缓冲已刷新");
        }
    }
}

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

    /**
     * 待入库计数
     *
     * 不用 ConcurrentLinkedQueue.size() 判断阈值：它的实现是 O(n) 遍历整个队列，
     * 弹幕是最高频写路径，每条都扫一遍队列会把 CPU 白白烧掉。
     */
    private final java.util.concurrent.atomic.AtomicInteger pending =
            new java.util.concurrent.atomic.AtomicInteger(0);

    /**
     * flush 互斥标记
     *
     * scheduledFlush 与 submit 都可能触发 flush，两个线程并发 poll 会把批量效果打散，
     * 甚至同一个 batch 被拆成两条 INSERT。
     */
    private final java.util.concurrent.atomic.AtomicBoolean flushing =
            new java.util.concurrent.atomic.AtomicBoolean(false);

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
        pending.incrementAndGet();

        // 达到阈值立即刷新
        if (pending.get() >= BATCH_SIZE) {
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
        // 同一时刻只允许一个线程在刷
        if (!flushing.compareAndSet(false, true)) {
            return;
        }
        try {
            List<Danmaku> batch = new ArrayList<>(BATCH_SIZE);
            Danmaku item;
            while ((item = buffer.poll()) != null && batch.size() < BATCH_SIZE) {
                batch.add(item);
            }

            if (batch.isEmpty()) return;
            pending.addAndGet(-batch.size());

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
                        pending.incrementAndGet();
                    } else {
                        log.error("弹幕入库重试超限，丢弃: content={}", danmaku.getContent());
                    }
                }
            }
        } finally {
            flushing.set(false);
        }
    }

    @PreDestroy
    public void destroy() {
        // 关闭时必须把缓冲清空。
        // 原实现只调一次 flush()，而 flush 每次最多取 BATCH_SIZE=100 条，
        // 缓冲里如果还有 250 条就会丢掉 150 条。这里循环刷到空为止。
        for (int i = 0; i < 10 && !buffer.isEmpty(); i++) {
            flush();
        }
        if (!buffer.isEmpty()) {
            log.warn("应用关闭时仍有 {} 条弹幕未能入库", buffer.size());
        } else {
            log.info("应用关闭，弹幕批量入库缓冲已清空");
        }
    }
}

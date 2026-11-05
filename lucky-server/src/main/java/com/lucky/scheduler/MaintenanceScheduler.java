package com.lucky.scheduler;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.lucky.constant.RedisKeys;
import com.lucky.entity.Danmaku;
import com.lucky.entity.ScreenConfig;
import com.lucky.mapper.DanmakuMapper;
import com.lucky.mapper.ScreenConfigMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

/**
 * 运维清理调度器
 *
 * 处理三类「运行时慢慢积累」的问题：
 * 1. O-01 弹幕表膨胀：30 天前的弹幕自动清理
 * 2. O-03 上传目录容量：超过阈值打警告（人工处理）
 * 3. O-09 孤儿文件：上传后 DB 保存失败留下的无引用文件
 *
 * 与 ActivityStatusScheduler / SeckillReconcileScheduler 一样：
 * 跨实例任务，用 Redisson 分布式锁保证只跑一份。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MaintenanceScheduler {

    private static final String LOCK_NAME = "maintenance";

    /** 弹幕保留天数 */
    private static final int DANMAKU_KEEP_DAYS = 30;

    /** 上传目录大小告警阈值（MB） */
    private static final int UPLOAD_WARN_MB = 500;

    private final DanmakuMapper danmakuMapper;
    private final ScreenConfigMapper screenConfigMapper;
    private final RedissonClient redissonClient;

    @Value("${lucky.upload-dir:/app/uploads}")
    private String uploadDir;

    /**
     * 每天凌晨 3:30 执行日常清理
     */
    @Scheduled(cron = "0 30 3 * * ?")
    public void dailyMaintenance() {
        RLock lock = redissonClient.getLock(RedisKeys.schedulerLock(LOCK_NAME));
        if (!lock.tryLock()) {
            return;
        }
        try {
            cleanOldDanmakus();
            checkUploadCapacity();
            cleanOrphanFiles();
        } finally {
            lock.unlock();
        }
    }

    /**
     * O-01：清理 30 天前的弹幕
     *
     * 一场毕业典礼的弹幕在活动结束后就没有保留价值了（30 天足够事后追溯）。
     * 分批删除避免长事务锁表。
     */
    private void cleanOldDanmakus() {
        LocalDateTime cutoff = LocalDateTime.now().minusDays(DANMAKU_KEEP_DAYS);
        int totalDeleted = 0;
        int batchSize = 1000;

        while (true) {
            // 查一批要删的 id
            List<Danmaku> batch = danmakuMapper.selectList(
                    new LambdaQueryWrapper<Danmaku>()
                            .lt(Danmaku::getCreatedAt, cutoff)
                            .last("LIMIT " + batchSize));
            if (batch.isEmpty()) {
                break;
            }

            List<Long> ids = batch.stream().map(Danmaku::getId).toList();
            danmakuMapper.deleteBatchIds(ids);
            totalDeleted += ids.size();

            if (batch.size() < batchSize) {
                break;
            }
        }

        if (totalDeleted > 0) {
            log.info("弹幕清理完成: 删除 {} 条（{} 天前）", totalDeleted, DANMAKU_KEEP_DAYS);
        }
    }

    /**
     * O-03：检查上传目录容量
     *
     * 不自动删文件（背景图正在用的不能删），只在超阈值时打 WARN 让人工处理。
     */
    private void checkUploadCapacity() {
        try {
            Path dir = Paths.get(uploadDir);
            if (!Files.isDirectory(dir)) {
                return;
            }

            long totalBytes;
            try (Stream<Path> walk = Files.walk(dir)) {
                totalBytes = walk
                        .filter(Files::isRegularFile)
                        .mapToLong(p -> {
                            try {
                                return Files.size(p);
                            } catch (Exception e) {
                                return 0;
                            }
                        })
                        .sum();
            }

            long totalMB = totalBytes / (1024 * 1024);
            if (totalMB > UPLOAD_WARN_MB) {
                log.warn("上传目录容量超过 {} MB（当前 {} MB），请清理不需要的文件: {}",
                        UPLOAD_WARN_MB, totalMB, uploadDir);
            }
        } catch (Exception e) {
            log.warn("上传目录容量检查失败: {}", e.getMessage());
        }
    }

    /**
     * O-09：清理孤儿文件（上传了但 DB 没记录的）
     *
     * 判断逻辑：uploads 目录下的文件名，如果不在 screen_config 表的
     * background_url / mobile_background_url 里，就是孤儿。
     *
     * 注意：只清理图片/视频文件（白名单扩展名），其他文件不动。
     */
    private void cleanOrphanFiles() {
        try {
            Path dir = Paths.get(uploadDir);
            if (!Files.isDirectory(dir)) {
                return;
            }

            // 1. 从 DB 收集所有被引用的文件名
            Set<String> referenced = new HashSet<>();
            List<ScreenConfig> configs = screenConfigMapper.selectList(null);
            for (ScreenConfig config : configs) {
                addFileName(referenced, config.getBackgroundUrl());
                addFileName(referenced, config.getMobileBackgroundUrl());
            }

            // 2. 扫描目录，找孤儿文件
            // 只处理白名单扩展名（其他文件可能不是我们管理的）
            Set<String> allowedExt = Set.of(".jpg", ".jpeg", ".png", ".gif", ".webp", ".mp4", ".webm", ".ogg");
            int orphanCount = 0;

            try (Stream<Path> files = Files.list(dir)) {
                for (Path file : files.toList()) {
                    if (!Files.isRegularFile(file)) {
                        continue;
                    }
                    String fileName = file.getFileName().toString();
                    String lower = fileName.toLowerCase();
                    boolean isManaged = allowedExt.stream().anyMatch(lower::endsWith);
                    if (!isManaged) {
                        continue;
                    }

                    if (!referenced.contains(fileName)) {
                        // 孤儿文件：删除（这些文件要么是上传失败留下的，要么是旧背景被替换后没清理）
                        Files.deleteIfExists(file);
                        orphanCount++;
                    }
                }
            }

            if (orphanCount > 0) {
                log.info("孤儿文件清理完成: 删除 {} 个文件", orphanCount);
            }
        } catch (Exception e) {
            log.warn("孤儿文件清理失败: {}", e.getMessage());
        }
    }

    private void addFileName(Set<String> set, String url) {
        if (url == null || url.isEmpty()) {
            return;
        }
        // URL 形如 /uploads/xxx.png 或 https://bucket.oss.../lucky/background/xxx.png
        int lastSlash = url.lastIndexOf('/');
        if (lastSlash >= 0 && lastSlash < url.length() - 1) {
            set.add(url.substring(lastSlash + 1));
        }
    }
}

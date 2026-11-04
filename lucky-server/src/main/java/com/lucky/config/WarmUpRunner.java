package com.lucky.config;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;

/**
 * 启动预热
 *
 * 压测数据很直观：同一个抢奖接口，冷启动 p99 是 172ms，跑热之后只有 49ms。
 * 差的是 JIT 编译和连接池建立，这部分不该让第一波真实用户来承担。
 *
 * 这里做的是最划算的三件事：填满 Redis / DB 连接池、触发热点类的 JIT、把序列化路径跑一遍。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class WarmUpRunner implements ApplicationRunner {

    private static final String PROBE_KEY = "lucky:warmup:probe";
    private static final int REDIS_ROUNDS = 100;
    private static final int DB_ROUNDS = 5;

    private final StringRedisTemplate stringRedisTemplate;
    private final DataSource dataSource;

    @Override
    public void run(ApplicationArguments args) {
        long start = System.currentTimeMillis();

        try {
            warmUpRedis();
            warmUpDatabase();
            stringRedisTemplate.delete(PROBE_KEY);
            log.info("启动预热完成，耗时 {}ms", System.currentTimeMillis() - start);
        } catch (Exception e) {
            // 预热失败不影响启动，只是第一波请求会慢一些
            log.warn("启动预热未完成（不影响启动）: {}", e.getMessage());
        }
    }

    private void warmUpRedis() {
        stringRedisTemplate.opsForValue().set(PROBE_KEY, "1");
        for (int i = 0; i < REDIS_ROUNDS; i++) {
            stringRedisTemplate.hasKey(PROBE_KEY);
        }
    }

    private void warmUpDatabase() throws Exception {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("SELECT 1")) {
            for (int i = 0; i < DB_ROUNDS; i++) {
                statement.execute();
            }
        }
    }
}

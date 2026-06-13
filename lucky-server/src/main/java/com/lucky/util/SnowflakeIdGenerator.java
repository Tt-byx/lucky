package com.lucky.util;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.InetAddress;
import java.net.UnknownHostException;

/**
 * 雪花算法分布式ID生成器
 *
 * 结构：0 - 0000000000 0000000000 0000000000 0000000000 0 - 00000 - 00000 - 000000000000
 *      1位符号位 | 41位时间戳 | 5位数据中心ID | 5位工作机器ID | 12位序列号
 */
@Slf4j
@Component
public class SnowflakeIdGenerator {

    /**
     * 起始时间戳 (2024-01-01 00:00:00)
     */
    private final long startTimestamp = 1704067200000L;

    /**
     * 数据中心ID位数
     */
    private final long datacenterIdBits = 5L;

    /**
     * 工作机器ID位数
     */
    private final long workerIdBits = 5L;

    /**
     * 序列号位数
     */
    private final long sequenceBits = 12L;

    /**
     * 数据中心ID最大值 (31)
     */
    private final long maxDatacenterId = ~(-1L << datacenterIdBits);

    /**
     * 工作机器ID最大值 (31)
     */
    private final long maxWorkerId = ~(-1L << workerIdBits);

    /**
     * 序列号最大值 (4095)
     */
    private final long sequenceMask = ~(-1L << sequenceBits);

    /**
     * 工作机器ID左移位数 (12)
     */
    private final long workerIdShift = sequenceBits;

    /**
     * 数据中心ID左移位数 (17)
     */
    private final long datacenterIdShift = sequenceBits + workerIdBits;

    /**
     * 时间戳左移位数 (22)
     */
    private final long timestampLeftShift = sequenceBits + workerIdBits + datacenterIdBits;

    /**
     * 数据中心ID
     */
    private long datacenterId;

    /**
     * 工作机器ID
     */
    private long workerId;

    /**
     * 序列号
     */
    private long sequence = 0L;

    /**
     * 上次生成ID的时间戳
     */
    private long lastTimestamp = -1L;

    /**
     * 构造方法（自动获取机器ID）
     */
    public SnowflakeIdGenerator(
            @Value("${lucky.snowflake.datacenter-id:1}") long datacenterId,
            @Value("${lucky.snowflake.worker-id:1}") long workerId) {
        if (datacenterId > maxDatacenterId || datacenterId < 0) {
            throw new IllegalArgumentException("数据中心ID不能大于 " + maxDatacenterId + " 或小于 0");
        }
        if (workerId > maxWorkerId || workerId < 0) {
            throw new IllegalArgumentException("工作机器ID不能大于 " + maxWorkerId + " 或小于 0");
        }
        this.datacenterId = datacenterId;
        this.workerId = workerId;
        log.info("雪花算法ID生成器初始化: datacenterId={}, workerId={}", datacenterId, workerId);
    }

    /**
     * 生成下一个ID
     */
    public synchronized long nextId() {
        long timestamp = System.currentTimeMillis();

        // 如果当前时间小于上一次生成ID的时间，说明时钟回拨，抛出异常
        if (timestamp < lastTimestamp) {
            throw new RuntimeException("时钟回拨，拒绝生成ID，回拨时间: " + (lastTimestamp - timestamp) + "ms");
        }

        // 如果是同一时间生成的，则进行毫秒内序列
        if (lastTimestamp == timestamp) {
            sequence = (sequence + 1) & sequenceMask;
            // 毫秒内序列溢出
            if (sequence == 0) {
                // 阻塞到下一个毫秒，获得新的时间戳
                timestamp = tilNextMillis(lastTimestamp);
            }
        } else {
            // 时间戳改变，毫秒内序列重置
            sequence = 0L;
        }

        lastTimestamp = timestamp;

        // 移位并通过或运算拼到一起组成64位的ID
        return ((timestamp - startTimestamp) << timestampLeftShift)
                | (datacenterId << datacenterIdShift)
                | (workerId << workerIdShift)
                | sequence;
    }

    /**
     * 阻塞到下一个毫秒
     */
    private long tilNextMillis(long lastTimestamp) {
        long timestamp = System.currentTimeMillis();
        while (timestamp <= lastTimestamp) {
            timestamp = System.currentTimeMillis();
        }
        return timestamp;
    }

    /**
     * 生成字符串类型的ID
     */
    public String nextIdStr() {
        return String.valueOf(nextId());
    }
}

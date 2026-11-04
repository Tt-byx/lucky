package com.lucky;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@MapperScan("com.lucky.mapper")
@EnableScheduling
@EnableAsync
public class LuckyApplication {

    public static void main(String[] args) {
        SpringApplication.run(LuckyApplication.class, args);
    }
}

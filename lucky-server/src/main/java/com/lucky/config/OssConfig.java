package com.lucky.config;

import com.aliyun.oss.OSS;
import com.aliyun.oss.OSSClientBuilder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 阿里云 OSS 配置
 * 仅在 access-key-id 和 access-key-secret 均非空时才创建 OSS 客户端，
 * 避免无凭证环境下启动失败。OSS 为可选功能，不配置时系统仍可正常运行。
 */
@Configuration
public class OssConfig {

    @Value("${alibaba.oss.endpoint:}")
    private String endpoint;

    @Value("${alibaba.oss.access-key-id:}")
    private String accessKeyId;

    @Value("${alibaba.oss.access-key-secret:}")
    private String accessKeySecret;

    @Bean
    @ConditionalOnExpression("!'${alibaba.oss.access-key-id:}'.isEmpty() && !'${alibaba.oss.access-key-secret:}'.isEmpty()")
    public OSS ossClient() {
        return new OSSClientBuilder().build(endpoint, accessKeyId, accessKeySecret);
    }
}

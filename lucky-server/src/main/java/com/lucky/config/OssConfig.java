package com.lucky.config;

import com.aliyun.oss.OSS;
import com.aliyun.oss.OSSClientBuilder;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 阿里云 OSS 配置
 * 仅在 access-key-id 和 access-key-secret 均非空时才创建 OSS 客户端，
 * 避免无凭证环境下启动失败。OSS 为可选功能，不配置时系统仍可正常运行。
 *
 * 凭据来源优先级：系统环境变量 > .env 文件（Docker Compose 注入）。
 * 生产环境务必通过部署平台的环境变量/密钥管理服务注入，不要把真实 AK/SK 写进 .env 后留在磁盘上。
 */
@Slf4j
@Configuration
public class OssConfig {

    @Value("${alibaba.oss.endpoint:}")
    private String endpoint;

    @Value("${alibaba.oss.access-key-id:}")
    private String accessKeyId;

    @Value("${alibaba.oss.access-key-secret:}")
    private String accessKeySecret;

    @PostConstruct
    public void checkCredentials() {
        if (accessKeyId == null || accessKeyId.isEmpty()) {
            log.info("OSS 未配置（access-key-id 为空），文件上传将降级为本地存储");
            return;
        }

        // 安全提示：凭据看起来像占位符时警告
        if (accessKeyId.contains("change-me") || accessKeyId.contains("your-")
                || accessKeyId.equals("LTAI5t") || accessKeyId.length() < 10) {
            log.warn("OSS access-key-id 看起来是占位符或测试值，请确认已替换为真实凭据");
        }

        // 安全提示：AK/SK 不应硬编码在代码或配置文件里
        if (accessKeySecret != null && accessKeySecret.contains("change-me")) {
            log.warn("OSS access-key-secret 看起来是占位符，请替换为真实凭据");
        }

        log.info("OSS 已配置: endpoint={}, bucket={}", endpoint, "${alibaba.oss.bucket-name:}");
    }

    @Bean
    @ConditionalOnExpression("!'${alibaba.oss.access-key-id:}'.isEmpty() && !'${alibaba.oss.access-key-secret:}'.isEmpty()")
    public OSS ossClient() {
        return new OSSClientBuilder().build(endpoint, accessKeyId, accessKeySecret);
    }
}

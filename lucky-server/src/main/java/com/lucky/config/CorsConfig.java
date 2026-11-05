package com.lucky.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import org.springframework.web.filter.CorsFilter;

import java.util.List;

/**
 * CORS 跨域配置
 *
 * 安全约束：
 * 1. 不能用 addAllowedOriginPattern("*") + setAllowCredentials(true)
 *    —— 这个组合允许任何网站带凭据发跨域请求，攻击者搭个恶意页就能冒充管理员调 API。
 * 2. 只放行本地开发端口 + 部署域名（从环境变量注入）。
 * 3. 不允许凭据（JWT 放 Authorization 头，不需要 Cookie 凭据）。
 */
@Configuration
public class CorsConfig {

    /**
     * 允许的来源，逗号分隔。
     * 开发默认值覆盖三端本地端口；生产通过 CORS_ALLOWED_ORIGINS 环境变量覆盖。
     */
    @Value("${lucky.cors.allowed-origins:http://localhost:5173,http://localhost:5174,http://localhost:5175,http://localhost}")
    private String allowedOrigins;

    @Bean
    public CorsFilter corsFilter() {
        CorsConfiguration config = new CorsConfiguration();

        // 显式列出允许的来源（不使用 *，避免任意网站跨域调用）
        for (String origin : allowedOrigins.split(",")) {
            String trimmed = origin.trim();
            if (!trimmed.isEmpty()) {
                config.addAllowedOrigin(trimmed);
            }
        }

        config.addAllowedHeader("*");
        config.addAllowedMethod("GET");
        config.addAllowedMethod("POST");
        config.addAllowedMethod("PUT");
        config.addAllowedMethod("DELETE");
        config.addAllowedMethod("OPTIONS");

        // 不允许凭据：JWT 在 Authorization 头里传，不需要 Cookie
        // 如果设 true 配合 * 来源，就是标准的 CSRF 攻击面
        config.setAllowCredentials(false);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);
        return new CorsFilter(source);
    }
}

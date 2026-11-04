package com.lucky.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import org.springframework.web.filter.CorsFilter;

@Configuration
public class CorsConfig {

    @Bean
    public CorsFilter corsFilter() {
        CorsConfiguration config = new CorsConfiguration();
        config.addAllowedOriginPattern("*");   // 允许所有来源的请求
        config.addAllowedHeader("*");          // 允许所有请求头
        config.addAllowedMethod("*");          // 允许所有 HTTP 方法（GET/POST/PUT/DELETE）
        config.setAllowCredentials(true);      // 允许携带 Cookie

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);  // 对所有路径生效
        return new CorsFilter(source);
    }
}

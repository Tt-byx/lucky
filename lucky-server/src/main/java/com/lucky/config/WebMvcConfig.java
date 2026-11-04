package com.lucky.config;

import com.lucky.util.FileUploadUtil;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Web 静态资源配置
 *
 * 背景图上传在 OSS 不可用时会降级到本地 uploads/ 目录，
 * 这里把 /uploads/** 映射过去，前端才能通过 URL 访问到。
 */
@Configuration
public class WebMvcConfig implements WebMvcConfigurer {

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        String location = FileUploadUtil.getUploadDir().toUri().toString();
        registry.addResourceHandler("/uploads/**")
                .addResourceLocations(location);
    }
}

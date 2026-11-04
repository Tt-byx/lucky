package com.lucky.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

//Web 静态资源配置 
@Configuration
public class WebMvcConfig implements WebMvcConfigurer {

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        registry.addResourceHandler("/uploads/**")        // 当请求路径是 /uploads/xxx 时
                .addResourceLocations("file:uploads/");   // 去服务器本地的 uploads/ 文件夹找文件
    }

    /*
    当管理员上传了活动背景图片到 uploads/ 文件夹后，
    前端需要通过 URL 访问这张图片。这个配置告诉 Spring："如果有人请求 /uploads/xxx，
    就去本地磁盘的 uploads/ 文件夹里找对应文件返回"。
     */
}

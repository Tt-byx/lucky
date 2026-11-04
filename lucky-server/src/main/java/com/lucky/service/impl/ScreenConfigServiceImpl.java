package com.lucky.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.lucky.dto.DanmakuSettings;
import com.lucky.entity.ScreenConfig;
import com.lucky.mapper.ScreenConfigMapper;
import com.lucky.service.OssService;
import com.lucky.service.ScreenConfigService;
import com.lucky.exception.BusinessException;
import com.lucky.util.FileUploadUtil;
import com.lucky.util.RedisUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

/**
 * 屏幕配置服务实现
 *
 * 上传策略：优先 OSS，失败自动降级到本地存储。
 * 原来只走 OSS，bucket 不存在时整个背景上传功能直接不可用；
 * 现在没有 OSS 凭据也能正常用，本地文件通过 /uploads/** 静态映射访问。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ScreenConfigServiceImpl extends ServiceImpl<ScreenConfigMapper, ScreenConfig>
        implements ScreenConfigService {

    private final OssService ossService;
    private final RedisUtil redisUtil;

    private static final String SCREEN_CONFIG_KEY = "lucky:screen:config";
    private static final String LOCAL_URL_PREFIX = "/uploads/";

    @Override
    public ScreenConfig getConfig() {
        Object cached = redisUtil.get(SCREEN_CONFIG_KEY);
        if (cached instanceof ScreenConfig) {
            log.debug("从缓存获取屏幕配置");
            return (ScreenConfig) cached;
        }

        ScreenConfig config = getById(1L);
        if (config == null) {
            config = new ScreenConfig();
            config.setId(1L);
        }

        redisUtil.set(SCREEN_CONFIG_KEY, config, 10, TimeUnit.MINUTES);
        return config;
    }

    @Override
    public ScreenConfig updateBackground(String type, MultipartFile file) {
        ScreenConfig config = getConfig();
        deleteFile(config.getBackgroundUrl());

        config.setBackgroundType(type);
        config.setBackgroundUrl(uploadFile(file));
        saveOrUpdate(config);

        redisUtil.delete(SCREEN_CONFIG_KEY);
        return config;
    }

    @Override
    public void clearBackground() {
        ScreenConfig config = getConfig();
        deleteFile(config.getBackgroundUrl());
        config.setBackgroundType(null);
        config.setBackgroundUrl(null);
        saveOrUpdate(config);

        redisUtil.delete(SCREEN_CONFIG_KEY);
    }

    @Override
    public ScreenConfig updateMobileBackground(String type, MultipartFile file) {
        ScreenConfig config = getConfig();
        deleteFile(config.getMobileBackgroundUrl());

        config.setMobileBackgroundType(type);
        config.setMobileBackgroundUrl(uploadFile(file));
        saveOrUpdate(config);

        redisUtil.delete(SCREEN_CONFIG_KEY);
        return config;
    }

    @Override
    public void clearMobileBackground() {
        ScreenConfig config = getConfig();
        deleteFile(config.getMobileBackgroundUrl());
        config.setMobileBackgroundType(null);
        config.setMobileBackgroundUrl(null);
        saveOrUpdate(config);

        redisUtil.delete(SCREEN_CONFIG_KEY);
    }

    @Override
    public void updateDanmakuSettings(DanmakuSettings settings) {
        ScreenConfig config = getConfig();
        config.setDanmakuArea(settings.getArea());
        config.setDanmakuOpacity(settings.getOpacity());
        config.setDanmakuFontSize(settings.getFontSize());
        config.setDanmakuSpeed(settings.getSpeed());
        saveOrUpdate(config);

        redisUtil.delete(SCREEN_CONFIG_KEY);
    }

    /**
     * 上传文件：OSS 优先，失败降级本地
     */
    private String uploadFile(MultipartFile file) {
        try {
            return ossService.upload(file);
        } catch (Exception e) {
            log.warn("OSS 上传失败，降级为本地存储: {}", e.getMessage());
            try {
                String url = FileUploadUtil.upload(file);
                log.info("本地存储上传成功: {}", url);
                return url;
            } catch (IOException io) {
                throw new BusinessException("文件上传失败: " + io.getMessage(), io);
            }
        }
    }

    /**
     * 删除文件：按 URL 前缀区分 OSS 与本地
     */
    private void deleteFile(String url) {
        if (url == null || url.isEmpty()) {
            return;
        }
        if (url.startsWith(LOCAL_URL_PREFIX)) {
            FileUploadUtil.delete(url);
        } else {
            ossService.delete(url);
        }
    }
}

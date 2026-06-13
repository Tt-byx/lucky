package com.lucky.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.lucky.dto.DanmakuSettings;
import com.lucky.entity.ScreenConfig;
import com.lucky.mapper.ScreenConfigMapper;
import com.lucky.service.OssService;
import com.lucky.service.ScreenConfigService;
import com.lucky.exception.BusinessException;
import com.lucky.util.RedisUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.util.concurrent.TimeUnit;

/**
 * 屏幕配置服务实现
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ScreenConfigServiceImpl extends ServiceImpl<ScreenConfigMapper, ScreenConfig>
        implements ScreenConfigService {

    private final OssService ossService;
    private final RedisUtil redisUtil;

    /**
     * 屏幕配置缓存 Key
     */
    private static final String SCREEN_CONFIG_KEY = "lucky:screen:config";

    @Override
    public ScreenConfig getConfig() {
        // 先从缓存获取
        Object cached = redisUtil.get(SCREEN_CONFIG_KEY);
        if (cached != null && cached instanceof ScreenConfig) {
            log.debug("从缓存获取屏幕配置");
            return (ScreenConfig) cached;
        }

        // 缓存未命中，从数据库查询
        ScreenConfig config = getById(1L);
        if (config == null) {
            config = new ScreenConfig();
            config.setId(1L);
        }

        // 写入缓存（10分钟过期）
        redisUtil.set(SCREEN_CONFIG_KEY, config, 10, TimeUnit.MINUTES);

        return config;
    }

    @Override
    public ScreenConfig updateBackground(String type, MultipartFile file) {
        ScreenConfig config = getConfig();
        ossService.delete(config.getBackgroundUrl());
        String url;
        try {
            url = ossService.upload(file);
        } catch (Exception e) {
            throw new BusinessException("文件上传失败: " + e.getMessage(), e);
        }
        config.setBackgroundType(type);
        config.setBackgroundUrl(url);
        saveOrUpdate(config);

        // 清除缓存
        redisUtil.delete(SCREEN_CONFIG_KEY);

        return config;
    }

    @Override
    public void clearBackground() {
        ScreenConfig config = getConfig();
        ossService.delete(config.getBackgroundUrl());
        config.setBackgroundType(null);
        config.setBackgroundUrl(null);
        saveOrUpdate(config);

        // 清除缓存
        redisUtil.delete(SCREEN_CONFIG_KEY);
    }

    @Override
    public ScreenConfig updateMobileBackground(String type, MultipartFile file) {
        ScreenConfig config = getConfig();
        ossService.delete(config.getMobileBackgroundUrl());
        String url;
        try {
            url = ossService.upload(file);
        } catch (Exception e) {
            throw new BusinessException("文件上传失败: " + e.getMessage(), e);
        }
        config.setMobileBackgroundType(type);
        config.setMobileBackgroundUrl(url);
        saveOrUpdate(config);

        // 清除缓存
        redisUtil.delete(SCREEN_CONFIG_KEY);

        return config;
    }

    @Override
    public void clearMobileBackground() {
        ScreenConfig config = getConfig();
        ossService.delete(config.getMobileBackgroundUrl());
        config.setMobileBackgroundType(null);
        config.setMobileBackgroundUrl(null);
        saveOrUpdate(config);

        // 清除缓存
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

        // 清除缓存
        redisUtil.delete(SCREEN_CONFIG_KEY);
    }
}
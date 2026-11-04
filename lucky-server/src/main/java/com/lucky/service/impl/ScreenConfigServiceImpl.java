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
        validateType(type, file);

        ScreenConfig config = getConfig();
        String oldUrl = config.getBackgroundUrl();

        // 先上传成功，再删旧文件。
        // 原实现是先 deleteFile(旧) 再 uploadFile(新)，一旦上传失败：
        // 旧背景已经被删掉，而数据库里的 URL 还指着它 —— 大屏直接空白且不可恢复。
        String newUrl = uploadFile(file);

        config.setBackgroundType(type);
        config.setBackgroundUrl(newUrl);
        saveOrUpdate(config);

        redisUtil.delete(SCREEN_CONFIG_KEY);
        deleteFile(oldUrl);
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
        validateType(type, file);

        ScreenConfig config = getConfig();
        String oldUrl = config.getMobileBackgroundUrl();

        // 同样先传新的，成功后再删旧的
        String newUrl = uploadFile(file);

        config.setMobileBackgroundType(type);
        config.setMobileBackgroundUrl(newUrl);
        saveOrUpdate(config);

        redisUtil.delete(SCREEN_CONFIG_KEY);
        deleteFile(oldUrl);
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
     * 校验背景类型与文件内容是否匹配
     *
     * 原实现直接采信前端传来的 type 并落库，于是可以出现
     * 「type=video 但文件是图片」，大屏会拿 <video src="xxx.png"> 渲染，直接黑屏。
     */
    private void validateType(String type, MultipartFile file) {
        if (!"image".equals(type) && !"video".equals(type)) {
            throw new BusinessException("背景类型只能是 image 或 video");
        }
        if ("image".equals(type) && !FileUploadUtil.isImage(file)) {
            throw new BusinessException(
                    "文件不是有效的图片：只支持 jpg/png/gif/webp，且不超过 10MB");
        }
        if ("video".equals(type) && !FileUploadUtil.isVideo(file)) {
            throw new BusinessException(
                    "文件不是有效的视频：只支持 mp4/webm/ogg，且不超过 100MB");
        }
    }

    /**
     * 上传文件：OSS 优先，失败降级本地
     *
     * 这里不再重复做类型校验——Controller 的 isImage/isVideo 与 validateType 已经做过，
     * 而两者内部都是同一套 detectSafeExtension（扩展名 + 大小 + 文件头），重复调用只是多读几次流。
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

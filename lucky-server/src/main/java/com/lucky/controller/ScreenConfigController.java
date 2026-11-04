package com.lucky.controller;

import com.lucky.dto.DanmakuSettings;
import com.lucky.dto.Result;
import com.lucky.entity.ScreenConfig;
import com.lucky.service.ScreenConfigService;
import com.lucky.exception.BusinessException;
import com.lucky.util.FileUploadUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@Slf4j
@RestController
@RequestMapping("/api/screen")
@RequiredArgsConstructor
public class ScreenConfigController {

    private final ScreenConfigService screenConfigService;

    @GetMapping("/config")
    public Result<ScreenConfig> getConfig() {
        return Result.ok(screenConfigService.getConfig());
    }

    @PostMapping("/background")
    public Result<ScreenConfig> uploadBackground(
            @RequestParam String type,
            @RequestParam("file") MultipartFile file) {
        
        log.info("收到上传请求 - 类型: {}, 文件名: {}, 大小: {} bytes", 
                type, file.getOriginalFilename(), file.getSize());
        
        if (file.isEmpty()) {
            log.warn("文件为空");
            return Result.error("请选择文件");
        }
        
        if (!"image".equals(type) && !"video".equals(type)) {
            log.warn("无效的类型: {}", type);
            return Result.error("背景类型必须是 image 或 video");
        }
        
        if ("image".equals(type) && !FileUploadUtil.isImage(file)) {
            log.warn("不支持的图片格式: {}", file.getContentType());
            return Result.error("文件不是有效的图片：只支持 jpg/png/gif/webp，且不超过 10MB");
        }
        
        if ("video".equals(type) && !FileUploadUtil.isVideo(file)) {
            log.warn("不支持的视频格式: {}", file.getContentType());
            return Result.error("文件不是有效的视频：只支持 mp4/webm/ogg，且不超过 100MB");
        }
        
        try {
            ScreenConfig config = screenConfigService.updateBackground(type, file);
            log.info("上传成功 - URL: {}", config.getBackgroundUrl());
            return Result.ok(config);
        } catch (BusinessException e) {
            log.warn("业务异常: {}", e.getMessage());
            return Result.error(e.getMessage());
        } catch (Exception e) {
            log.error("上传失败", e);
            return Result.error("上传失败，请稍后重试");
        }
    }

    @DeleteMapping("/background")
    public Result<Void> clearBackground() {
        screenConfigService.clearBackground();
        log.info("背景已清除");
        return Result.ok();
    }

    @PostMapping("/mobile-background")
    public Result<ScreenConfig> uploadMobileBackground(
            @RequestParam String type,
            @RequestParam("file") MultipartFile file) {

        log.info("收到手机端背景上传请求 - 类型: {}, 文件名: {}, 大小: {} bytes",
                type, file.getOriginalFilename(), file.getSize());

        if (file.isEmpty()) {
            return Result.error("请选择文件");
        }

        if (!"image".equals(type) && !"video".equals(type)) {
            return Result.error("背景类型必须是 image 或 video");
        }

        if ("image".equals(type) && !FileUploadUtil.isImage(file)) {
            return Result.error("文件不是有效的图片：只支持 jpg/png/gif/webp，且不超过 10MB");
        }

        if ("video".equals(type) && !FileUploadUtil.isVideo(file)) {
            return Result.error("文件不是有效的视频：只支持 mp4/webm/ogg，且不超过 100MB");
        }

        try {
            ScreenConfig config = screenConfigService.updateMobileBackground(type, file);
            log.info("手机端背景上传成功 - URL: {}", config.getMobileBackgroundUrl());
            return Result.ok(config);
        } catch (BusinessException e) {
            log.warn("业务异常: {}", e.getMessage());
            return Result.error(e.getMessage());
        } catch (Exception e) {
            log.error("手机端背景上传失败", e);
            return Result.error("上传失败，请稍后重试");
        }
    }

    @DeleteMapping("/mobile-background")
    public Result<Void> clearMobileBackground() {
        screenConfigService.clearMobileBackground();
        log.info("手机端背景已清除");
        return Result.ok();
    }

    @GetMapping("/danmaku-settings")
    public Result<DanmakuSettings> getDanmakuSettings() {
        ScreenConfig config = screenConfigService.getConfig();
        DanmakuSettings settings = DanmakuSettings.builder()
                .area(config.getDanmakuArea())
                .opacity(config.getDanmakuOpacity())
                .fontSize(config.getDanmakuFontSize())
                .speed(config.getDanmakuSpeed())
                .build();
        return Result.ok(settings);
    }

    @PostMapping("/danmaku-settings")
    public Result<DanmakuSettings> saveDanmakuSettings(@RequestBody DanmakuSettings settings) {
        log.info("保存弹幕设置: {}", settings);
        screenConfigService.updateDanmakuSettings(settings);
        return Result.ok(settings);
    }
}
package com.lucky.service;

import com.aliyun.oss.OSS;
import com.lucky.util.FileUploadUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.util.UUID;

/**
 * 阿里云 OSS 服务
 * OSS 为可选功能，未配置凭证时 ossClient 为 null，上传功能不可用但不影响系统启动
 */
@Slf4j
@Service
public class OssService {

    @Autowired(required = false)
    private OSS ossClient;

    @Value("${alibaba.oss.bucket-name:}")
    private String bucketName;

    @Value("${alibaba.oss.endpoint:}")
    private String endpoint;

    public String upload(MultipartFile file) throws IOException {
        if (ossClient == null) {
            throw new IllegalStateException("OSS 未配置，无法上传文件");
        }

        // 与本地存储同一套校验：扩展名白名单 + 文件头验真 + 大小上限。
        // 落盘（对象名）的扩展名也只取真实类型，不采用用户提供的文件名。
        // 否则上传一个伪装成 image/png 的 .html，会被存成 xxx.html 并以 text/html 返回 —— 存储型 XSS。
        String safeExtension = FileUploadUtil.detectSafeExtension(file);
        if (safeExtension == null) {
            throw new IllegalArgumentException("文件类型不支持或超出大小限制");
        }

        String objectName = "lucky/background/" + UUID.randomUUID() + safeExtension;

        try (InputStream inputStream = file.getInputStream()) {
            ossClient.putObject(bucketName, objectName, inputStream);
        }

        return "https://" + bucketName + "." + endpoint + "/" + objectName;
    }

    public void delete(String url) {
        if (ossClient == null || url == null || url.isEmpty()) {
            return;
        }
        try {
            String prefix = "https://" + bucketName + "." + endpoint + "/";
            if (url.startsWith(prefix)) {
                String objectName = url.substring(prefix.length());
                ossClient.deleteObject(bucketName, objectName);
            }
        } catch (Exception e) {
            log.warn("OSS 删除文件失败: {}", e.getMessage());
        }
    }
}

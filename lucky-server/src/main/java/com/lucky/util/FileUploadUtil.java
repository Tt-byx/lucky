package com.lucky.util;

import org.springframework.web.multipart.MultipartFile;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Set;
import java.util.UUID;

public class FileUploadUtil {

    private static final String UPLOAD_DIR = "uploads";
    private static final Set<String> IMAGE_TYPES = Set.of("image/jpeg", "image/png", "image/gif", "image/webp");
    private static final Set<String> VIDEO_TYPES = Set.of("video/mp4", "video/webm", "video/ogg");

    public static boolean isImage(MultipartFile file) {
        return file.getContentType() != null && IMAGE_TYPES.contains(file.getContentType());
    }

    public static boolean isVideo(MultipartFile file) {
        return file.getContentType() != null && VIDEO_TYPES.contains(file.getContentType());
    }

    /**
     * 本地存储目录
     *
     * 必须用绝对路径：MultipartFile.transferTo 传入相对路径时，
     * Servlet 容器会基于自己的 multipart 临时目录去解析，
     * 结果文件落到 /tmp/tomcat.xxx/work/... 下面，静态映射根本找不到。
     */
    public static Path getUploadDir() {
        return Paths.get(UPLOAD_DIR).toAbsolutePath().normalize();
    }

    public static String upload(MultipartFile file) throws IOException {
        String originalFilename = file.getOriginalFilename();
        String extension = "";
        if (originalFilename != null && originalFilename.contains(".")) {
            extension = originalFilename.substring(originalFilename.lastIndexOf("."));
        }
        String newFilename = UUID.randomUUID().toString() + extension;

        Path uploadPath = getUploadDir();
        Files.createDirectories(uploadPath);

        Path filePath = uploadPath.resolve(newFilename);
        file.transferTo(filePath.toFile());

        return "/uploads/" + newFilename;
    }

    public static void delete(String url) {
        if (url == null || !url.startsWith("/uploads/")) {
            return;
        }
        try {
            String filename = url.substring("/uploads/".length());
            Files.deleteIfExists(getUploadDir().resolve(filename));
        } catch (IOException e) {
            // 删除失败不影响业务，留个文件而已
        }
    }
}

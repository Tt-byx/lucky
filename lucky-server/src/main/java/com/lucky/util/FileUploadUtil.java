package com.lucky.util;

import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Set;
import java.util.UUID;

/**
 * 本地文件上传工具
 *
 * 安全模型（三层防御，缺一不可）：
 *   1. 扩展名白名单 —— 只认图片/视频，且**故意不支持 svg**（SVG 可内嵌 script，是 XSS 载体）
 *   2. 文件头校验   —— 读前 12 字节判断真实类型，伪造 Content-Type 无效
 *   3. 落盘扩展名取自真实类型 —— 不信任用户给的文件名，即使传 xxx.html 也只会存成 xxx.png
 *
 * 之前的问题：只把原始文件名的扩展名拼到 UUID 后面，既不验类型也不验内容。
 * 实测上传一个伪装成 image/png 的 .html，能被存成 /uploads/xxx.html 并以
 * Content-Type: text/html 返回，访问即执行任意 JS —— 同源下的存储型 XSS。
 */
public class FileUploadUtil {

    private static final String UPLOAD_DIR = "uploads";

    /** 允许的图片扩展名（不含 .svg：可内嵌脚本） */
    private static final Set<String> IMAGE_EXT = Set.of(".jpg", ".jpeg", ".png", ".gif", ".webp");

    /** 允许的视频扩展名 */
    private static final Set<String> VIDEO_EXT = Set.of(".mp4", ".webm", ".ogg");

    /** 图片大小上限 10MB —— 背景图没必要更大 */
    private static final long MAX_IMAGE_BYTES = 10L * 1024 * 1024;

    /** 视频大小上限 100MB */
    private static final long MAX_VIDEO_BYTES = 100L * 1024 * 1024;

    public static boolean isImage(MultipartFile file) {
        String ext = detectSafeExtension(file);
        return ext != null && IMAGE_EXT.contains(ext);
    }

    public static boolean isVideo(MultipartFile file) {
        String ext = detectSafeExtension(file);
        return ext != null && VIDEO_EXT.contains(ext);
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

    /**
     * 校验文件并返回「可以安全落盘的扩展名」
     *
     * @return 形如 ".png"；不合法（类型不支持 / 超大小 / 内容与扩展名不符）返回 null
     */
    public static String detectSafeExtension(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            return null;
        }

        String declared = extensionOf(file.getOriginalFilename());
        boolean declaredImage = IMAGE_EXT.contains(declared);
        boolean declaredVideo = VIDEO_EXT.contains(declared);
        if (!declaredImage && !declaredVideo) {
            return null;   // 包含 .svg / .html / .jsp 等一律拒绝
        }

        long size = file.getSize();
        if (declaredImage && size > MAX_IMAGE_BYTES) {
            return null;
        }
        if (declaredVideo && size > MAX_VIDEO_BYTES) {
            return null;
        }

        // 真实类型必须和声明的大类一致：把 .png 改名叫 .mp4 也要挡住
        String real = detectByMagic(file);
        if (real == null) {
            return null;
        }
        if (declaredImage && !IMAGE_EXT.contains(real)) {
            return null;
        }
        if (declaredVideo && !VIDEO_EXT.contains(real)) {
            return null;
        }

        return real;
    }

    /** 读文件头判断真实类型，返回对应的安全扩展名 */
    private static String detectByMagic(MultipartFile file) {
        try (InputStream in = file.getInputStream()) {
            byte[] h = new byte[12];
            int n = in.read(h);
            if (n < 4) {
                return null;
            }

            // PNG: 89 50 4E 47
            if ((h[0] & 0xFF) == 0x89 && h[1] == 0x50 && h[2] == 0x4E && h[3] == 0x47) {
                return ".png";
            }
            // JPEG: FF D8 FF
            if ((h[0] & 0xFF) == 0xFF && (h[1] & 0xFF) == 0xD8 && (h[2] & 0xFF) == 0xFF) {
                return ".jpg";
            }
            // GIF: 47 49 46 38
            if (h[0] == 0x47 && h[1] == 0x49 && h[2] == 0x46 && h[3] == 0x38) {
                return ".gif";
            }
            // WEBP: "RIFF" .... "WEBP"
            if (n >= 12 && h[0] == 0x52 && h[1] == 0x49 && h[2] == 0x46 && h[3] == 0x46
                    && h[8] == 0x57 && h[9] == 0x45 && h[10] == 0x42 && h[11] == 0x50) {
                return ".webp";
            }
            // MP4: 第 4~7 字节是 "ftyp"
            if (n >= 8 && h[4] == 0x66 && h[5] == 0x74 && h[6] == 0x79 && h[7] == 0x70) {
                return ".mp4";
            }
            // WEBM: 1A 45 DF A3
            if ((h[0] & 0xFF) == 0x1A && (h[1] & 0xFF) == 0x45
                    && (h[2] & 0xFF) == 0xDF && (h[3] & 0xFF) == 0xA3) {
                return ".webm";
            }
            // OGG: "OggS"
            if (h[0] == 0x4F && h[1] == 0x67 && h[2] == 0x67 && h[3] == 0x53) {
                return ".ogg";
            }
            return null;
        } catch (IOException e) {
            return null;
        }
    }

    private static String extensionOf(String filename) {
        if (filename == null) {
            return "";
        }
        int idx = filename.lastIndexOf('.');
        if (idx < 0) {
            return "";
        }
        return filename.substring(idx).toLowerCase();
    }

    /**
     * 本地落盘
     *
     * 扩展名一律由 detectSafeExtension 决定，不采用用户提供的文件名后缀。
     */
    public static String upload(MultipartFile file) throws IOException {
        String ext = detectSafeExtension(file);
        if (ext == null) {
            throw new IllegalArgumentException("文件类型不支持或超出大小限制");
        }

        String newFilename = UUID.randomUUID() + ext;

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

            // 防路径穿越：只接受单层文件名。
            // 原来直接把 filename 丢给 resolve，若 url 是 "/uploads/../../app/config/x.yml"，
            // 解析出来就在 upload 目录之外，deleteIfExists 会删掉任意文件。
            if (filename.isEmpty()
                    || filename.contains("/")
                    || filename.contains("\\")
                    || filename.contains("..")) {
                return;
            }

            Path base = getUploadDir().normalize();
            Path target = base.resolve(filename).normalize();
            if (!target.startsWith(base)) {
                return;
            }

            Files.deleteIfExists(target);
        } catch (IOException e) {
            // 删除失败不影响业务，留个文件而已
        }
    }
}

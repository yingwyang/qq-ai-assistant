package com.qqai.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class MediaDownloadService {

    private static final Logger log = LoggerFactory.getLogger(MediaDownloadService.class);

    @Value("${file.storage.local-path:./uploads/images}")
    private String localImagePath;

    @Value("${ffmpeg.path:ffmpeg}")
    private String ffmpegPath;

    @Value("${python.executable:python}")
    private String pythonExecutable;

    @Value("${python.silk-script:./scripts/convert_silk_to_mp3.py}")
    private String silkScriptPath;

    public String downloadMediaToLocal(String cqMessage, String groupId, String mediaType, String defaultExt) {
        try {
            String mediaUrl = extractUrlFromCQ(cqMessage);
            if (mediaUrl == null || mediaUrl.isEmpty()) {
                log.warn("无法从CQ码中提取URL或路径: {}", cqMessage);
                return null;
            }

            String dateFolder = LocalDateTime.now().toLocalDate().toString();
            Path groupDir = Paths.get(localImagePath, mediaType, groupId, dateFolder);
            Files.createDirectories(groupDir);

            String fileName = UUID.randomUUID() + defaultExt;
            Path localPath = groupDir.resolve(fileName);

            if (isLocalFilePath(mediaUrl)) {
                // 本地文件路径：直接复制。
                // 安全:群成员可在文本中伪造 "[CQ:image,path=C:/敏感文件]" 触发任意本地文件拷贝,
                // 因此必须同时满足:(1) 文件扩展名与媒体类型匹配;(2) 路径位于 uploads 目录之内
                // (NapCat 上报的本地缓存路径通常位于 NapCat 安装目录,不在 uploads 内,
                //  这类合法路径会改走 HTTP url 字段下载,一般不会走到本地复制分支)。
                Path sourcePath = Paths.get(mediaUrl).toAbsolutePath().normalize();
                if (!Files.exists(sourcePath)) {
                    log.warn("本地媒体文件不存在: {}", mediaUrl);
                    return null;
                }
                if (!isAllowedExtensionForType(sourcePath.getFileName().toString(), mediaType)) {
                    log.warn("【安全】本地文件扩展名与媒体类型不符,拒绝复制: {}", mediaUrl);
                    return null;
                }
                Path uploadsRoot = Paths.get("uploads").toAbsolutePath().normalize();
                Path imagesRoot = Paths.get(localImagePath).toAbsolutePath().normalize();
                if (!sourcePath.startsWith(uploadsRoot) && !sourcePath.startsWith(imagesRoot)) {
                    log.warn("【安全】本地文件路径越界,拒绝复制: {}", sourcePath);
                    return null;
                }
                Files.copy(sourcePath, localPath, StandardCopyOption.REPLACE_EXISTING);
                log.info("复制本地媒体文件: {} -> {}", mediaUrl, localPath);
            } else {
                // 远程 URL：走 HTTP 下载（含 SSRF 防护）
                URL url = new URL(mediaUrl);
                String protocol = url.getProtocol();
                if (!"http".equalsIgnoreCase(protocol) && !"https".equalsIgnoreCase(protocol)) {
                    log.warn("SSRF防护：拒绝非 HTTP/HTTPS 协议: {}", protocol);
                    return null;
                }
                String host = url.getHost();
                if (isBlockedHost(host)) {
                    log.warn("SSRF防护：拒绝访问内网/敏感地址: {}", host);
                    return null;
                }
                HttpURLConnection connection = (HttpURLConnection) url.openConnection();
                connection.setRequestMethod("GET");
                connection.setConnectTimeout(10000);
                connection.setReadTimeout(60000);
                connection.setRequestProperty("User-Agent", "Mozilla/5.0");

                if (connection.getResponseCode() != HttpURLConnection.HTTP_OK) {
                    log.warn("下载{}失败，HTTP状态码: {}", mediaType, connection.getResponseCode());
                    return null;
                }

                // 检查 Content-Length 头，超过 100MB 拒绝
                long contentLength = connection.getContentLengthLong();
                final long MAX_DOWNLOAD_SIZE = 100L * 1024 * 1024; // 100MB
                if (contentLength > MAX_DOWNLOAD_SIZE) {
                    log.warn("下载{}失败，文件大小超过100MB限制: {} bytes", mediaType, contentLength);
                    return null;
                }

                try (InputStream inputStream = connection.getInputStream();
                     FileOutputStream outputStream = new FileOutputStream(localPath.toFile())) {
                    byte[] buffer = new byte[4096];
                    int bytesRead;
                    long totalRead = 0;
                    while ((bytesRead = inputStream.read(buffer)) != -1) {
                        totalRead += bytesRead;
                        if (totalRead > MAX_DOWNLOAD_SIZE) {
                            throw new IOException("下载文件超过100MB限制");
                        }
                        outputStream.write(buffer, 0, bytesRead);
                    }
                }
            }

            // 语音文件统一尝试转换为浏览器可播放的 mp3
            if ("voice".equals(mediaType)) {
                String mp3Path = convertVoiceToMp3(localPath.toString());
                if (mp3Path != null) {
                    Path mp3FileName = Paths.get(mp3Path).getFileName();
                    String relativePath = "/images/" + mediaType + "/" + groupId + "/" + dateFolder + "/" + mp3FileName;
                    Files.deleteIfExists(localPath);
                    return relativePath;
                }
            }

            return "/images/" + mediaType + "/" + groupId + "/" + dateFolder + "/" + fileName;
        } catch (Exception e) {
            log.error("下载{}到本地时出错: {}", mediaType, e.getMessage(), e);
            return null;
        }
    }

    public String downloadGroupAvatarToLocal(String groupId) {
        try {
            String imageUrl = "https://p.qlogo.cn/gh/" + groupId + "/" + groupId + "/100";
            Path avatarDir = Paths.get("uploads/avatars/groups");
            Files.createDirectories(avatarDir);

            String fileName = "group_" + groupId + ".jpg";
            Path localPath = avatarDir.resolve(fileName);

            URL url = new URL(imageUrl);
            HttpURLConnection connection = (HttpURLConnection) url.openConnection();
            connection.setRequestMethod("GET");
            connection.setConnectTimeout(10000);
            connection.setReadTimeout(30000);
            connection.setRequestProperty("User-Agent", "Mozilla/5.0");

            if (connection.getResponseCode() != HttpURLConnection.HTTP_OK) {
                log.warn("下载群头像失败，HTTP状态码: {}, URL: {}", connection.getResponseCode(), imageUrl);
                return null;
            }

            try (InputStream inputStream = connection.getInputStream();
                 FileOutputStream outputStream = new FileOutputStream(localPath.toFile())) {
                byte[] buffer = new byte[4096];
                int bytesRead;
                while ((bytesRead = inputStream.read(buffer)) != -1) {
                    outputStream.write(buffer, 0, bytesRead);
                }
            }

            return "/uploads/avatars/groups/" + fileName;
        } catch (Exception e) {
            log.error("下载群头像到本地时出错: {}", e.getMessage());
            return null;
        }
    }

    public String extractUrlFromCQ(String cqMessage) {
        // 优先使用反引号包裹的 URL（NapCat 常用）
        Pattern pattern = Pattern.compile("url=`([^`]+)`");
        Matcher matcher = pattern.matcher(cqMessage);
        if (matcher.find()) {
            return matcher.group(1).replace("&amp;", "&").trim();
        }

        pattern = Pattern.compile("url=([^,\\]]+)");
        matcher = pattern.matcher(cqMessage);
        if (matcher.find()) {
            return matcher.group(1).replace("&amp;", "&").trim();
        }

        // NapCat 语音消息使用本地路径时，url 和 path 都可能是本地文件路径
        pattern = Pattern.compile("path=`([^`]+)`");
        matcher = pattern.matcher(cqMessage);
        if (matcher.find()) {
            return matcher.group(1).trim();
        }

        pattern = Pattern.compile("path=([^,\\]]+)");
        matcher = pattern.matcher(cqMessage);
        if (matcher.find()) {
            return matcher.group(1).trim();
        }
        return null;
    }

    /**
     * 尝试将语音文件转换为浏览器可播放的 mp3。
     * 优先使用 ffmpeg 处理标准 AMR；失败时调用 Python + pysilk 处理 QQ SILK 语音。
     */
    private String convertVoiceToMp3(String voicePath) {
        if (!Files.exists(Paths.get(voicePath))) {
            return null;
        }

        String mp3Path = voicePath.replaceAll("\\.(amr|silk)$", "") + ".mp3";

        // 先尝试 ffmpeg 直接转换（标准 AMR）
        String ffmpeg = resolveFfmpegPath();
        try {
            ProcessBuilder pb = new ProcessBuilder(
                    ffmpeg,
                    "-i", voicePath,
                    "-acodec", "libmp3lame",
                    "-q:a", "2",
                    "-y",
                    mp3Path
            );
            pb.redirectErrorStream(true);
            Process process = pb.start();
            int exitCode = process.waitFor();
            if (exitCode == 0 && Files.exists(Paths.get(mp3Path))) {
                return mp3Path;
            }
        } catch (Exception e) {
            log.debug("ffmpeg 直接转换语音失败: {}", e.getMessage());
        }

        // ffmpeg 失败，尝试使用 Python + pysilk 解码 SILK
        try {
            return convertSilkToMp3(voicePath, mp3Path);
        } catch (Exception e) {
            log.error("Python SILK 转 MP3 失败: {}", e.getMessage());
        }

        return null;
    }

    private String convertSilkToMp3(String inputPath, String outputPath) throws Exception {
        Path scriptPath = Paths.get(silkScriptPath);
        if (!scriptPath.isAbsolute()) {
            // 如果配置的是相对路径，基于当前工作目录解析
            scriptPath = Paths.get(System.getProperty("user.dir"), silkScriptPath).toAbsolutePath().normalize();
        }
        if (!Files.exists(scriptPath)) {
            log.warn("SILK 转换脚本不存在: {}", scriptPath);
            return null;
        }

        ProcessBuilder pb = new ProcessBuilder(
                pythonExecutable,
                scriptPath.toString(),
                inputPath,
                outputPath,
                "--ffmpeg", resolveFfmpegPath()
        );
        pb.redirectErrorStream(true);
        Process process = pb.start();

        StringBuilder output = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                output.append(line).append("\n");
            }
        }

        int exitCode = process.waitFor();
        if (exitCode != 0) {
            log.warn("SILK 转 MP3 脚本退出码: {}, 输出: {}", exitCode, output);
            return null;
        }

        if (Files.exists(Paths.get(outputPath))) {
            log.info("SILK 语音已转换为 MP3: {}", outputPath);
            return outputPath;
        }
        return null;
    }

    /**
     * SSRF 防护：校验 host 是否为内网/敏感地址。
     * 拒绝回环、私网、链路本地、0.0.0.0、广播、组播、以及 "localhost" 裸主机名。
     */
    private boolean isBlockedHost(String host) {
        if (host == null || host.isEmpty()) return true;
        // 拒绝裸主机名 localhost
        if ("localhost".equalsIgnoreCase(host)) return true;
        try {
            InetAddress[] addresses = InetAddress.getAllByName(host);
            for (InetAddress addr : addresses) {
                if (addr.isLoopbackAddress() || addr.isLinkLocalAddress()
                        || addr.isSiteLocalAddress() || addr.isAnyLocalAddress()
                        || addr.isMulticastAddress()) {
                    return true;
                }
            }
        } catch (Exception e) {
            log.warn("SSRF防护：无法解析主机名 {}, 拒绝下载", host);
            return true;
        }
        return false;
    }

    private boolean isLocalFilePath(String path) {
        if (path == null || path.isEmpty()) {
            return false;
        }
        // Windows 盘符路径或 Unix 绝对路径
        return path.matches("^[A-Za-z]:\\\\.*$") || path.startsWith("/") || path.startsWith("\\\\");
    }

    /**
     * 校验本地文件扩展名是否与媒体类型匹配,防止把任意文件(如配置、密钥)复制进公开媒体目录。
     */
    private boolean isAllowedExtensionForType(String fileName, String mediaType) {
        if (fileName == null || fileName.isEmpty()) {
            return false;
        }
        String name = fileName.toLowerCase();
        return switch (mediaType) {
            case "images" -> name.endsWith(".jpg") || name.endsWith(".jpeg") || name.endsWith(".png")
                    || name.endsWith(".gif") || name.endsWith(".webp") || name.endsWith(".bmp");
            case "voice" -> name.endsWith(".amr") || name.endsWith(".silk") || name.endsWith(".mp3")
                    || name.endsWith(".wav") || name.endsWith(".m4a");
            case "video" -> name.endsWith(".mp4") || name.endsWith(".mov") || name.endsWith(".webm")
                    || name.endsWith(".m4v") || name.endsWith(".avi");
            default -> false;
        };
    }

    private String resolveFfmpegPath() {
        File ffmpegFile = new File(ffmpegPath);
        return ffmpegFile.exists() ? ffmpegPath : "ffmpeg";
    }

    /**
     * 将本地路径中的 .amr/.silk 语音文件转换为浏览器可播放的 mp3（按需转码）。
     * 适用于数据库中存储的是旧的 .amr 路径的情况。
     *
     * @param relativePath 相对路径，如 /images/voice/{groupId}/{date}/xxx.amr
     * @return 转码后的相对路径（如 /images/voice/{groupId}/{date}/xxx.mp3），失败时返回 null
     */
    public String convertVoiceToMp3OnDemand(String relativePath) {
        if (relativePath == null || relativePath.isEmpty()) {
            return null;
        }

        // 如果已经是 mp3 格式，直接返回
        if (relativePath.toLowerCase().endsWith(".mp3")) {
            return relativePath;
        }

        // 如果不是 amr/silk 格式，直接返回
        if (!relativePath.toLowerCase().matches(".*\\.(amr|silk)$")) {
            return relativePath;
        }

        // 构建本地绝对路径
        String baseDir = localImagePath.endsWith("/") ? localImagePath : localImagePath + "/";
        // relativePath 可能以 /images/ 开头，需要去除该前缀
        String subPath = relativePath.startsWith("/images/")
                ? relativePath.substring("/images/".length())
                : relativePath;
        // 如果是 /uploads/ 开头，则使用 uploads 作为基础目录
        String absolutePathStr;
        if (relativePath.startsWith("/uploads/")) {
            String uploadSubPath = relativePath.substring("/uploads/".length());
            File uploadsDir = new File("uploads");
            absolutePathStr = new File(uploadsDir, uploadSubPath).getAbsolutePath();
        } else {
            absolutePathStr = new File(baseDir + subPath).getAbsolutePath();
        }

        Path voicePath = Paths.get(absolutePathStr).toAbsolutePath().normalize();

        // 路径穿越防护：必须位于合法的两个根目录之下
        Path basePath = Paths.get(localImagePath).toAbsolutePath().normalize();
        File uploadsDir = new File("uploads");
        Path uploadsPath = uploadsDir.toPath().toAbsolutePath().normalize();
        if (!voicePath.startsWith(basePath) && !voicePath.startsWith(uploadsPath)) {
            log.warn("路径穿越攻击检测: relativePath={}, resolved={}", relativePath, voicePath);
            return null;
        }

        if (!Files.exists(voicePath)) {
            log.warn("语音文件不存在: {}", absolutePathStr);
            return null;
        }

        // 检查是否已有对应的 mp3 文件
        String mp3PathStr = absolutePathStr.replaceAll("\\.(amr|silk)$", ".mp3");
        Path mp3Path = Paths.get(mp3PathStr);
        if (Files.exists(mp3Path)) {
            // 已有 mp3 文件，直接返回 mp3 的相对路径
            return relativePath.replaceAll("\\.(amr|silk)$", ".mp3");
        }

        // 尝试转码
        String converted = convertVoiceToMp3(absolutePathStr);
        if (converted != null) {
            // 转码成功，返回新的相对路径
            return relativePath.replaceAll("\\.(amr|silk)$", ".mp3");
        }

        log.warn("语音转码失败: {}", absolutePathStr);
        return null;
    }
}

package com.qqai.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
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
                // 本地文件路径：直接复制
                Path sourcePath = Paths.get(mediaUrl);
                if (!Files.exists(sourcePath)) {
                    log.warn("本地媒体文件不存在: {}", mediaUrl);
                    return null;
                }
                Files.copy(sourcePath, localPath, StandardCopyOption.REPLACE_EXISTING);
                log.info("复制本地媒体文件: {} -> {}", mediaUrl, localPath);
            } else {
                // 远程 URL：走 HTTP 下载
                URL url = new URL(mediaUrl);
                HttpURLConnection connection = (HttpURLConnection) url.openConnection();
                connection.setRequestMethod("GET");
                connection.setConnectTimeout(10000);
                connection.setReadTimeout(60000);
                connection.setRequestProperty("User-Agent", "Mozilla/5.0");

                if (connection.getResponseCode() != HttpURLConnection.HTTP_OK) {
                    log.warn("下载{}失败，HTTP状态码: {}", mediaType, connection.getResponseCode());
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

    private boolean isLocalFilePath(String path) {
        if (path == null || path.isEmpty()) {
            return false;
        }
        // Windows 盘符路径或 Unix 绝对路径
        return path.matches("^[A-Za-z]:\\\\.*$") || path.startsWith("/") || path.startsWith("\\\\");
    }

    private String resolveFfmpegPath() {
        File ffmpegFile = new File(ffmpegPath);
        return ffmpegFile.exists() ? ffmpegPath : "ffmpeg";
    }
}

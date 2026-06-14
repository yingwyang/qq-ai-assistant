package com.qqai.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
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

    public String downloadMediaToLocal(String cqMessage, String groupId, String mediaType, String defaultExt) {
        try {
            String mediaUrl = extractUrlFromCQ(cqMessage);
            if (mediaUrl == null || mediaUrl.isEmpty()) {
                log.warn("无法从CQ码中提取URL: {}", cqMessage);
                return null;
            }

            String dateFolder = LocalDateTime.now().toLocalDate().toString();
            Path groupDir = Paths.get(localImagePath, mediaType, groupId, dateFolder);
            Files.createDirectories(groupDir);

            String fileName = UUID.randomUUID() + defaultExt;
            Path localPath = groupDir.resolve(fileName);

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

            if ("voice".equals(mediaType) && ".amr".equalsIgnoreCase(defaultExt)) {
                String mp3Path = convertAmrToMp3(localPath.toString());
                if (mp3Path != null) {
                    Path mp3FileName = Paths.get(mp3Path).getFileName();
                    String relativePath = "/images/" + mediaType + "/" + groupId + "/" + dateFolder + "/" + mp3FileName;
                    Files.deleteIfExists(localPath);
                    return relativePath;
                }
            }

            return "/images/" + mediaType + "/" + groupId + "/" + dateFolder + "/" + fileName;
        } catch (Exception e) {
            log.error("下载{}到本地时出错: {}", mediaType, e.getMessage());
            return null;
        }
    }

    public String downloadGroupAvatarToLocal(String groupId) {
        try {
            String imageUrl = "https://p.qlogo.cn/gh/" + groupId + "/" + groupId + "/100";
            Path avatarDir = Paths.get(localImagePath, "avatars");
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

            return "/images/avatars/" + fileName;
        } catch (Exception e) {
            log.error("下载群头像到本地时出错: {}", e.getMessage());
            return null;
        }
    }

    public String extractUrlFromCQ(String cqMessage) {
        Pattern pattern = Pattern.compile("url=`([^`]+)`");
        Matcher matcher = pattern.matcher(cqMessage);
        if (matcher.find()) {
            return matcher.group(1).replace("&amp;", "&");
        }

        pattern = Pattern.compile("url=([^,\\]]+)");
        matcher = pattern.matcher(cqMessage);
        if (matcher.find()) {
            return matcher.group(1).replace("&amp;", "&");
        }
        return null;
    }

    private String convertAmrToMp3(String amrPath) {
        try {
            Path amrFile = Paths.get(amrPath);
            if (!Files.exists(amrFile)) {
                return null;
            }

            String mp3Path = amrPath.replace(".amr", ".mp3");
            String executable = resolveFfmpegPath();

            ProcessBuilder pb = new ProcessBuilder(
                    executable,
                    "-i", amrPath,
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
            log.warn("AMR转MP3失败，退出码: {}", exitCode);
            return null;
        } catch (Exception e) {
            log.error("AMR转MP3时出错: {}", e.getMessage());
            return null;
        }
    }

    private String resolveFfmpegPath() {
        File ffmpegFile = new File(ffmpegPath);
        return ffmpegFile.exists() ? ffmpegPath : "ffmpeg";
    }
}

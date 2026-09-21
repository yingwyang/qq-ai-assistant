package com.qqai.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.databind.JsonNode;

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

    @Value("${napcat.onebot-api-url:http://localhost:6100}")
    private String napCatApiUrl;

    @Value("${napcat.token:}")
    private String napCatToken;

    /** 普通媒体(图片等)最大下载体积,单位 MB */
    @Value("${file.download.max-size-mb:100}")
    private int defaultMaxSizeMb;

    /** 视频最大下载体积,单位 MB(QQ 视频常超过 100MB) */
    @Value("${file.download.max-video-size-mb:300}")
    private int videoMaxSizeMb;

    @Autowired
    @Lazy
    private NapCatService napCatService;

    /**
     * 解析 NapCat API URL 中的主机名，用于 SSRF 白名单
     */
    private String napCatHost;

    @jakarta.annotation.PostConstruct
    private void init() {
        try {
            URL url = new URL(napCatApiUrl);
            this.napCatHost = url.getHost().toLowerCase();
        } catch (Exception e) {
            this.napCatHost = "localhost";
        }
        log.info("NapCat SSRF 白名单主机: {}", napCatHost);
    }

    public String downloadMediaToLocal(String cqMessage, String groupId, String mediaType, String defaultExt) {
        return downloadMediaToLocal(cqMessage, groupId, mediaType, defaultExt, null, false);
    }

    public String downloadMediaToLocal(String cqMessage, String groupId, String mediaType, String defaultExt, String fileId) {
        return downloadMediaToLocal(cqMessage, groupId, mediaType, defaultExt, fileId, false);
    }

    /**
     * @param trustedSource 消息段已佐证(true)时,允许复制 uploads 之外的 QQ 本地缓存文件
     *                      (视频消息的 url 即为此类本地绝对路径)
     */
    public String downloadMediaToLocal(String cqMessage, String groupId, String mediaType, String defaultExt,
                                       String fileId, boolean trustedSource) {
        try {
            String mediaUrl = extractUrlFromCQ(cqMessage);
            String cqFileId = extractFileFromCQ(cqMessage);
            String effectiveFileId = (fileId != null && !fileId.isBlank()) ? fileId : cqFileId;

            String dateFolder = LocalDateTime.now().toLocalDate().toString();
            Path groupDir = Paths.get(localImagePath, mediaType, groupId, dateFolder);
            Files.createDirectories(groupDir);

            String fileName = UUID.randomUUID() + defaultExt;
            Path localPath = groupDir.resolve(fileName);

            String result = downloadFromSources(cqMessage, mediaUrl, effectiveFileId, localPath,
                    groupId, mediaType, dateFolder, fileName, trustedSource);
            if (result != null) {
                return result;
            }

            // ===== 第 3 级:视频缩略图兜底 =====
            // QQ(NT) 默认不在本地保留视频原片(仅缩略图),NapCat 上报的本地路径往往已失效;
            // 此时落盘 QQ 缩略图作为预览,避免前端只剩"[视频已过期]"。
            if ("video".equals(mediaType)) {
                String thumb = tryVideoThumbnailFallback(mediaUrl, groupDir, groupId, mediaType, dateFolder);
                if (thumb != null) {
                    return thumb;
                }
            }
            return null;
        } catch (Exception e) {
            log.error("下载{}到本地时出错: {}", mediaType, e.getMessage(), e);
            return null;
        }
    }

    /**
     * 依次尝试:CQ 直连 → NapCat get_file 兜底。返回相对路径或 null。
     */
    private String downloadFromSources(String cqMessage, String mediaUrl, String effectiveFileId, Path localPath,
                                       String groupId, String mediaType, String dateFolder, String fileName,
                                       boolean trustedSource) throws IOException {
        // ===== 第 1 级:CQ 码中的 url/path 直连 =====
        if (mediaUrl != null && !mediaUrl.isEmpty()) {
            String result = trySource(mediaUrl, localPath, groupId, mediaType, dateFolder, fileName, trustedSource);
            if (result != null) {
                return result;
            }
            log.warn("CQ 直连获取失败,改用 NapCat get_file 兜底: mediaType={}, fileId={}, url={}",
                    mediaType, effectiveFileId, mediaUrl);
        } else {
            log.warn("CQ 码中无 url/path,直接使用 NapCat get_file: mediaType={}, fileId={}, cq={}",
                    mediaType, effectiveFileId, cqMessage);
        }

        // ===== 第 2 级:NapCat get_file 兜底 =====
        if (effectiveFileId == null || effectiveFileId.isBlank()) {
            log.error("无法获取媒体: CQ 码既无可用 url/path,也没有 fileId 可用于 get_file。cq={}", cqMessage);
            return null;
        }
        String resolved = resolveFileViaNapCatApi(cqMessage, effectiveFileId);
        if (resolved == null) {
            log.error("NapCat get_file 未能解析出可下载地址: fileId={}, cq={}", effectiveFileId, cqMessage);
            return null;
        }
        String result = trySource(resolved, localPath, groupId, mediaType, dateFolder, fileName, true);
        if (result == null) {
            log.error("get_file 解析出的地址仍无法获取媒体: fileId={}, resolved={}", effectiveFileId, resolved);
        }
        return result;
    }

    /**
     * 视频缩略图兜底:由视频原片路径推导 QQ 缩略图路径
     * (...\Video\<yyyy-MM>\Ori\<uuid>.mp4 → ...\Video\<yyyy-MM>\Thumb\<uuid>_0.png)。
     *
     * @return 缩略图相对访问路径,找不到返回 null
     */
    private String tryVideoThumbnailFallback(String mediaUrl, Path groupDir, String groupId,
                                             String mediaType, String dateFolder) {
        try {
            if (mediaUrl == null || mediaUrl.isBlank() || !isLocalFilePath(mediaUrl)) {
                return null;
            }
            String normalized = mediaUrl.replace('\\', '/');
            int oriIdx = normalized.toLowerCase().lastIndexOf("/ori/");
            if (oriIdx < 0) {
                return null;
            }
            String prefix = normalized.substring(0, oriIdx);           // .../Video/2026-09
            String fileName = normalized.substring(oriIdx + 5);        // <uuid>.mp4
            int dot = fileName.lastIndexOf('.');
            String base = dot > 0 ? fileName.substring(0, dot) : fileName;
            Path thumbPath = Paths.get(prefix + "/Thumb/" + base + "_0.png");
            if (!Files.exists(thumbPath)) {
                log.warn("视频原片缺失且未找到缩略图: {}", thumbPath);
                return null;
            }
            String thumbName = UUID.randomUUID() + ".png";
            Path target = groupDir.resolve(thumbName);
            Files.copy(thumbPath, target, StandardCopyOption.REPLACE_EXISTING);
            String relative = "/images/" + mediaType + "/" + groupId + "/" + dateFolder + "/" + thumbName;
            log.info("视频原片不可用,已落盘 QQ 缩略图作为预览: {} -> {}", thumbPath, relative);
            return relative;
        } catch (Exception e) {
            log.warn("视频缩略图兜底失败: {}", e.getMessage());
            return null;
        }
    }

    /**
     * 按来源类型获取媒体到本地路径。
     *
     * @param source  HTTP(S) URL 或本地绝对路径
     * @param trusted true = 来自 NapCat get_file 的可信结果(允许 uploads 之外的本地缓存路径);
     *                false = 来自消息 CQ 码(必须是 media 目录内且扩展名匹配,防任意文件拷贝)
     * @return 相对访问路径,失败返回 null
     */
    private String trySource(String source, Path localPath, String groupId, String mediaType,
                             String dateFolder, String fileName, boolean trusted) throws IOException {
        if (isLocalFilePath(source)) {
            Path sourcePath = Paths.get(source).toAbsolutePath().normalize();
            if (!Files.exists(sourcePath)) {
                log.warn("本地媒体文件不存在: {}", sourcePath);
                return null;
            }
            boolean extOk = isAllowedExtensionForType(sourcePath.getFileName().toString(), mediaType);
            if (trusted) {
                // 可信来源(NapCat 消息段/ get_file 结果):QQ 本地缓存视频的可能路径
                // 仍要求它落在已知媒体目录内,避免被诱导读取系统文件
                if (!isKnownMediaPath(sourcePath)) {
                    log.warn("【安全】可信来源但路径不在已知媒体目录内,拒绝复制: {}", sourcePath);
                    return null;
                }
                if (!extOk && hasExtension(sourcePath.getFileName().toString())) {
                    log.warn("【安全】可信来源扩展名与媒体类型不符,拒绝复制: {}", sourcePath);
                    return null;
                }
            } else {
                if (!extOk) {
                    log.warn("【安全】本地文件扩展名与媒体类型不符,拒绝复制: {}", source);
                    return null;
                }
                Path uploadsRoot = Paths.get("uploads").toAbsolutePath().normalize();
                Path imagesRoot = Paths.get(localImagePath).toAbsolutePath().normalize();
                if (!sourcePath.startsWith(uploadsRoot) && !sourcePath.startsWith(imagesRoot)) {
                    log.warn("【安全】本地文件路径越界,拒绝复制: {}", sourcePath);
                    return null;
                }
            }
            Files.copy(sourcePath, localPath, StandardCopyOption.REPLACE_EXISTING);
            log.info("复制本地媒体文件{}: {} -> {}", trusted ? "(NapCat可信)" : "", source, localPath);
            return finishLocalFile(localPath, groupId, mediaType, dateFolder, fileName);
        }
        return downloadFromUrl(source, localPath, groupId, mediaType, dateFolder, fileName);
    }

    private boolean hasExtension(String fileName) {
        if (fileName == null) return false;
        int dot = fileName.lastIndexOf('.');
        return dot > 0 && dot < fileName.length() - 1;
    }

    /**
     * 已知媒体目录判定:QQ(NT) 缓存 / NapCat 缓存 / 本服务 uploads。
     * 用于放行可信来源的本地视频路径,同时排除系统文件等敏感位置。
     */
    private boolean isKnownMediaPath(Path path) {
        String p = path.toString().toLowerCase().replace('\\', '/');
        if (p.contains("/nt_qq/") || p.contains("/nt_data/") || p.contains("tencent files")) return true;
        if (p.contains("/napcat")) return true;
        try {
            String uploadsRoot = Paths.get("uploads").toAbsolutePath().normalize().toString().toLowerCase().replace('\\', '/');
            return p.startsWith(uploadsRoot);
        } catch (Exception e) {
            // 路径归一化失败时按"非受控媒体路径"处理（不自动清理），留痕便于排查
            log.warn("判断媒体路径失败，按非已知路径处理: path={}, err={}", path, e.toString());
            return false;
        }
    }

    /**
     * 本地文件落盘后的收尾:语音转 mp3(可选),返回前端可访问的相对路径。
     */
    private String finishLocalFile(Path localPath, String groupId, String mediaType,
                                   String dateFolder, String fileName) {
        if ("voice".equals(mediaType)) {
            String mp3Path = convertVoiceToMp3(localPath.toString());
            if (mp3Path != null) {
                Path mp3FileName = Paths.get(mp3Path).getFileName();
                try {
                    Files.deleteIfExists(localPath);
                } catch (IOException e) {
                    // 清理旧转码产物失败不影响本次返回，记调试日志
                    log.debug("删除旧音频文件失败: file={}, err={}", localPath, e.toString());
                }
                return "/images/" + mediaType + "/" + groupId + "/" + dateFolder + "/" + mp3FileName;
            }
        }
        return "/images/" + mediaType + "/" + groupId + "/" + dateFolder + "/" + fileName;
    }

    /**
     * 从远程 URL 下载文件到本地路径，返回相对访问路径（成功）或 null（失败）。
     */
    private String downloadFromUrl(String mediaUrl, Path localPath, String groupId,
                                   String mediaType, String dateFolder, String fileName) throws IOException {
        if (isBlockedUrl(mediaUrl)) {
            log.warn("SSRF防护：拒绝访问内网/敏感地址: {}", mediaUrl);
            return null;
        }
        URL url = new URL(mediaUrl);
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        connection.setRequestMethod("GET");
        connection.setConnectTimeout(15000);
        connection.setReadTimeout(120000);
        connection.setRequestProperty("User-Agent", "Mozilla/5.0");
        // 仅对 NapCat 自身的文件服务附带 token;QQ CDN 等第三方地址不应带 Authorization,
        // 否则部分 CDN 会因未知认证头拒绝请求(视频下载常见 403)。
        String targetHost = url.getHost() == null ? "" : url.getHost().toLowerCase();
        boolean isNapCatHost = targetHost.equals(napCatHost)
                || ("localhost".equals(napCatHost) && ("127.0.0.1".equals(targetHost) || "0.0.0.0".equals(targetHost)));
        if (isNapCatHost && napCatToken != null && !napCatToken.isBlank()) {
            connection.setRequestProperty("Authorization", "Bearer " + napCatToken);
        }

        int code = connection.getResponseCode();
        if (code != HttpURLConnection.HTTP_OK) {
            log.warn("下载{}失败，HTTP状态码: {}, url={}", mediaType, code, mediaUrl);
            return null;
        }

        long maxBytes = maxDownloadBytes(mediaType);
        long contentLength = connection.getContentLengthLong();
        if (contentLength > maxBytes) {
            log.warn("下载{}失败，文件大小 {} bytes 超过限制 {} bytes", mediaType, contentLength, maxBytes);
            return null;
        }

        try (InputStream inputStream = connection.getInputStream();
             FileOutputStream outputStream = new FileOutputStream(localPath.toFile())) {
            byte[] buffer = new byte[8192];
            int bytesRead;
            long totalRead = 0;
            while ((bytesRead = inputStream.read(buffer)) != -1) {
                totalRead += bytesRead;
                if (totalRead > maxBytes) {
                    throw new IOException("下载文件超过大小限制: " + maxBytes + " bytes");
                }
                outputStream.write(buffer, 0, bytesRead);
            }
        }

        // 校验下载的文件是否为有效媒体（非 JSON 错误响应）
        long fileSize = Files.size(localPath);
        if (fileSize < 1024) {
            // 文件太小，可能是错误响应，读取内容检查
            String contentPreview = Files.readString(localPath, java.nio.charset.StandardCharsets.UTF_8);
            if (contentPreview.trim().startsWith("{") || contentPreview.contains("\"status\":\"failed\"")) {
                log.warn("下载{}失败，返回错误响应: {}", mediaType, contentPreview);
                Files.deleteIfExists(localPath);
                return null;
            }
        }

        log.info("媒体下载完成: type={}, bytes={}, url={}", mediaType, fileSize, mediaUrl);
        return finishLocalFile(localPath, groupId, mediaType, dateFolder, fileName);
    }

    /**
     * 各媒体类型允许的最大下载体积(字节)。视频默认放宽到 300MB(可在配置中调整)。
     */
    private long maxDownloadBytes(String mediaType) {
        if ("video".equals(mediaType)) {
            return Math.max(1, videoMaxSizeMb) * 1024L * 1024L;
        }
        return Math.max(1, defaultMaxSizeMb) * 1024L * 1024L;
    }

    /**
     * 通过 NapCat get_file API 解析文件对应的实际可下载地址。
     * 适用于视频/语音等上报为 QQ 本地缓存路径、后端无法直接访问的场景。
     */
    private String resolveFileViaNapCatApi(String cqMessage, String fileId) {
        // 优先使用传入的 fileId，否则从 CQ 码提取
        String effectiveFileId = (fileId != null && !fileId.isBlank()) ? fileId : extractFileFromCQ(cqMessage);
        if (effectiveFileId == null || effectiveFileId.isBlank()) {
            return null;
        }
        log.info("调用 NapCat get_file: fileId={}", effectiveFileId);
        try {
            JsonNode data = napCatService.getFile(effectiveFileId);
            if (data == null) return null;
            // 优先使用 url 字段（HTTP 可下载地址）
            if (data.has("url") && !data.get("url").isNull()) {
                String url = data.get("url").asText().trim();
                if (!url.isEmpty()) return url;
            }
            // 其次使用 file 字段（本地绝对路径，通常在 NapCat 工作目录下）
            if (data.has("file") && !data.get("file").isNull()) {
                String path = data.get("file").asText().trim();
                if (!path.isEmpty()) return path;
            }
            log.warn("get_file 返回数据中无 url/file 字段: {}", data);
        } catch (Exception e) {
            log.error("通过 NapCat get_file 解析文件失败: {}", e.getMessage());
        }
        return null;
    }

    /**
     * 从 CQ 码中提取 file 字段（文件 ID）。
     */
    public String extractFileFromCQ(String cqMessage) {
        if (cqMessage == null) return null;
        Pattern pattern = Pattern.compile("file=`([^`]+)`");
        Matcher matcher = pattern.matcher(cqMessage);
        if (matcher.find()) return matcher.group(1).trim();
        pattern = Pattern.compile("file=([^,\\]]+)");
        matcher = pattern.matcher(cqMessage);
        if (matcher.find()) return matcher.group(1).trim();
        return null;
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
     * 检查 URL 是否被 SSRF 防护拦截。
     * NapCat 配置的主机名会被加入白名单（因为 NapCat 是可信的内部服务）。
     */
    public boolean isBlockedUrl(String urlStr) {
        if (urlStr == null || urlStr.isEmpty()) return true;
        try {
            URL url = new URL(urlStr);
            String protocol = url.getProtocol();
            if (!"http".equalsIgnoreCase(protocol) && !"https".equalsIgnoreCase(protocol)) {
                return true; // 拒绝非 HTTP/HTTPS
            }
            String host = url.getHost().toLowerCase();
            // NapCat 主机白名单（localhost 同时允许 127.0.0.1 / 0.0.0.0）
            if (host.equals(napCatHost)) {
                return false;
            }
            if ("localhost".equals(napCatHost) && ("127.0.0.1".equals(host) || "0.0.0.0".equals(host))) {
                return false;
            }
            return isBlockedHost(host);
        } catch (Exception e) {
            // 安全判定失败按"阻断"处理（fail-closed），这是安全决策，必须留下 WARN
            log.warn("下载地址安全判定失败，按阻断处理: url={}, err={}", urlStr, e.toString());
            return true;
        }
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

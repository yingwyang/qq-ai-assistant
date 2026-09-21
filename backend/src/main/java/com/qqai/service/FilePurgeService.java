package com.qqai.service;

import com.qqai.entity.FileRecord;
import com.qqai.entity.Message;
import com.qqai.repository.FileRecordRepository;
import com.qqai.repository.MessageRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

/**
 * 清理媒体文件服务（图片/视频/语音/文件）
 *
 * 递归扫描 baseDir 下的若干子目录，匹配指定扩展名文件，删除之并返回统计信息。
 * 失败的文件会被跳过，不抛出异常。
 */
@Service
public class FilePurgeService {

    private static final Logger log = LoggerFactory.getLogger(FilePurgeService.class);

    /** 媒体文件根目录：相对于项目根目录下的 backend/uploads */
    @Value("${file.purge.base-dir:uploads}")
    private String baseDir;

    @Autowired
    private FileRecordRepository fileRecordRepository;

    @Autowired
    private MessageRepository messageRepository;

    @Autowired
    private AuditLogService auditLogService;

    /** 本地媒体文件存储根目录 */
    @Value("${file.storage.local-path:./uploads/images}")
    private String localStoragePath;

    /**
     * 媒体目录扫描结果缓存（按类型分组，30 秒）。
     * 与 TokenBlacklistService 一样直接用 Caffeine，不引入 Spring Cache 抽象。
     */
    private final com.github.benmanes.caffeine.cache.Cache<String, List<MediaFileDto>> mediaScanCache =
            com.github.benmanes.caffeine.cache.Caffeine.newBuilder()
                    .expireAfterWrite(java.time.Duration.ofSeconds(30))
                    .maximumSize(16)
                    .build();

    private final com.github.benmanes.caffeine.cache.Cache<String, MediaSummary> mediaSummaryCache =
            com.github.benmanes.caffeine.cache.Caffeine.newBuilder()
                    .expireAfterWrite(java.time.Duration.ofSeconds(30))
                    .maximumSize(4)
                    .build();

    private static final String MEDIA_SUMMARY_KEY = "summary";

    /** 图片扩展名（小写比较） */
    private static final Set<String> IMAGE_EXTS = Set.of(
            ".jpg", ".jpeg", ".png", ".gif", ".webp", ".bmp", ".svg", ".tif", ".tiff"
    );

    /** 视频扩展名 */
    private static final Set<String> VIDEO_EXTS = Set.of(
            ".mp4", ".mov", ".avi", ".mkv", ".flv", ".wmv", ".webm", ".m4v"
    );

    /** 语音/音频扩展名 */
    private static final Set<String> AUDIO_EXTS = Set.of(
            ".mp3", ".wav", ".wma", ".ogg", ".aac", ".flac", ".m4a", ".amr"
    );

    /** 通用文件扩展名（可选） */
    private static final Set<String> FILE_EXTS = Set.of(
            ".pdf", ".doc", ".docx", ".xls", ".xlsx", ".ppt", ".pptx", ".txt", ".zip", ".rar", ".7z"
    );

    /**
     * 执行清理操作。
     *
     * @param types 要清理的文件类型，支持 IMAGE / VIDEO / AUDIO / FILE / ALL
     * @return 清理结果统计
     */
    public PurgeResult purgeMedia(List<String> types) {
        if (types == null || types.isEmpty()) {
            return new PurgeResult(0, 0L, Collections.emptyList(), "未指定类型，跳过");
        }

        Set<String> finalExts = new HashSet<>();
        for (String t : types) {
            if (t == null) continue;
            switch (t.toUpperCase()) {
                case "IMAGE":
                    finalExts.addAll(IMAGE_EXTS); break;
                case "VIDEO":
                    finalExts.addAll(VIDEO_EXTS); break;
                case "AUDIO":
                    finalExts.addAll(AUDIO_EXTS); break;
                case "FILE":
                    finalExts.addAll(FILE_EXTS); break;
                case "ALL":
                    finalExts.addAll(IMAGE_EXTS);
                    finalExts.addAll(VIDEO_EXTS);
                    finalExts.addAll(AUDIO_EXTS);
                    finalExts.addAll(FILE_EXTS);
                    break;
                default:
                    // 未知类型忽略
            }
        }

        if (finalExts.isEmpty()) {
            return new PurgeResult(0, 0L, Collections.emptyList(), "未匹配到任何扩展名，跳过");
        }

        Path root = getBasePath();
        File rootFile = root.toFile();
        if (!rootFile.exists() || !rootFile.isDirectory()) {
            log.info("媒体目录不存在: {}", root);
            return new PurgeResult(0, 0L, Collections.emptyList(),
                    "媒体目录不存在: " + root.toAbsolutePath());
        }

        List<String> deletedFiles = new ArrayList<>();
        long totalBytes = 0L;
        int totalFiles = 0;

        try (Stream<Path> walk = Files.walk(root)) {
            List<Path> paths = walk
                    .filter(Files::isRegularFile)
                    .filter(p -> !isAvatarPath(root, p))
                    .filter(p -> matchExtension(p, finalExts))
                    .toList();

            for (Path p : paths) {
                File f = p.toFile();
                long size = f.length();
                if (f.delete()) {
                    totalFiles++;
                    totalBytes += size;
                    deletedFiles.add(root.relativize(p).toString());
                    deactivateFileRecordIfExists(p, root);
                    softDeleteMessagesReferencingFile(root.relativize(p).toString().replace('\\', '/'));
                } else {
                    log.warn("无法删除文件: {}", f.getAbsolutePath());
                }
            }
        } catch (IOException e) {
            log.warn("扫描媒体目录失败: {}", e.getMessage());
            return new PurgeResult(totalFiles, totalBytes, deletedFiles,
                    "扫描过程中发生 IO 错误: " + e.getMessage());
        }

        log.info("purgeMedia finished: {} files, {} bytes", totalFiles, totalBytes);
        return new PurgeResult(totalFiles, totalBytes, deletedFiles, null);
    }

    /**
     * 执行清理操作，并写入审计日志。
     *
     * @param types    要清理的文件类型
     * @param operator 操作人用户名
     */
    public PurgeResult purgeMedia(List<String> types, String operator) {
        PurgeResult result = purgeMedia(types);
        invalidateMediaCaches();
        String action = "FILE_PURGE";
        String target = "types:" + (types == null ? "[]" : types);
        if (result.getTotalDeleted() > 0 || (result.getNote() != null && result.getNote().contains("IO 错误"))) {
            auditLogService.log(operator, action, target, "SUCCESS",
                    "清理 " + result.getTotalDeleted() + " 个文件, 释放 " + result.getFreedMB());
        } else {
            auditLogService.log(operator, action, target, "SUCCESS",
                    result.getNote() != null ? result.getNote() : "无文件被清理");
        }
        return result;
    }

    private boolean matchExtension(Path p, Set<String> exts) {
        String name = p.getFileName().toString().toLowerCase();
        int dot = name.lastIndexOf('.');
        if (dot < 0) return false;
        return exts.contains(name.substring(dot));
    }

    /**
     * 判断文件是否位于头像目录下，避免清理时误删头像。
     * 覆盖 uploads/avatars 隔离目录以及旧版 uploads/images/avatars 目录。
     */
    private boolean isAvatarPath(Path root, Path p) {
        try {
            String relative = root.relativize(p).toString().replace('\\', '/');
            return relative.equals("avatars") || relative.startsWith("avatars/")
                    || relative.equals("images/avatars") || relative.startsWith("images/avatars/");
        } catch (Exception e) {
            // 相对路径计算失败时按"不是头像路径"处理（交给清理逻辑判断），但要留痕
            log.warn("判断头像路径失败，按非头像路径处理: path={}, err={}", p, e.toString());
            return false;
        }
    }

    private String detectFileType(String filename) {
        String name = filename == null ? "" : filename.toLowerCase();
        int dot = name.lastIndexOf('.');
        if (dot < 0) return "FILE";
        String ext = name.substring(dot);
        if (IMAGE_EXTS.contains(ext)) return "IMAGE";
        if (VIDEO_EXTS.contains(ext)) return "VIDEO";
        if (AUDIO_EXTS.contains(ext)) return "AUDIO";
        return "FILE";
    }

    private Path getBasePath() {
        return Paths.get(baseDir).toAbsolutePath().normalize();
    }

    /**
     * 分页扫描本地媒体目录，返回可直接展示的文件列表。
     *
     * @param type     文件类型：IMAGE / VIDEO / AUDIO / FILE / ALL 或 null/空
     * @param pageable 分页参数
     * @return 媒体文件 DTO 分页结果
     */
    public Page<MediaFileDto> listMediaFiles(String type, Pageable pageable) {
        return listMediaFiles(type, pageable, null, null, null, null);
    }

    /**
     * 带筛选与排序的媒体文件列表。
     *
     * <p>实现说明：媒体文件不是数据库记录而是磁盘文件，筛选/排序只能在扫描结果上做，
     * 因此每次都需遍历 uploads 目录。为避免「改筛选条件/翻页/改每页条数」把全量遍历
     * 放大成多次，这里用 Caffeine 缓存 30 秒内的扫描结果，清理/删除成功后立即失效。</p>
     *
     * @param type      IMAGE / VIDEO / AUDIO / FILE / ALL(空)
     * @param pageable  分页参数
     * @param keyword   文件名关键字（忽略大小写，可为空）
     * @param from      修改时间起（含，可为空）
     * @param to        修改时间止（含，可为空）
     * @param sortSpec  排序，格式 time|size|name + 方向（如 "size,desc"），默认 time,desc
     */
    public Page<MediaFileDto> listMediaFiles(String type, Pageable pageable, String keyword,
                                            LocalDate from, LocalDate to, String sortSpec) {
        List<MediaFileDto> allFiles = scanMediaFiles(type);

        String kw = keyword == null ? null : keyword.trim().toLowerCase();
        List<MediaFileDto> filtered = new ArrayList<>();
        for (MediaFileDto dto : allFiles) {
            if (kw != null && !kw.isEmpty()) {
                String name = dto.getFileName() == null ? "" : dto.getFileName().toLowerCase();
                if (!name.contains(kw)) continue;
            }
            if (from != null || to != null) {
                LocalDateTime ts = dto.getCreatedAt();
                if (ts == null) continue;
                if (from != null && ts.isBefore(from.atStartOfDay())) continue;
                if (to != null && ts.isAfter(LocalDateTime.of(to, LocalTime.MAX))) continue;
            }
            filtered.add(dto);
        }

        sortMediaFiles(filtered, sortSpec);

        int total = filtered.size();
        int page = pageable.getPageNumber();
        int size = pageable.getPageSize();
        int fromIdx = Math.min(page * size, total);
        int toIdx = Math.min(fromIdx + size, total);
        List<MediaFileDto> content = fromIdx < total ? filtered.subList(fromIdx, toIdx) : Collections.emptyList();
        return new PageImpl<>(content, pageable, total);
    }

    /**
     * 各类型媒体文件的数量与占用（供「清理预览」展示，避免误清理）。
     */
    public MediaSummary summarizeMediaFiles() {
        return mediaSummaryCache.get(MEDIA_SUMMARY_KEY, key -> buildMediaSummary());
    }

    private MediaSummary buildMediaSummary() {
        List<MediaFileDto> all = scanMediaFiles(null);
        Map<String, long[]> byType = new LinkedHashMap<>();
        for (String t : List.of("IMAGE", "VIDEO", "AUDIO", "FILE")) {
            byType.put(t, new long[]{0L, 0L});
        }
        long totalCount = 0;
        long totalBytes = 0;
        for (MediaFileDto dto : all) {
            String fileType = dto.getFileType() == null ? "FILE" : dto.getFileType().toUpperCase();
            long[] slot = byType.computeIfAbsent(fileType, k -> new long[]{0L, 0L});
            slot[0]++;
            slot[1] += dto.getFileSize();
            totalCount++;
            totalBytes += dto.getFileSize();
        }
        MediaSummary summary = new MediaSummary();
        for (Map.Entry<String, long[]> entry : byType.entrySet()) {
            summary.put(entry.getKey(), entry.getValue()[0], entry.getValue()[1]);
        }
        summary.put("TOTAL", totalCount, totalBytes);
        return summary;
    }

    /** 目录扫描结果缓存：筛选/翻页会反复触发全量遍历，缓存 30s；改造/删除后立即失效 */
    private List<MediaFileDto> scanMediaFiles(String type) {
        String key = type == null || type.isBlank() ? "ALL" : type.toUpperCase();
        return mediaScanCache.get(key, k -> scanMediaFilesUncached(k));
    }

    private void invalidateMediaCaches() {
        mediaScanCache.invalidateAll();
        mediaSummaryCache.invalidateAll();
    }

    private List<MediaFileDto> scanMediaFilesUncached(String type) {
        Path root = getBasePath();
        File rootFile = root.toFile();
        if (!rootFile.exists() || !rootFile.isDirectory()) {
            log.info("媒体目录不存在: {}", root);
            return Collections.emptyList();
        }

        Set<String> targetExts = resolveExtensions(type);
        List<MediaFileDto> allFiles = new ArrayList<>();

        try (Stream<Path> walk = Files.walk(root)) {
            walk
                .filter(Files::isRegularFile)
                .filter(p -> !isAvatarPath(root, p))
                .filter(p -> targetExts.isEmpty() || matchExtension(p, targetExts))
                .forEach(p -> {
                    File f = p.toFile();
                    String relative = root.relativize(p).toString().replace('\\', '/');
                    String fileType = detectFileType(f.getName());
                    allFiles.add(new MediaFileDto(
                            encodeId(relative),
                            f.getName(),
                            fileType,
                            f.length(),
                            toLocalDateTime(f.lastModified()),
                            "/uploads/" + relative,
                            relative
                    ));
                });
        } catch (IOException e) {
            log.warn("扫描媒体目录失败: {}", e.getMessage());
            return Collections.emptyList();
        }

        // 默认按修改时间倒序
        sortMediaFiles(allFiles, "time,desc");
        return allFiles;
    }

    /** 排序字段白名单：time / size / name，方向 asc / desc，非法值一律回落 time,desc */
    private void sortMediaFiles(List<MediaFileDto> files, String sortSpec) {
        String field = "time";
        boolean desc = true;
        if (sortSpec != null && !sortSpec.isBlank()) {
            String[] parts = sortSpec.split(",");
            String candidate = parts[0].trim().toLowerCase();
            if (Set.of("time", "size", "name").contains(candidate)) {
                field = candidate;
            }
            if (parts.length > 1) {
                desc = !"asc".equalsIgnoreCase(parts[1].trim());
            }
        }
        Comparator<MediaFileDto> comparator;
        switch (field) {
            case "size":
                comparator = Comparator.comparingLong(MediaFileDto::getFileSize);
                break;
            case "name":
                comparator = Comparator.comparing(f -> f.getFileName() == null ? "" : f.getFileName().toLowerCase());
                break;
            default:
                comparator = Comparator.comparing(MediaFileDto::getCreatedAt,
                        Comparator.nullsFirst(Comparator.naturalOrder()));
        }
        files.sort(desc ? comparator.reversed() : comparator);
    }

    private Set<String> resolveExtensions(String type) {
        Set<String> exts = new HashSet<>();
        if (type == null || type.isBlank() || "ALL".equalsIgnoreCase(type)) {
            return exts; // 空集合表示不限制
        }
        switch (type.toUpperCase()) {
            case "IMAGE":
                exts.addAll(IMAGE_EXTS); break;
            case "VIDEO":
                exts.addAll(VIDEO_EXTS); break;
            case "AUDIO":
                exts.addAll(AUDIO_EXTS); break;
            case "FILE":
                exts.addAll(FILE_EXTS); break;
            default:
                // 未知类型不过滤
        }
        return exts;
    }

    private String encodeId(String relativePath) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(relativePath.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    private String decodeId(String id) {
        return new String(Base64.getUrlDecoder().decode(id), java.nio.charset.StandardCharsets.UTF_8);
    }

    private LocalDateTime toLocalDateTime(long timestamp) {
        return LocalDateTime.ofInstant(Instant.ofEpochMilli(timestamp), ZoneId.systemDefault());
    }

    /**
     * 根据 ID（Base64 编码的相对路径）批量删除本地媒体文件。
     * 删除物理文件，并尝试将对应的 file_records 标记为 inactive。
     *
     * @param ids Base64 编码的相对路径列表
     * @return 删除结果统计
     */
    public PurgeResult deleteFilesByIds(List<String> ids) {
        if (ids == null || ids.isEmpty()) {
            return new PurgeResult(0, 0L, Collections.emptyList(), "未指定文件 ID，跳过");
        }

        Path root = getBasePath();
        List<String> deletedFiles = new ArrayList<>();
        int totalDeleted = 0;
        long totalBytes = 0L;
        int skipped = 0;

        for (String id : ids) {
            String relative;
            try {
                relative = decodeId(id);
            } catch (Exception e) {
                log.warn("无法解析文件 ID: {}", id);
                skipped++;
                continue;
            }

            Path filePath = root.resolve(relative).normalize();
            if (!filePath.startsWith(root)) {
                log.warn("非法文件路径，拒绝删除: {}", relative);
                skipped++;
                continue;
            }

            if (isAvatarPath(root, filePath)) {
                log.warn("拒绝删除头像文件: {}", relative);
                skipped++;
                continue;
            }

            File file = filePath.toFile();
            if (!file.exists()) {
                log.warn("物理文件不存在: {}", filePath);
                skipped++;
                continue;
            }

            long size = file.length();
            if (file.delete()) {
                totalDeleted++;
                totalBytes += size;
                deletedFiles.add(relative);
                deactivateFileRecordIfExists(filePath, root);
                softDeleteMessagesReferencingFile(relative);
            } else {
                log.warn("无法删除文件: {}", filePath);
                skipped++;
            }
        }

        String note = skipped > 0 ? "跳过 " + skipped + " 个无效或无法删除的文件" : null;
        return new PurgeResult(totalDeleted, totalBytes, deletedFiles, note);
    }

    /**
     * 根据 ID 批量删除本地媒体文件，并写入审计日志。
     *
     * @param ids     Base64 编码的相对路径列表
     * @param operator 操作人用户名
     */
    public PurgeResult deleteFilesByIds(List<String> ids, String operator) {
        PurgeResult result = deleteFilesByIds(ids);
        invalidateMediaCaches();
        auditLogService.log(operator, "FILE_DELETE", "files:" + (ids == null ? 0 : ids.size()),
                "SUCCESS",
                "删除 " + result.getTotalDeleted() + " 个文件, 释放 " + result.getFreedMB()
                        + (result.getNote() != null ? ", " + result.getNote() : ""));
        return result;
    }

    private void deactivateFileRecordIfExists(Path filePath, Path root) {
        try {
            String relative = root.relativize(filePath).toString().replace('\\', '/');
            fileRecordRepository.findByFileId(relative).ifPresent(record -> {
                record.setActive(false);
                fileRecordRepository.save(record);
            });
        } catch (Exception e) {
            log.debug("标记 file_records 失败: {}", e.getMessage());
        }
    }

    /**
     * 删除物理文件后，软删除内容中引用该文件的消息，避免前端继续加载已删除的媒体而报错。
     */
    private void softDeleteMessagesReferencingFile(String relativePath) {
        if (relativePath == null || relativePath.isEmpty() || messageRepository == null) {
            return;
        }
        try {
            String withSlash = "/" + relativePath;
            List<Message> messages = messageRepository.findByContentContainingPath(relativePath, withSlash);
            if (messages == null || messages.isEmpty()) {
                return;
            }
            LocalDateTime now = LocalDateTime.now();
            int count = 0;
            for (Message message : messages) {
                if (message.isDeleted()) {
                    continue;
                }
                message.setDeleted(true);
                message.setDeletedAt(now);
                message.setDeletedBy(null);
                messageRepository.save(message);
                count++;
            }
            if (count > 0) {
                log.info("文件删除后同步软删除 {} 条引用消息: {}", count, relativePath);
            }
        } catch (Exception e) {
            log.warn("同步软删除引用消息失败: {}", e.getMessage());
        }
    }

    /**
     * 将字节数格式化为可读字符串。
     */
    public String formatBytes(long bytes) {
        if (bytes < 1024) {
            return bytes + " B";
        }
        if (bytes < 1024 * 1024) {
            return String.format("%.2f KB", bytes / 1024.0);
        }
        if (bytes < 1024L * 1024 * 1024) {
            return String.format("%.2f MB", bytes / (1024.0 * 1024.0));
        }
        return String.format("%.2f GB", bytes / (1024.0 * 1024.0 * 1024.0));
    }

    /**
     * 清理结果（返回给 controller）
     */
    public static class PurgeResult {
        private final int totalDeleted;
        private final long freedBytes;
        private final List<String> deletedFiles;
        private final String note;

        public PurgeResult(int totalDeleted, long freedBytes, List<String> deletedFiles, String note) {
            this.totalDeleted = totalDeleted;
            this.freedBytes = freedBytes;
            this.deletedFiles = deletedFiles;
            this.note = note;
        }

        public int getTotalDeleted() { return totalDeleted; }
        public long getFreedBytes() { return freedBytes; }
        public List<String> getDeletedFiles() { return deletedFiles; }
        public String getNote() { return note; }

        /**
         * 返回释放空间 MB 格式字符串（保留两位小数）。
         */
        public String getFreedMB() {
            return String.format("%.2f MB", freedBytes / (1024.0 * 1024.0));
        }
    }

    /**
     * 本地媒体文件 DTO，直接对应 uploads 目录中的文件。
     */
    public static class MediaFileDto {
        private final String id;          // Base64 编码的相对路径
        private final String fileName;    // 文件名
        private final String fileType;    // IMAGE / VIDEO / AUDIO / FILE
        private final long fileSize;      // 字节
        private final LocalDateTime createdAt;
        private final String url;         // 访问 URL，如 /uploads/xxx.jpg
        private final String path;        // 相对路径展示

        public MediaFileDto(String id, String fileName, String fileType, long fileSize,
                            LocalDateTime createdAt, String url, String path) {
            this.id = id;
            this.fileName = fileName;
            this.fileType = fileType;
            this.fileSize = fileSize;
            this.createdAt = createdAt;
            this.url = url;
            this.path = path;
        }

        public String getId() { return id; }
        public String getFileName() { return fileName; }
        public String getFileType() { return fileType; }
        public long getFileSize() { return fileSize; }
        public LocalDateTime getCreatedAt() { return createdAt; }
        public String getUrl() { return url; }
        public String getPath() { return path; }
    }

    /** 各类型媒体文件的数量与占用体积（清理预览用） */
    public static class MediaSummary {
        private final Map<String, Map<String, Object>> byType = new LinkedHashMap<>();

        public void put(String type, long count, long bytes) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("count", count);
            item.put("bytes", bytes);
            byType.put(type, item);
        }

        public Map<String, Map<String, Object>> getByType() { return byType; }
    }
}

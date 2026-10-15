package com.qqai.common;

import com.qqai.exception.BizException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 上传文件类型策略：把配置项 {@code file.upload.allowed-types} 变成真正的校验。
 *
 * <p><b>为什么需要</b>：该配置此前在代码里<b>从未被读取</b>（死配置）。上传只校验逻辑上的
 * fileType（IMAGE/VIDEO/AUDIO/FILE）与体积，扩展名和 Content-Type 全部由客户端提供，
 * 于是 {@code .html} / {@code .svg} / {@code .exe} / 任意无扩展名文件都能落库并分发给别人。</p>
 *
 * <p>两条规则：</p>
 * <ol>
 *   <li><b>扩展名白名单</b>：配置里写 {@code image/*,video/*,audio/*} 这类通配会展开为具体扩展名，
 *       写 {@code .pdf} 就是单个扩展名；配置为空时不做限制（保持旧行为，避免误伤未配置环境）。</li>
 *   <li><b>Content-Type 由扩展名推导</b>，不采信客户端承诺的 MIME——否则可以把 {@code .html}
 *       声明成 {@code image/png} 存进去，后续按什么类型解释由读取方决定，形成 MIME 欺骗。</li>
 * </ol>
 */
@Component
public class UploadTypePolicy {

    private static final Logger log = LoggerFactory.getLogger(UploadTypePolicy.class);

    public static final String DEFAULT_CONTENT_TYPE = "application/octet-stream";

    /** 通配类别 → 允许的扩展名（配置里写 image/* 时展开为下列扩展名） */
    private static final Map<String, Set<String>> MIME_WILDCARD_GROUPS = Map.of(
            "image/*", Set.of("jpg", "jpeg", "png", "gif", "webp", "bmp"),
            "video/*", Set.of("mp4", "mov", "avi", "mkv", "webm"),
            "audio/*", Set.of("mp3", "wav", "amr", "silk", "m4a", "ogg", "flac", "aac")
    );

    /** 扩展名 → 安全 Content-Type（未收录的一律 octet-stream，交给浏览器当附件处理） */
    private static final Map<String, String> EXTENSION_CONTENT_TYPES = Map.ofEntries(
            Map.entry("jpg", "image/jpeg"), Map.entry("jpeg", "image/jpeg"),
            Map.entry("png", "image/png"), Map.entry("gif", "image/gif"),
            Map.entry("webp", "image/webp"), Map.entry("bmp", "image/bmp"),
            Map.entry("mp4", "video/mp4"), Map.entry("mov", "video/quicktime"),
            Map.entry("avi", "video/x-msvideo"), Map.entry("mkv", "video/x-matroska"),
            Map.entry("webm", "video/webm"),
            Map.entry("mp3", "audio/mpeg"), Map.entry("wav", "audio/wav"),
            Map.entry("amr", "audio/amr"), Map.entry("m4a", "audio/mp4"),
            Map.entry("ogg", "audio/ogg"), Map.entry("flac", "audio/flac"),
            Map.entry("aac", "audio/aac"), Map.entry("silk", "audio/silk"),
            Map.entry("pdf", "application/pdf"),
            Map.entry("doc", "application/msword"),
            Map.entry("docx", "application/vnd.openxmlformats-officedocument.wordprocessingml.document"),
            Map.entry("xls", "application/vnd.ms-excel"),
            Map.entry("xlsx", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"),
            Map.entry("ppt", "application/vnd.ms-powerpoint"),
            Map.entry("pptx", "application/vnd.openxmlformats-officedocument.presentationml.presentation"),
            Map.entry("txt", "text/plain"),
            Map.entry("zip", "application/zip")
    );

    private final Set<String> allowedExtensions = new LinkedHashSet<>();
    private final String rawConfig;

    public UploadTypePolicy(@Value("${file.upload.allowed-types:}") String rawConfig) {
        this.rawConfig = rawConfig == null ? "" : rawConfig;
        parse(this.rawConfig);
        log.info("上传类型白名单: {}", allowedExtensions.isEmpty() ? "(未配置：不限制类型)" : allowedExtensions);
    }

    private void parse(String raw) {
        for (String part : raw.split(",")) {
            String item = part.trim().toLowerCase(Locale.ROOT);
            if (item.isEmpty()) {
                continue;
            }
            Set<String> group = MIME_WILDCARD_GROUPS.get(item);
            if (group != null) {
                allowedExtensions.addAll(group);
            } else if (item.startsWith(".")) {
                allowedExtensions.add(item.substring(1));
            } else if (item.contains("/")) {
                // 其它 MIME 通配（如 application/*）暂不展开：宁可不放行，也不误放行
                log.warn("上传白名单暂不支持该 MIME 通配写法，已忽略: {}", item);
            } else {
                allowedExtensions.add(item);
            }
        }
    }

    /** 配置为空时不做类型限制（保持旧行为） */
    public boolean isEnforced() {
        return !allowedExtensions.isEmpty();
    }

    /** 取小写扩展名（不含点）；无扩展名返回 null */
    public String extensionOf(String filename) {
        if (filename == null) {
            return null;
        }
        String name = filename.trim();
        int slash = Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\'));
        if (slash >= 0) {
            name = name.substring(slash + 1);
        }
        int dot = name.lastIndexOf('.');
        if (dot < 0 || dot == name.length() - 1) {
            return null;
        }
        return name.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    public boolean isAllowedExtension(String filename) {
        if (!isEnforced()) {
            return true;
        }
        String ext = extensionOf(filename);
        return ext != null && allowedExtensions.contains(ext);
    }

    /** 校验并返回扩展名；不允许时抛 400（由 GlobalExceptionHandler 输出） */
    public String requireAllowedExtension(String filename) {
        if (!isAllowedExtension(filename)) {
            String ext = extensionOf(filename);
            throw new BizException(400, "FILE_TYPE_NOT_ALLOWED",
                    "不支持的文件类型" + (ext == null ? "（缺少扩展名）" : "：." + ext)
                            + "，仅允许: " + describeAllowed());
        }
        return extensionOf(filename);
    }

    /** 依据扩展名推导 Content-Type，不采信客户端提供的 MIME */
    public String resolveContentType(String filename) {
        String ext = extensionOf(filename);
        if (ext == null) {
            return DEFAULT_CONTENT_TYPE;
        }
        return EXTENSION_CONTENT_TYPES.getOrDefault(ext, DEFAULT_CONTENT_TYPE);
    }

    /** 允许的扩展名列表（错误提示用） */
    public String describeAllowed() {
        if (allowedExtensions.isEmpty()) {
            return "任意类型";
        }
        return allowedExtensions.stream().map(e -> "." + e).reduce((a, b) -> a + ", " + b).orElse("");
    }

    /** 供诊断：原始配置 */
    public String rawConfig() {
        return rawConfig;
    }
}

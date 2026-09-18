package com.qqai.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.qqai.entity.Message;
import com.qqai.exception.BizException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * 网页「发送媒体到 QQ 群」的落盘与消息段编排。
 *
 * <p>职责边界：{@link NapCatService} 只管「怎么发」（HTTP 调用 + 结果解析），
 * 本服务管「发什么」——文件落盘、图片走 base64 还是本地路径、段顺序、以及给 Controller
 * 的返回体字段。权限（{@code checkGroupAccess}）、限流、审计都留在 Controller，
 * 与本服务已有的 {@code GroupDigestService} 分工口径一致。</p>
 *
 * <p><b>落盘位置</b>：{@code <file.upload.base-dir>/outbound/<groupId>/<uuid>.<ext>}，
 * 默认 base-dir 为相对路径 {@code uploads}（后端工作目录即 {@code backend/}，
 * 因此实际为 {@code backend/uploads/outbound/...}，已被根 {@code .gitignore} 的
 * {@code backend/uploads/} 覆盖）。</p>
 *
 * <p><b>为什么文件必须落盘</b>：OneBot 的 {@code upload_group_file} 与图片段都支持传本地路径，
 * 但要求 NapCat 进程能读到该路径（当前部署下后端与 NapCat 同机）；同时落盘也便于事后排查。</p>
 */
@Service
public class OutboundMediaService {

    private static final Logger log = LoggerFactory.getLogger(OutboundMediaService.class);

    /** 单文件大小上限：20MB（契约值） */
    public static final long MAX_MEDIA_SIZE = 20L * 1024 * 1024;

    /** 图片走 base64 图片段的大小上限；超过则改用本地绝对路径图片段 */
    public static final long IMAGE_BASE64_MAX_SIZE = 4L * 1024 * 1024;

    /** 图片扩展名白名单（小写，含点） */
    private static final Set<String> IMAGE_EXTS = Set.of(".jpg", ".jpeg", ".png", ".gif", ".webp", ".bmp");

    /** 扩展名 → Content-Type 映射（只用于图片判定，不依赖 Files.probeContentType 的平台差异） */
    private static final Map<String, String> IMAGE_MIME = Map.of(
            ".jpg", "image/jpeg",
            ".jpeg", "image/jpeg",
            ".png", "image/png",
            ".gif", "image/gif",
            ".webp", "image/webp",
            ".bmp", "image/bmp"
    );

    /** 扩展名清洗：只保留字母数字，最多 10 位，避免把用户文件名里的怪字符带进落盘路径 */
    private static final Pattern SAFE_EXT = Pattern.compile("^[a-z0-9]{1,10}$");

    /** groupId 清洗：路径片段只允许数字/字母/下划线/短横线，防止 {@code ../} 穿越 */
    private static final Pattern SAFE_PATH_SEGMENT = Pattern.compile("[^A-Za-z0-9_-]");

    /** 落盘根目录（相对后端工作目录，即 backend/）；与 {@code FilePurgeService.base-dir} 同一口径 */
    @Value("${file.upload.base-dir:uploads}")
    private String uploadBaseDir;

    @Autowired
    private NapCatService napCatService;

    @Autowired
    private MessageService messageService;

    private final ObjectMapper objectMapper = new ObjectMapper();

    // ==================== 对外入口 ====================

    /**
     * 把网页上传的文件发到指定 QQ 群。
     *
     * <p>分支规则：</p>
     * <ul>
     *   <li><b>图片</b>（扩展名与 Content-Type 都属于图片，见 {@link #isImageFile}）：
     *       文件 ≤ {@value #IMAGE_BASE64_MAX_SIZE} 字节用 {@code base64://} 图片段，超过则用本地绝对路径图片段；
     *       段顺序固定为 {@code reply? → at×N → image → text?}；</li>
     *   <li><b>其他文件</b>：走 OneBot {@code upload_group_file}（body 含 {@code group_id/file/name}）。
     *       {@code text} 与 {@code atQqs} 作为<b>一条独立文本消息先发</b>（非图片文件无法把文字和文件放进同一条消息），
     *       随后再上传群文件；这样文件上传失败时文字仍已送达，且失败原因不会被文字发送掩盖。</li>
     * </ul>
     *
     * <p>不做鉴权/限流/审计（由 Controller 负责）。参数非法或 NapCat 失败抛
     * {@link BizException}（400 参数类 / 503 NapCat 类）。</p>
     *
     * @param groupId   目标群号（URL 路径里的群号）
     * @param file      上传的文件（必填且非空）
     * @param text      附带文字，可为空
     * @param replyToId 被回复消息的<b>数据库自增 id</b>，可为空
     * @param atQqs     @ 的 QQ 号列表，可为空
     * @return 返回体字段（{@code sent/groupId/kind/fileName/size/napcatMessageId/segmentTypes} 等）
     */
    public Map<String, Object> sendMedia(String groupId, MultipartFile file,
                                        String text, Long replyToId, List<String> atQqs) {
        if (file == null || file.isEmpty()) {
            throw new BizException(400, "请选择要发送的文件");
        }
        if (file.getSize() > MAX_MEDIA_SIZE) {
            throw new BizException(400, "文件过大，最大 20MB");
        }

        String originalName = file.getOriginalFilename();
        if (originalName == null || originalName.isBlank()) {
            originalName = "unnamed";
        }
        // 只取文件名部分（浏览器个别情况下会带路径）
        originalName = originalName.replace('\\', '/');
        int slash = originalName.lastIndexOf('/');
        if (slash >= 0) {
            originalName = originalName.substring(slash + 1);
        }
        if (originalName.isBlank()) {
            originalName = "unnamed";
        }

        String ext = resolveExtension(originalName);
        String storedName = UUID.randomUUID().toString().replace("-", "") + ext;
        File stored = storeToDisk(file, groupId, storedName);

        String replyMessageId = resolveReplyMessageId(groupId, replyToId);
        List<String> mentionIds = normalizeAtQqs(atQqs);

        boolean image = isImageFile(ext, file.getContentType());
        return image
                ? sendImage(groupId, stored, originalName, text, replyMessageId, mentionIds)
                : sendFile(groupId, stored, originalName, text, replyMessageId, mentionIds);
    }

    /**
     * 解析 {@code replyToId}（用户传的是<b>数据库自增 id</b>，前端传的就是消息列表里的 id）。
     *
     * <p>返回的是可以放进 reply 段的 <b>OneBot message_id</b>；三种「忽略 reply 段」的情形都返回 null：</p>
     * <ul>
     *   <li>{@code replyToId} 为 null；</li>
     *   <li>该消息的 {@code message_id}（OneBot id）为空 —— 契约要求忽略而不是报错；</li>
     *   <li>该消息已被软删除（{@code deleted=true}）—— 不回错但也不引用，避免引用一条用户已删的消息。</li>
     * </ul>
     *
     * @throws BizException 400 消息不存在 / 不属于该群
     */
    public String resolveReplyMessageId(String groupId, Long replyToId) {
        if (replyToId == null) {
            return null;
        }
        Message message = messageService.getMessageById(replyToId)
                .orElseThrow(() -> new BizException(400, "被回复的消息不存在"));
        if (!groupId.equals(message.getGroupId())) {
            throw new BizException(400, "被回复的消息不属于该群");
        }
        if (message.isDeleted()) {
            log.info("被回复的消息已删除，忽略 reply 段: replyToId={}, groupId={}", replyToId, groupId);
            return null;
        }
        String messageId = message.getMessageId();
        if (messageId == null || messageId.isBlank()) {
            log.info("被回复的消息没有 OneBot message_id，忽略 reply 段: replyToId={}", replyToId);
            return null;
        }
        return messageId;
    }

    /**
     * 解析并清洗 @ 目标 QQ 号：去掉空白项，最多取前 {@value #MAX_AT_COUNT} 个（超出只 warn 不报错）。
     * 保持调用方传入的顺序（LinkedHashSet 去重但保序）。
     */
    public List<String> normalizeAtQqs(List<String> rawQqs) {
        List<String> result = new ArrayList<>();
        if (rawQqs == null || rawQqs.isEmpty()) {
            return result;
        }
        java.util.Set<String> seen = new java.util.LinkedHashSet<>();
        for (String qq : rawQqs) {
            if (qq == null) {
                continue;
            }
            String trimmed = qq.trim();
            if (!trimmed.isEmpty()) {
                seen.add(trimmed);
            }
        }
        for (String qq : seen) {
            if (result.size() >= MAX_AT_COUNT) {
                log.warn("@ 目标超过 {} 个，只发送前 {} 个（收到 {} 个）", MAX_AT_COUNT, MAX_AT_COUNT, seen.size());
                break;
            }
            result.add(qq);
        }
        return result;
    }

    /** @ 目标数量上限（契约值：超出只取前 10 个并 warn） */
    public static final int MAX_AT_COUNT = 10;

    /**
     * 把解析好的消息段拼成 {@code reply? → at×N → text?} 的数组（不含图片段）。
     * 文本为空时不加 text 段；段全空时返回空数组。
     *
     * <p>这就是 {@code POST /api/groups/{groupId}/send} 用的段序列，
     * 与 {@code send-media} 的图片段共用（图片段插在 at 之后、text 之前）。</p>
     */
    public ArrayNode buildTextSegments(String replyMessageId, List<String> mentionIds, String text) {
        ArrayNode segments = objectMapper.createArrayNode();
        if (replyMessageId != null && !replyMessageId.isBlank()) {
            ObjectNode reply = objectMapper.createObjectNode();
            reply.put("type", "reply");
            reply.putObject("data").put("id", replyMessageId);
            segments.add(reply);
        }
        if (mentionIds != null) {
            for (String qq : mentionIds) {
                ObjectNode at = objectMapper.createObjectNode();
                at.put("type", "at");
                at.putObject("data").put("qq", qq);
                segments.add(at);
            }
        }
        if (text != null && !text.isEmpty()) {
            ObjectNode textSegment = objectMapper.createObjectNode();
            textSegment.put("type", "text");
            textSegment.putObject("data").put("text", text);
            segments.add(textSegment);
        }
        return segments;
    }

    /** 把段数组里的 {@code type} 按顺序抽成 {@code ["reply","at","text"]}，用于返回体 / 审计 */
    public List<String> segmentTypes(ArrayNode segments) {
        List<String> types = new ArrayList<>();
        if (segments == null) {
            return types;
        }
        for (int i = 0; i < segments.size(); i++) {
            types.add(segments.get(i).path("type").asText(""));
        }
        return types;
    }

    // ==================== 图片分支 ====================

    /**
     * 图片：按大小决定 base64 段还是本地路径段，与 text 同一条消息发出。
     * 段顺序 {@code reply? → at×N → image → text?}。
     */
    private Map<String, Object> sendImage(String groupId, File file, String fileName, String text,
                                          String replyMessageId, List<String> mentionIds) {
        long size = file.length();
        String imageFile;
        boolean useBase64 = size <= IMAGE_BASE64_MAX_SIZE;
        if (useBase64) {
            imageFile = encodeBase64(file);
        } else {
            // NapCat 与后端同机，直接给绝对路径
            imageFile = file.getAbsolutePath();
        }

        ArrayNode segments = objectMapper.createArrayNode();
        if (replyMessageId != null && !replyMessageId.isBlank()) {
            ObjectNode reply = objectMapper.createObjectNode();
            reply.put("type", "reply");
            reply.putObject("data").put("id", replyMessageId);
            segments.add(reply);
        }
        for (String qq : mentionIds) {
            ObjectNode at = objectMapper.createObjectNode();
            at.put("type", "at");
            at.putObject("data").put("qq", qq);
            segments.add(at);
        }
        ObjectNode image = objectMapper.createObjectNode();
        image.put("type", "image");
        image.putObject("data").put("file", imageFile);
        segments.add(image);
        if (text != null && !text.isEmpty()) {
            ObjectNode textSegment = objectMapper.createObjectNode();
            textSegment.put("type", "text");
            textSegment.putObject("data").put("text", text);
            segments.add(textSegment);
        }

        log.info("发送图片到群{}: name={}, size={}B, 图片段方式={}", groupId, fileName, size,
                useBase64 ? "base64" : "本地路径");

        NapCatService.SendGroupMessageResult result = napCatService.sendGroupSegments(groupId, segments);
        if (result == null || !result.isSuccess()) {
            throw new BizException(503, "NapCat 未登录或不可用，发送失败");
        }

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("sent", true);
        data.put("groupId", groupId);
        data.put("kind", "image");
        data.put("fileName", fileName);
        data.put("size", size);
        data.put("napcatMessageId", result.getMessageId());
        data.put("segmentTypes", segmentTypes(segments));
        return data;
    }

    // ==================== 其他文件分支 ====================

    /**
     * 其他文件：先（可选）发一条 text/at 消息，再调 {@code upload_group_file}。
     * {@code "file"} 是 segmentTypes 里的固定占位段名（群文件在 OneBot 里不是 message 段，无法回读段类型）。
     */
    private Map<String, Object> sendFile(String groupId, File file, String fileName, String text,
                                        String replyMessageId, List<String> mentionIds) {
        List<String> types = new ArrayList<>();
        String textMessageId = null;

        if ((text != null && !text.isEmpty()) || !mentionIds.isEmpty()) {
            ArrayNode textSegments = buildTextSegments(replyMessageId, mentionIds, text);
            NapCatService.SendGroupMessageResult textResult = napCatService.sendGroupSegments(groupId, textSegments);
            if (textResult == null || !textResult.isSuccess()) {
                // 文字都没发出去，再传文件也没意义，且失败语义与图片分支保持一致 → 503
                throw new BizException(503, "NapCat 未登录或不可用，发送失败");
            }
            textMessageId = textResult.getMessageId();
            types.addAll(segmentTypes(textSegments));
        }

        NapCatService.UploadGroupFileResult upload =
                napCatService.uploadGroupFile(groupId, file.getAbsolutePath(), fileName);
        if (upload == null || !upload.isSuccess()) {
            throw new BizException(503, "NapCat 未登录或不可用，发送失败");
        }
        types.add("file");

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("sent", true);
        data.put("groupId", groupId);
        data.put("kind", "file");
        data.put("fileName", fileName);
        data.put("size", file.length());
        data.put("napcatMessageId", upload.getMessageId());
        // 附带文字时它是独立的一条消息，其 message_id 单独给出（契约只要求 napcatMessageId，这里是新增字段）
        if (textMessageId != null) {
            data.put("textMessageId", textMessageId);
        }
        data.put("segmentTypes", types);
        return data;
    }

    // ==================== 落盘 / 判定工具 ====================

    /** 从原始文件名取小写扩展名（含点）；没有或不合规则返回空串 */
    private String resolveExtension(String fileName) {
        String name = fileName.toLowerCase(Locale.ROOT);
        int dot = name.lastIndexOf('.');
        if (dot < 0 || dot == name.length() - 1) {
            return "";
        }
        String ext = name.substring(dot);
        if (!SAFE_EXT.matcher(ext.substring(1)).matches()) {
            return "";
        }
        return ext;
    }

    /**
     * 是否按图片处理：扩展名在白名单内，<b>且</b> Content-Type 是 {@code image/*}。
     * 两者都满足才走图片分支，避免把改了扩展名的非图片文件当图片发（契约：按扩展名 + Content-Type 判断）。
     */
    private boolean isImageFile(String ext, String contentType) {
        if (ext.isEmpty() || !IMAGE_EXTS.contains(ext)) {
            return false;
        }
        if (contentType == null || contentType.isBlank()) {
            return false;
        }
        return contentType.trim().toLowerCase(Locale.ROOT).startsWith("image/");
    }

    /** 返回该扩展名对应的图片 Content-Type；非图片返回 null（供 Controller 侧需要时使用） */
    public String imageContentType(String ext) {
        return ext == null ? null : IMAGE_MIME.get(ext.toLowerCase(Locale.ROOT));
    }

    /** 把上传文件写到 {@code <base>/outbound/<groupId>/<storedName>}，返回落盘后的文件（绝对路径） */
    private File storeToDisk(MultipartFile file, String groupId, String storedName) {
        try {
            Path dir = Paths.get(uploadBaseDir, "outbound", safePathSegment(groupId))
                    .toAbsolutePath().normalize();
            Files.createDirectories(dir);
            Path target = dir.resolve(storedName).normalize();
            if (!target.startsWith(dir)) {
                throw new BizException(400, "文件路径不合法");
            }
            try (java.io.InputStream in = file.getInputStream()) {
                Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
            }
            return target.toFile();
        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            log.error("保存上传文件失败 groupId={}, name={}: {}", groupId, storedName, e.getMessage(), e);
            throw new BizException(500, "文件保存失败：" + e.getMessage());
        }
    }

    /** 路径片段清洗：非 {@code [A-Za-z0-9_-]} 一律替换为下划线，杜绝 {@code ../} 穿越 */
    private String safePathSegment(String raw) {
        String cleaned = SAFE_PATH_SEGMENT.matcher(raw == null ? "" : raw).replaceAll("_");
        return cleaned.isEmpty() ? "unknown" : cleaned;
    }

    /** 读文件转 {@code base64://<...>}（OneBot 约定的 base64 图片段写法） */
    private String encodeBase64(File file) {
        try {
            byte[] bytes = Files.readAllBytes(file.toPath());
            return "base64://" + Base64.getEncoder().encodeToString(bytes);
        } catch (Exception e) {
            log.error("读取图片转 base64 失败: {}", file.getAbsolutePath(), e);
            throw new BizException(500, "文件读取失败：" + e.getMessage());
        }
    }
}

package com.qqai.controller;

import com.qqai.common.SecurityHelper;
import com.qqai.dto.common.ApiResponse;
import com.qqai.entity.FileRecord;
import com.qqai.entity.Message;
import com.qqai.service.AuditLogService;
import com.qqai.service.FileStorageService;
import com.qqai.service.MessageService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.FileSystemResource;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.core.io.Resource;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.*;

@RestController
@RequestMapping("/api/messages")
public class MessageController {

    /** 未预期异常统一在此记录堆栈（对外只返回安全文案 + traceId，不回显内部异常文本） */
    private static final Logger log = LoggerFactory.getLogger(MessageController.class);

    @Autowired
    private MessageService messageService;

    /** 搜索结果 contentSnippet 的截断长度（契约：内容前 120 字，超出加 …） */
    private static final int SEARCH_SNIPPET_MAX_CHARS = 120;

    /** 搜索结果 sendTime 的输出格式（契约示例：2026-09-17T12:42:54） */
    private static final java.time.format.DateTimeFormatter SEARCH_TIME_FORMATTER =
            java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");

    @Autowired
    private com.qqai.service.AstrBotPersonaService astrBotPersonaService;

    @Autowired
    private com.qqai.service.FilePurgeService filePurgeService;

    @Autowired
    private FileStorageService fileStorageService;

    @Autowired
    private SecurityHelper securityHelper;

    @Autowired
    private AuditLogService auditLogService;

    @Autowired
    private com.qqai.service.AstrBotService astrBotService;

    @Autowired
    private com.qqai.service.AiSummaryParser aiSummaryParser;

    @Autowired
    private com.qqai.common.RateLimiterService rateLimiterService;

    @Autowired
    private com.qqai.service.MessageBroadcastService messageBroadcastService;

    /** AI 摘要运行时配置（总开关 / 额度 / 白名单），由后台「摘要设置」维护 */
    @Autowired
    private com.qqai.service.AiSummarySettingsService aiSummarySettingsService;


    /**
     * 单条按需摘要（阶段 1）：POST /api/messages/{id}/summarize?force=false
     *
     * <ul>
     *   <li>权限：登录用户 + 该消息所属群对当前用户可见；管理员不受限</li>
     *   <li>总开关：{@code ai.summary.enabled=false} → 403（唯一的统一开关校验，其余行为保持不变）</li>
     *   <li>限流：每用户 10 次/分钟（超出 429）</li>
     *   <li>幂等：已有摘要且 force=false 时直接返回 {@code cached:true}，不再调用大模型、不计额度</li>
     *   <li>同步返回：单条通常 2–16 秒</li>
     * </ul>
     */
    @PostMapping("/{id}/summarize")
    public ResponseEntity<ApiResponse<Map<String, Object>>> summarizeMessage(
            @PathVariable Long id,
            @RequestParam(defaultValue = "false") boolean force) {

        Long userId = securityHelper.getCurrentUserId();
        if (userId == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(ApiResponse.error(401, "未登录"));
        }

        // AI 摘要总开关：关闭后所有摘要接口统一 403（本接口只增加这一条校验）
        if (!aiSummarySettingsService.isEnabled()) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(ApiResponse.error(403, "AI 摘要功能已关闭"));
        }

        Optional<Message> opt = messageService.getMessageById(id);
        if (opt.isEmpty()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(ApiResponse.error(404, "消息不存在"));
        }
        Message message = opt.get();

        // 群可见性校验（管理员不受限）
        if (!securityHelper.isAdmin()) {
            List<String> userQqList = securityHelper.getCurrentUserQqBindings();
            if (userQqList.isEmpty()) {
                return ResponseEntity.status(HttpStatus.FORBIDDEN).body(ApiResponse.error(403, "请先绑定QQ账号"));
            }
            if (!securityHelper.hasGroupAccess(message.getGroupId(), userQqList)) {
                return ResponseEntity.status(HttpStatus.FORBIDDEN).body(ApiResponse.error(403, "无权访问该群聊"));
            }
        }

        // 幂等：已有摘要直接返回缓存
        if (!force && aiSummaryParser.hasSummary(message)) {
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("status", "cached");
            data.put("cached", true);
            data.put("summary", aiSummaryParser.toView(message));
            return ResponseEntity.ok(ApiResponse.success(data));
        }

        // 限流：每用户 10 次/分钟（缓存命中不计入，放在幂等判断之后）
        if (!rateLimiterService.isAllowed("summarize:" + userId, 10, 1)) {
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                    .body(ApiResponse.error(429, "操作过于频繁，请稍后再试（每分钟最多 10 条）"));
        }

        String rendered = astrBotService.renderMessageForSummary(message);
        if (rendered == null || rendered.trim().isEmpty()) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(ApiResponse.error(400, "该消息没有可摘要的文本内容"));
        }

        String summary;
        try {
            // 带图消息：把图片作为消息段一起送出并切到视觉配置文件，否则模型看不到画面，
            // 只能答"内容未知/未提供可辨内容"（图片在文本里只是一条 URL）。
            List<String> imageUrls = astrBotService.renderMessageImageUrls(message);
            // 「人层」：用户选定的 AstrBot 人格（没选则 null，用内置默认/vision-task 档案）
            String personaConfig = astrBotPersonaService.resolveConfigName(userId, !imageUrls.isEmpty());
            summary = astrBotService.summarizeMessageStructured(
                    rendered, null, resolveGroupType(message.getGroupId()), imageUrls, personaConfig);
        } catch (com.qqai.exception.BizException biz) {
                // 业务异常保持原有状态码与文案，交由 GlobalExceptionHandler 输出
                throw biz;
            } catch (Exception e) {
            log.error("AI 服务调用失败", e);
            throw new com.qqai.exception.BizException(503, "AI_SERVICE_UNAVAILABLE", "AI 服务暂不可用，请稍后重试");
        }
        if (summary == null || summary.isBlank()) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(ApiResponse.error(503, "AI 服务返回为空，请稍后重试"));
        }

        message.setAiSummary(summary);
        aiSummaryParser.applyStructured(message, summary);
        message.setProcessed(true);
        Message updated = messageService.saveMessage(message);
        messageBroadcastService.broadcastMessageUpdate(updated);

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("status", "ok");
        data.put("cached", false);
        data.put("summary", aiSummaryParser.toView(updated));
        return ResponseEntity.ok(ApiResponse.success(data));
    }

    /** 从消息所属群解析 groupType（与 AI 分析消费者口径一致） */
    private String resolveGroupType(String groupId) {
        if (groupId == null || groupId.isBlank()) return null;
        try {
            return messageService.getGroupTypeByGroupId(groupId);
        } catch (Exception e) {
            return null;
        }
    }



    @PostMapping
    public ResponseEntity<ApiResponse<Message>> createMessage(@RequestBody Message message) {
        Long userId = securityHelper.getCurrentUserId();
        if (userId == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(ApiResponse.error(401, "未登录"));
        }
        List<String> userQqList = securityHelper.getCurrentUserQqBindings();
        // selfQq 为空或不属于当前用户绑定列表 → 403
        if (message.getSelfQq() == null || message.getSelfQq().isBlank()) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(ApiResponse.error(403, "selfQq 不能为空"));
        }
        if (!userQqList.contains(message.getSelfQq())) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(ApiResponse.error(403, "selfQq 不属于当前用户绑定列表"));
        }
        // 校验群聊访问权限
        if (message.getGroupId() != null && !message.getGroupId().isBlank()) {
            boolean hasAccess = securityHelper.hasGroupAccess(message.getGroupId(), userQqList);
            if (!hasAccess) {
                return ResponseEntity.status(HttpStatus.FORBIDDEN).body(ApiResponse.error(403, "无权访问该群聊"));
            }
        }
        Message saved = messageService.saveMessage(message);
        return ResponseEntity.ok(ApiResponse.success(saved));
    }

    @GetMapping("/group/{groupId}")
    public ResponseEntity<ApiResponse<List<Message>>> getMessagesByGroupId(
            @PathVariable String groupId,
            @RequestParam(required = false) String selfQq) {
        Long userId = securityHelper.getCurrentUserId();
        if (userId == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(ApiResponse.error(401, "未登录"));
        }

        // 获取用户绑定的所有QQ账号
        List<String> userQqList = securityHelper.getCurrentUserQqBindings();
        if (userQqList.isEmpty()) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(ApiResponse.error(403, "请先绑定QQ账号"));
        }

        boolean hasAccess = securityHelper.hasGroupAccess(groupId, userQqList);
        if (!hasAccess) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(ApiResponse.error(403, "无权访问该群聊"));
        }

        List<Message> messages;
        if (selfQq != null && !selfQq.isBlank() && userQqList.contains(selfQq)) {
            messages = messageService.getMessagesByGroupIdAndUser(groupId, selfQq);
        } else {
            messages = messageService.getMessagesByGroupIdAndUserQqList(groupId, userQqList);
        }
        return ResponseEntity.ok(ApiResponse.success(messages));
    }

    @GetMapping("/group/{groupId}/paged")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getMessagesByGroupIdPaged(
            @PathVariable String groupId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size,
            @RequestParam(required = false) String selfQq) {
        Long userId = securityHelper.getCurrentUserId();
        if (userId == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(ApiResponse.error(401, "未登录"));
        }

        // 获取用户绑定的所有QQ账号
        List<String> userQqList = securityHelper.getCurrentUserQqBindings();
        if (userQqList.isEmpty()) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(ApiResponse.error(403, "请先绑定QQ账号"));
        }

        boolean hasAccess = securityHelper.hasGroupAccess(groupId, userQqList);
        if (!hasAccess) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(ApiResponse.error(403, "无权访问该群聊"));
        }

        Pageable pageable = PageRequest.of(page, Math.min(size, 200));
        List<Message> messages;
        Long total;
        if (selfQq != null && !selfQq.isBlank() && userQqList.contains(selfQq)) {
            messages = messageService.getMessagesByGroupIdPagedAndUser(groupId, selfQq, pageable);
            total = messageService.countMessagesByGroupIdAndUser(groupId, selfQq);
        } else {
            messages = messageService.getMessagesByGroupIdPagedAndUserQqList(groupId, userQqList, pageable);
            total = messageService.countMessagesByGroupIdAndUserQqList(groupId, userQqList);
        }

        Map<String, Object> response = new HashMap<>();
        response.put("messages", messages);
        response.put("total", total);
        response.put("page", page);
        response.put("size", size);

        return ResponseEntity.ok(ApiResponse.success(response));
    }

    @GetMapping("/group/{groupId}/since")
    public ResponseEntity<ApiResponse<List<Message>>> getMessagesSince(
            @PathVariable String groupId,
            @RequestParam Long afterId,
            @RequestParam(required = false) String selfQq) {
        Long userId = securityHelper.getCurrentUserId();
        if (userId == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(ApiResponse.error(401, "未登录"));
        }

        List<String> userQqList = securityHelper.getCurrentUserQqBindings();
        if (userQqList.isEmpty()) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(ApiResponse.error(403, "请先绑定QQ账号"));
        }

        boolean hasAccess = securityHelper.hasGroupAccess(groupId, userQqList);
        if (!hasAccess) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(ApiResponse.error(403, "无权访问该群聊"));
        }

        List<Message> messages;
        if (selfQq != null && !selfQq.isBlank() && userQqList.contains(selfQq)) {
            messages = messageService.getMessagesSinceId(groupId, selfQq, afterId);
        } else {
            messages = messageService.getMessagesSinceId(groupId, userQqList, afterId);
        }
        return ResponseEntity.ok(ApiResponse.success(messages));
    }

    /**
     * 获取群聊成员 QQ 号与昵称映射（用于 @消息解析）
     */
    @GetMapping("/group/{groupId}/members")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> getGroupMembers(@PathVariable String groupId) {
        Long userId = securityHelper.getCurrentUserId();
        if (userId == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(ApiResponse.error(401, "未登录"));
        }

        // 管理员不受群可见性限制：否则未绑定 QQ 的管理员在群聊页点「@」会拿到 403，
        // 而同一页面的发送接口是允许管理员发的，体验上自相矛盾。
        if (!securityHelper.isAdmin()) {
            List<String> userQqList = securityHelper.getCurrentUserQqBindings();
            if (userQqList.isEmpty()) {
                return ResponseEntity.status(HttpStatus.FORBIDDEN)
                        .body(ApiResponse.error(403, "请先绑定QQ账号"));
            }

            boolean hasAccess = securityHelper.hasGroupAccess(groupId, userQqList);
            if (!hasAccess) {
                return ResponseEntity.status(HttpStatus.FORBIDDEN)
                        .body(ApiResponse.error(403, "无权访问该群聊"));
            }
        }

        List<Map<String, Object>> members = messageService.getGroupMemberNicknames(groupId);
        return ResponseEntity.ok(ApiResponse.success(members));
    }

    private final java.util.concurrent.locks.ReentrantLock processLock = new java.util.concurrent.locks.ReentrantLock();

    @Autowired
    private com.qqai.service.AiSummaryBatchService aiSummaryBatchService;

    /**
     * 批量补摘要（AI 摘要阶段 2）。
     * 请求体（可选字段）：{@code {groupId, start, end, limit, minLength, onlyText}}
     * — 必须带 groupId 或 start，否则 400；limit 上限 500（管理员 2000）；有任务进行中 409。
     */
    @PostMapping("/process")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ApiResponse<Map<String, Object>>> processAllMessages(
            @RequestBody(required = false) Map<String, Object> body) {
        if (!processLock.tryLock()) {
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(ApiResponse.error(409, "任务正在执行中，请勿重复调用"));
        }
        try {
            String groupId = str(body, "groupId");
            java.time.LocalDateTime start = time(body, "start");
            java.time.LocalDateTime end = time(body, "end");
            Integer limit = num(body, "limit");
            Integer minLength = num(body, "minLength");
            Boolean onlyText = bool(body, "onlyText");
            Map<String, Object> data = aiSummaryBatchService.start(groupId, start, end, limit, minLength, onlyText);
            return ResponseEntity.ok(ApiResponse.success(data));
        } catch (com.qqai.exception.BizException e) {
            return ResponseEntity.status(e.getCode() >= 400 && e.getCode() < 600 ? e.getCode() : 400)
                    .body(ApiResponse.error(e.getCode(), e.getMessage()));
        } finally {
            processLock.unlock();
        }
    }

    /** 批量任务进度：队列深度 / 今日已完成 / 剩余待处理 / 是否进行中 */
    @GetMapping("/process/status")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ApiResponse<Map<String, Object>>> processStatus() {
        return ResponseEntity.ok(ApiResponse.success(aiSummaryBatchService.status()));
    }

    /** 停止批量任务：清空 ai.analysis.queue（保留 DLQ），写审计 */
    @PostMapping("/process/stop")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ApiResponse<Map<String, Object>>> processStop() {
        try {
            return ResponseEntity.ok(ApiResponse.success(aiSummaryBatchService.stop()));
        } catch (com.qqai.exception.BizException e) {
            return ResponseEntity.status(500).body(ApiResponse.error(e.getCode(), e.getMessage()));
        }
    }

    /** 从请求体取字符串（空串归一为 null） */
    private String str(Map<String, Object> body, String key) {
        if (body == null) return null;
        Object v = body.get(key);
        if (v == null) return null;
        String s = String.valueOf(v).trim();
        return s.isEmpty() ? null : s;
    }

    /** 取时间参数，支持 "2026-09-16" 与 "2026-09-16 00:00:00" 两种写法；日期单独给时按 00:00:00 计 */
    private java.time.LocalDateTime time(Map<String, Object> body, String key) {
        String s = str(body, key);
        if (s == null) return null;
        try {
            if (s.length() <= 10) {
                java.time.LocalDate d = java.time.LocalDate.parse(s);
                return "end".equals(key) ? d.atTime(java.time.LocalTime.MAX) : d.atStartOfDay();
            }
            return java.time.LocalDateTime.parse(s.replace(' ', 'T'));
        } catch (Exception e) {
            throw new com.qqai.exception.BizException(400, "时间格式不合法：" + key + "=" + s + "（应为 2026-09-16 或 2026-09-16 12:00:00）");
        }
    }

    private Integer num(Map<String, Object> body, String key) {
        String s = str(body, key);
        if (s == null) return null;
        try {
            return (int) Double.parseDouble(s);
        } catch (Exception e) {
            return null;
        }
    }

    private Boolean bool(Map<String, Object> body, String key) {
        String s = str(body, key);
        return s == null ? null : Boolean.parseBoolean(s);
    }

    /**
     * 上传文件
     */
    private static final Set<String> ALLOWED_FILE_TYPES = Set.of("IMAGE", "VIDEO", "AUDIO", "FILE");
    private static final long MAX_UPLOAD_SIZE = 50 * 1024 * 1024; // 50MB

    @PostMapping("/upload")
    public ResponseEntity<ApiResponse<FileRecord>> uploadFile(
            @RequestParam("file") MultipartFile file,
            @RequestParam("fileType") String fileType) {
        try {
            // fileType 白名单校验
            if (fileType == null || !ALLOWED_FILE_TYPES.contains(fileType.toUpperCase())) {
                return ResponseEntity.badRequest().body(ApiResponse.error(400, "无效的文件类型，仅支持 IMAGE/VIDEO/AUDIO/FILE"));
            }
            // 文件大小校验
            if (file.getSize() > MAX_UPLOAD_SIZE) {
                return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE).body(ApiResponse.error(413, "文件大小超过限制（最大50MB）"));
            }
            FileRecord.FileType type = FileRecord.FileType.valueOf(fileType.toUpperCase());
            FileRecord fileRecord = fileStorageService.uploadFile(file, type, securityHelper.getCurrentUserId());
            return ResponseEntity.ok(ApiResponse.success(fileRecord));
        } catch (com.qqai.exception.BizException biz) {
                // 业务异常保持原有状态码与文案，交由 GlobalExceptionHandler 输出
                throw biz;
            } catch (Exception e) {
            log.error("上传失败", e);
            throw new com.qqai.exception.BizException(500, "FILE_UPLOAD_FAILED", "文件上传失败，请稍后重试");
        }
    }

    /**
     * 发送带文件的消息
     */
    @PostMapping("/send-with-file")
    public ResponseEntity<ApiResponse<Message>> sendMessageWithFile(
            @RequestParam("groupId") String groupId,
            @RequestParam("userQq") String userQq,
            @RequestParam("userNickname") String userNickname,
            @RequestParam("content") String content,
            @RequestParam("messageType") String messageType,
            @RequestParam(value = "file", required = false) MultipartFile file,
            @RequestParam(value = "selfQq", required = false) String selfQq) {
        try {
            Long userId = securityHelper.getCurrentUserId();
            if (userId == null) {
                return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(ApiResponse.error(401, "未登录"));
            }
            List<String> userQqList = securityHelper.getCurrentUserQqBindings();
            // selfQq 校验：若请求体未传或为空，取第一个绑定QQ
            if (selfQq == null || selfQq.isBlank()) {
                if (userQqList.isEmpty()) {
                    return ResponseEntity.status(HttpStatus.FORBIDDEN).body(ApiResponse.error(403, "请先绑定QQ账号"));
                }
                selfQq = userQqList.get(0);
            }
            if (!userQqList.contains(selfQq)) {
                return ResponseEntity.status(HttpStatus.FORBIDDEN).body(ApiResponse.error(403, "selfQq 不属于当前用户绑定列表"));
            }
            // groupId 权限校验
            boolean hasAccess = securityHelper.hasGroupAccess(groupId, userQqList);
            if (!hasAccess) {
                return ResponseEntity.status(HttpStatus.FORBIDDEN).body(ApiResponse.error(403, "无权访问该群聊"));
            }

            Message message = new Message();
            message.setGroupId(groupId);
            message.setUserQq(userQq);
            message.setUserNickname(userNickname);
            message.setContent(content);
            message.setMessageType(Message.MessageType.valueOf(messageType.toUpperCase()));
            message.setSelfQq(selfQq);

            // 如果有文件，先上传文件
            if (file != null && !file.isEmpty()) {
                FileRecord.FileType fileType = getFileTypeFromMessageType(messageType);
                FileRecord fileRecord = fileStorageService.uploadFile(file, fileType, securityHelper.getCurrentUserId());
                message.setFileId(fileRecord.getFileId());
            }

            Message savedMessage = messageService.saveMessage(message);
            return ResponseEntity.ok(ApiResponse.success(savedMessage));
        } catch (com.qqai.exception.BizException biz) {
                // 业务异常保持原有状态码与文案，交由 GlobalExceptionHandler 输出
                throw biz;
            } catch (Exception e) {
            log.error("发送失败", e);
            throw new com.qqai.exception.BizException(500, "MESSAGE_SEND_FAILED", "消息发送失败，请稍后重试");
        }
    }

    /**
     * 获取文件信息
     */
    @GetMapping("/file/{fileId}")
    public ResponseEntity<ApiResponse<FileRecord>> getFileInfo(@PathVariable String fileId) {
        try {
            Long currentUserId = securityHelper.getCurrentUserId();
            if (currentUserId == null) {
                return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(ApiResponse.error(401, "未登录"));
            }

            FileRecord fr = fileStorageService.getFileRecord(fileId);

            if (fr.getUploaderId() != null) {
                if (!fr.getUploaderId().equals(currentUserId)) {
                    return ResponseEntity.status(HttpStatus.FORBIDDEN).body(ApiResponse.error(403, "无权操作该文件"));
                }
            } else {
                List<String> userQqList = securityHelper.getCurrentUserQqBindings();
                boolean allowed = false;
                List<com.qqai.entity.Message> relatedMsgs = messageService.findByFileId(fileId);
                for (com.qqai.entity.Message m : relatedMsgs) {
                    if (m.getSelfQq() != null && userQqList.contains(m.getSelfQq())) {
                        allowed = true; break;
                    }
                }
                if (!allowed) {
                    return ResponseEntity.status(HttpStatus.FORBIDDEN).body(ApiResponse.error(403, "无权操作该文件"));
                }
            }

            return ResponseEntity.ok(ApiResponse.success(fr));
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(ApiResponse.error(404, "文件不存在"));
        }
    }

    /**
     * 受控的本地文件下载端点。
     * 前端应将原本直接访问 /images/ 或 /uploads/ 的链接改为通过此接口获取。
     */
    @GetMapping("/download")
    public ResponseEntity<Resource> downloadFile(@RequestParam String path) {
        Long currentUserId = securityHelper.getCurrentUserId();
        if (currentUserId == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }

        if (path == null || path.isEmpty() || path.contains("..") || path.contains("~")) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).build();
        }

        File file;
        if (path.startsWith("/images/")) {
            String[] parts = path.split("/");
            if (parts.length < 4) {
                return ResponseEntity.status(HttpStatus.BAD_REQUEST).build();
            }
            String groupId = parts[3];
            List<String> userQqList = securityHelper.getCurrentUserQqBindings();
            if (userQqList.isEmpty()) {
                return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
            }
            boolean hasAccess = securityHelper.hasGroupAccess(groupId, userQqList);
            if (!hasAccess) {
                return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
            }
            file = new File("uploads" + path);
        } else if (path.startsWith("/uploads/")) {
            if (path.startsWith("/uploads/avatars/")) {
                return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
            }
            file = new File("." + path);
        } else {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).build();
        }

        if (!file.exists() || !file.isFile()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        }

        try {
            String canonicalPath = file.getCanonicalPath();
            String uploadsBase = new File("uploads").getCanonicalPath();
            if (!canonicalPath.startsWith(uploadsBase)) {
                return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
            }
        } catch (IOException e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }

        Resource resource = new FileSystemResource(file);
        String contentType = "application/octet-stream";
        try {
            String probed = Files.probeContentType(file.toPath());
            if (probed != null) {
                contentType = probed;
            }
        } catch (IOException ignored) {
        }

        String safeFilename = file.getName().replaceAll("[\\r\\n\"]", "_");
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(contentType))
                .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"" + safeFilename + "\"")
                .body(resource);
    }

    /**
     * 删除文件
     */
    @DeleteMapping("/file/{fileId}")
    public ResponseEntity<ApiResponse<Void>> deleteFile(@PathVariable String fileId) {
        try {
            Long currentUserId = securityHelper.getCurrentUserId();
            if (currentUserId == null) {
                return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(ApiResponse.error(401, "未登录"));
            }

            FileRecord fr = fileStorageService.getFileRecord(fileId);

            if (fr.getUploaderId() != null) {
                if (!fr.getUploaderId().equals(currentUserId)) {
                    return ResponseEntity.status(HttpStatus.FORBIDDEN).body(ApiResponse.error(403, "无权操作该文件"));
                }
            } else {
                List<String> userQqList = securityHelper.getCurrentUserQqBindings();
                boolean allowed = false;
                List<com.qqai.entity.Message> relatedMsgs = messageService.findByFileId(fileId);
                for (com.qqai.entity.Message m : relatedMsgs) {
                    if (m.getSelfQq() != null && userQqList.contains(m.getSelfQq())) {
                        allowed = true; break;
                    }
                }
                if (!allowed) {
                    return ResponseEntity.status(HttpStatus.FORBIDDEN).body(ApiResponse.error(403, "无权操作该文件"));
                }
            }

            fileStorageService.deleteFile(fileId);
            return ResponseEntity.ok(ApiResponse.success());
        } catch (com.qqai.exception.BizException biz) {
                // 业务异常保持原有状态码与文案，交由 GlobalExceptionHandler 输出
                throw biz;
            } catch (Exception e) {
            log.error("删除失败", e);
            throw new com.qqai.exception.BizException(500, "DELETE_FAILED", "删除失败，请稍后重试");
        }
    }

    /**
     * 删除单条消息
     */
    @DeleteMapping("/{messageId}")
    public ResponseEntity<ApiResponse<Void>> deleteMessage(@PathVariable Long messageId) {
        try {
            Long userId = securityHelper.getCurrentUserId();
            if (userId == null) {
                return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                        .body(ApiResponse.error(401, "未登录"));
            }

            List<String> userQqList = securityHelper.getCurrentUserQqBindings();
            if (userQqList.isEmpty()) {
                return ResponseEntity.status(HttpStatus.FORBIDDEN)
                        .body(ApiResponse.error(403, "请先绑定QQ账号"));
            }

            Message message = messageService.getMessageById(messageId)
                    .orElseThrow(() -> new com.qqai.exception.BizException(404, "MESSAGE_NOT_FOUND", "消息不存在"));

            if (!userQqList.contains(message.getSelfQq())) {
                return ResponseEntity.status(HttpStatus.FORBIDDEN)
                        .body(ApiResponse.error(403, "无权删除该消息"));
            }

            messageService.deleteMessage(messageId, userId, userQqList);
            return ResponseEntity.ok(ApiResponse.success());
        } catch (com.qqai.exception.BizException biz) {
            // 业务异常保持原有状态码与文案，交由 GlobalExceptionHandler 输出
            throw biz;
        } catch (RuntimeException e) {
            log.error("删除消息失败 messageId={}", messageId, e);
            throw new com.qqai.exception.BizException(500, "MESSAGE_DELETE_FAILED", "删除消息失败，请稍后重试");
        } catch (Exception e) {
            log.error("删除失败", e);
            throw new com.qqai.exception.BizException(500, "DELETE_FAILED", "删除失败，请稍后重试");
        }
    }

    /**
     * 批量删除消息（软删除），可选同时删除关联的媒体文件（图片/视频/语音）。
     *
     * 请求体示例:
     * {
     *   "messageIds": [1,2,3],
     *   "deleteMedia": true
     * }
     */
    @PostMapping("/delete-batch")
    public ResponseEntity<ApiResponse<Map<String, Object>>> deleteMessagesBatch(@RequestBody Map<String, Object> request) {
        try {
            Long userId = securityHelper.getCurrentUserId();
            if (userId == null) {
                return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                        .body(ApiResponse.error(401, "未登录"));
            }

            List<String> userQqList = securityHelper.getCurrentUserQqBindings();
            if (userQqList.isEmpty()) {
                return ResponseEntity.status(HttpStatus.FORBIDDEN)
                        .body(ApiResponse.error(403, "请先绑定QQ账号"));
            }

            Object rawIds = request.get("messageIds");
            if (rawIds == null) {
                return ResponseEntity.badRequest().body(ApiResponse.error(400, "请选择要删除的消息"));
            }
            // 兼容 list 或 number 形式
            List<Long> messageIds = new java.util.ArrayList<>();
            if (rawIds instanceof java.util.Collection<?> c) {
                for (Object o : c) {
                    if (o instanceof Number n) messageIds.add(n.longValue());
                }
            }
            if (messageIds.isEmpty()) {
                return ResponseEntity.badRequest().body(ApiResponse.error(400, "请选择要删除的消息"));
            }

            boolean deleteMedia = Boolean.TRUE.equals(request.get("deleteMedia"));

            int deletedCount = messageService.deleteMessagesByIds(messageIds, userQqList, userId, deleteMedia);
            Map<String, Object> data = new HashMap<>();
            data.put("success", true);
            data.put("deletedCount", deletedCount);
            data.put("deleteMedia", deleteMedia);
            data.put("message", "成功删除 " + deletedCount + " 条消息" + (deleteMedia ? "（同时删除了关联媒体文件）" : ""));
            auditLogService.log(securityHelper.getCurrentUsername(), "MESSAGE_DELETE_BATCH",
                    "messages:" + messageIds.size(), "SUCCESS",
                    "批量删除 " + deletedCount + " 条消息" + (deleteMedia ? "（含媒体）" : "") + ", userId=" + userId);
            return ResponseEntity.ok(ApiResponse.success(data));
        } catch (com.qqai.exception.BizException biz) {
            throw biz;
        } catch (Exception e) {
            auditLogService.log(securityHelper.getCurrentUsername(), "MESSAGE_DELETE_BATCH",
                    "messages", "FAILURE", e.getMessage());
            log.error("删除失败", e);
            throw new com.qqai.exception.BizException(500, "DELETE_FAILED", "删除失败，请稍后重试");
        }
    }

    /**
     * 按群聊和消息类型批量删除消息（软删除），可选同时删除关联媒体文件。
     *
     * 请求体示例:
     * {
     *   "types": ["IMAGE", "VIDEO"],
     *   "deleteMedia": true
     * }
     * 可选类型: TEXT / IMAGE / VIDEO / AUDIO / VOICE / FORWARD / FILE
     */
    @PostMapping("/group/{groupId}/delete-by-types")
    public ResponseEntity<ApiResponse<Map<String, Object>>> deleteMessagesByTypes(
            @PathVariable String groupId,
            @RequestBody Map<String, Object> request) {
        try {
            Long userId = securityHelper.getCurrentUserId();
            if (userId == null) {
                return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                        .body(ApiResponse.error(401, "未登录"));
            }

            List<String> userQqList = securityHelper.getCurrentUserQqBindings();
            if (userQqList.isEmpty()) {
                return ResponseEntity.status(HttpStatus.FORBIDDEN)
                        .body(ApiResponse.error(403, "请先绑定QQ账号"));
            }

            Object rawTypes = request.get("types");
            if (rawTypes == null) {
                return ResponseEntity.badRequest().body(ApiResponse.error(400, "请选择要删除的消息类型"));
            }
            List<Message.MessageType> types = new java.util.ArrayList<>();
            if (rawTypes instanceof java.util.Collection<?> c) {
                for (Object o : c) {
                    try {
                        types.add(Message.MessageType.valueOf(String.valueOf(o).toUpperCase()));
                    } catch (IllegalArgumentException ignored) {
                        // 忽略未知类型
                    }
                }
            }
            if (types.isEmpty()) {
                return ResponseEntity.badRequest().body(ApiResponse.error(400, "请选择有效的消息类型"));
            }

            boolean deleteMedia = Boolean.TRUE.equals(request.get("deleteMedia"));

            int deletedCount = messageService.deleteMessagesByGroupAndTypes(
                    groupId, types, userQqList, userId, deleteMedia);
            Map<String, Object> data = new HashMap<>();
            data.put("success", true);
            data.put("deletedCount", deletedCount);
            data.put("deleteMedia", deleteMedia);
            data.put("message", "成功删除 " + deletedCount + " 条消息" + (deleteMedia ? "（同时删除了关联媒体文件）" : ""));
            auditLogService.log(securityHelper.getCurrentUsername(), "MESSAGE_DELETE_BY_TYPES",
                    "group:" + groupId, "SUCCESS",
                    "按类型删除 " + deletedCount + " 条消息, types=" + types + (deleteMedia ? "（含媒体）" : "") + ", userId=" + userId);
            return ResponseEntity.ok(ApiResponse.success(data));
        } catch (com.qqai.exception.BizException biz) {
            throw biz;
        } catch (Exception e) {
            auditLogService.log(securityHelper.getCurrentUsername(), "MESSAGE_DELETE_BY_TYPES",
                    "group:" + groupId, "FAILURE", e.getMessage());
            log.error("删除失败", e);
            throw new com.qqai.exception.BizException(500, "DELETE_FAILED", "删除失败，请稍后重试");
        }
    }

    /**
     * 删除群聊会话（硬删除）：彻底删除指定QQ下该群的所有消息、媒体文件、群记录和已读状态。
     * 适用于已退出群聊的清理，操作不可恢复。
     *
     * 请求体示例:
     * {
     *   "ownerQq": "123456789"
     * }
     */
    @PostMapping("/group/{groupId}/delete-conversation")
    public ResponseEntity<ApiResponse<Map<String, Object>>> deleteGroupConversation(
            @PathVariable String groupId,
            @RequestBody(required = false) Map<String, Object> request) {
        try {
            Long userId = securityHelper.getCurrentUserId();
            if (userId == null) {
                return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                        .body(ApiResponse.error(401, "未登录"));
            }

            List<String> userQqList = securityHelper.getCurrentUserQqBindings();
            if (userQqList.isEmpty()) {
                return ResponseEntity.status(HttpStatus.FORBIDDEN)
                        .body(ApiResponse.error(403, "请先绑定QQ账号"));
            }

            // ownerQq 从请求体获取，默认取第一个绑定QQ
            String ownerQq = (request != null && request.get("ownerQq") != null)
                    ? String.valueOf(request.get("ownerQq")) : userQqList.get(0);

            // 权限校验：ownerQq 必须属于当前用户绑定的QQ
            if (!userQqList.contains(ownerQq)) {
                return ResponseEntity.status(HttpStatus.FORBIDDEN)
                        .body(ApiResponse.error(403, "无权操作该QQ的群聊"));
            }

            Map<String, Object> result = messageService.deleteGroupConversation(groupId, ownerQq, userId);
            auditLogService.log(securityHelper.getCurrentUsername(), "GROUP_DELETE_CONVERSATION",
                    "group:" + groupId + ",ownerQq:" + ownerQq, "SUCCESS",
                    "删除群聊会话: " + result.get("message") + ", userId=" + userId);
            return ResponseEntity.ok(ApiResponse.success(result));
        } catch (com.qqai.exception.BizException biz) {
            throw biz;
        } catch (Exception e) {
            auditLogService.log(securityHelper.getCurrentUsername(), "GROUP_DELETE_CONVERSATION",
                    "group:" + groupId, "FAILURE", e.getMessage());
            log.error("删除群聊失败", e);
            throw new com.qqai.exception.BizException(500, "GROUP_DELETE_FAILED", "删除群聊失败，请稍后重试");
        }
    }

    /**
     * 清理媒体文件（按类型扫描 backend/uploads 目录，删除匹配扩展名的文件）。
     *
     * 请求体示例:
     * {
     *   "types": ["IMAGE", "VIDEO"]
     * }
     * 可选类型: IMAGE / VIDEO / AUDIO / FILE / ALL
     */
    @PostMapping("/purge-media")
    public ResponseEntity<ApiResponse<Map<String, Object>>> purgeMedia(@RequestBody(required = false) Map<String, Object> request) {
        try {
            String role = securityHelper.getCurrentUserRole();
            if (!"ADMIN".equalsIgnoreCase(role)) {
                return ResponseEntity.status(HttpStatus.FORBIDDEN).body(ApiResponse.error(403, "需要管理员权限"));
            }

            Long userId = securityHelper.getCurrentUserId();
            if (userId == null) {
                return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                        .body(ApiResponse.error(401, "未登录"));
            }

            @SuppressWarnings("unchecked")
            List<String> types = (List<String>) (request == null ? null : request.get("types"));
            if (types == null || types.isEmpty()) {
                types = java.util.List.of("IMAGE", "VIDEO", "AUDIO");
            }

            com.qqai.service.FilePurgeService.PurgeResult result = filePurgeService.purgeMedia(types, securityHelper.getCurrentUsername());

            Map<String, Object> data = new HashMap<>();
            data.put("success", true);
            data.put("totalDeleted", result.getTotalDeleted());
            data.put("freedBytes", result.getFreedBytes());
            data.put("freedMB", result.getFreedMB());
            data.put("note", result.getNote() == null ? "" : result.getNote());
            data.put("message", "成功清理 " + result.getTotalDeleted() + " 个文件 (" + result.getFreedMB() + ")");
            return ResponseEntity.ok(ApiResponse.success(data));
        } catch (com.qqai.exception.BizException biz) {
                // 业务异常保持原有状态码与文案，交由 GlobalExceptionHandler 输出
                throw biz;
            } catch (Exception e) {
            log.error("清理媒体失败", e);
            throw new com.qqai.exception.BizException(500, "MEDIA_PURGE_FAILED", "清理媒体失败，请稍后重试");
        }
    }

    /**
     * 分页扫描本地媒体文件。
     *
     * 示例: GET /api/messages/media-files?type=IMAGE&page=0&size=20&sort=size,desc&from=2026-01-01
     * type 可选: IMAGE / VIDEO / AUDIO / FILE / ALL，为空或 ALL 时查询全部本地文件
     * sort 可选: time|size|name（默认 time,desc）；from/to 为修改日期（yyyy-MM-dd）
     * kw 为文件名关键字
     */
    @GetMapping("/media-files")
    public ResponseEntity<ApiResponse<Map<String, Object>>> listMediaFiles(
            @RequestParam(required = false) String type,
            @RequestParam(required = false) String kw,
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to,
            @RequestParam(required = false) String sort,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        try {
            String role = securityHelper.getCurrentUserRole();
            if (!"ADMIN".equalsIgnoreCase(role)) {
                return ResponseEntity.status(HttpStatus.FORBIDDEN).body(ApiResponse.error(403, "需要管理员权限"));
            }
            Long userId = securityHelper.getCurrentUserId();
            if (userId == null) {
                return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                        .body(ApiResponse.error(401, "未登录"));
            }

            // 分页上限统一收口在 common/PageLimits
            Pageable pageable = com.qqai.common.PageLimits.of(page, size);
            org.springframework.data.domain.Page<com.qqai.service.FilePurgeService.MediaFileDto> result =
                    filePurgeService.listMediaFiles(type, pageable, kw, parseDateOrNull(from), parseDateOrNull(to), sort);

            Map<String, Object> data = new HashMap<>();
            data.put("content", result.getContent());
            data.put("totalElements", result.getTotalElements());
            data.put("totalPages", result.getTotalPages());

            return ResponseEntity.ok(ApiResponse.success(data));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(ApiResponse.error(400, "无效的文件类型: " + type));
        } catch (com.qqai.exception.BizException biz) {
                // 业务异常保持原有状态码与文案，交由 GlobalExceptionHandler 输出
                throw biz;
            } catch (Exception e) {
            log.error("查询媒体文件失败", e);
            throw new com.qqai.exception.BizException(500, "MEDIA_QUERY_FAILED", "查询媒体文件失败，请稍后重试");
        }
    }

    /**
     * 各类型媒体文件的数量与占用体积（清理预览用）。
     * GET /api/messages/media-files/summary → { byType: { IMAGE: {count, bytes}, ..., TOTAL: {...} } }
     */
    @GetMapping("/media-files/summary")
    public ResponseEntity<ApiResponse<Map<String, Object>>> summarizeMediaFiles() {
        try {
            String role = securityHelper.getCurrentUserRole();
            if (!"ADMIN".equalsIgnoreCase(role)) {
                return ResponseEntity.status(HttpStatus.FORBIDDEN).body(ApiResponse.error(403, "需要管理员权限"));
            }
            Map<String, Object> data = new HashMap<>();
            data.put("byType", filePurgeService.summarizeMediaFiles().getByType());
            return ResponseEntity.ok(ApiResponse.success(data));
        } catch (com.qqai.exception.BizException biz) {
                // 业务异常保持原有状态码与文案，交由 GlobalExceptionHandler 输出
                throw biz;
            } catch (Exception e) {
            log.error("统计媒体文件失败", e);
            throw new com.qqai.exception.BizException(500, "MEDIA_SUMMARY_FAILED", "统计媒体文件失败，请稍后重试");
        }
    }

    /** 解析 yyyy-MM-dd 日期参数，无法解析时返回 null（不因一个脏参数让整页报错） */
    private java.time.LocalDate parseDateOrNull(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            String trimmed = value.trim();
            return java.time.LocalDate.parse(trimmed.length() > 10 ? trimmed.substring(0, 10) : trimmed);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 根据 ID 批量删除本地媒体文件。
     * ID 为 Base64 编码的文件相对路径。
     *
     * 请求体示例:
     * {
     *   "ids": ["aW1hZ2VzL3h4eC5qcGc=", "..."]
     * }
     */
    @PostMapping("/delete-media-files")
    public ResponseEntity<ApiResponse<Map<String, Object>>> deleteMediaFiles(@RequestBody Map<String, Object> request) {
        try {
            String role = securityHelper.getCurrentUserRole();
            if (!"ADMIN".equalsIgnoreCase(role)) {
                return ResponseEntity.status(HttpStatus.FORBIDDEN).body(ApiResponse.error(403, "需要管理员权限"));
            }

            Long userId = securityHelper.getCurrentUserId();
            if (userId == null) {
                return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                        .body(ApiResponse.error(401, "未登录"));
            }

            Object rawIds = request.get("ids");
            if (rawIds == null) {
                return ResponseEntity.badRequest().body(ApiResponse.error(400, "请选择要删除的文件"));
            }

            List<String> ids = new ArrayList<>();
            if (rawIds instanceof Collection<?> c) {
                for (Object o : c) {
                    if (o instanceof String s && !s.isBlank()) {
                        ids.add(s);
                    }
                }
            }
            if (ids.isEmpty()) {
                return ResponseEntity.badRequest().body(ApiResponse.error(400, "请选择要删除的文件"));
            }

            com.qqai.service.FilePurgeService.PurgeResult result = filePurgeService.deleteFilesByIds(ids, securityHelper.getCurrentUsername());

            Map<String, Object> data = new HashMap<>();
            data.put("success", true);
            data.put("totalDeleted", result.getTotalDeleted());
            data.put("freedBytes", result.getFreedBytes());
            data.put("freedMB", result.getFreedMB());
            data.put("message", result.getNote() == null
                    ? "成功删除 " + result.getTotalDeleted() + " 个文件 (" + result.getFreedMB() + ")"
                    : result.getNote());
            return ResponseEntity.ok(ApiResponse.success(data));
        } catch (com.qqai.exception.BizException biz) {
                // 业务异常保持原有状态码与文案，交由 GlobalExceptionHandler 输出
                throw biz;
            } catch (Exception e) {
            log.error("删除媒体文件失败", e);
            throw new com.qqai.exception.BizException(500, "MEDIA_DELETE_FAILED", "删除媒体文件失败，请稍后重试");
        }
    }

    /**
     * 手动触发归档
     */
    @PostMapping("/archive")
    public ResponseEntity<ApiResponse<String>> manualArchive(@RequestParam(defaultValue = "90") int daysBefore) {
        try {
            String role = securityHelper.getCurrentUserRole();
            if (!"ADMIN".equalsIgnoreCase(role)) {
                return ResponseEntity.status(HttpStatus.FORBIDDEN).body(ApiResponse.error(403, "需要管理员权限"));
            }

            messageService.manualArchive(daysBefore);
            return ResponseEntity.ok(ApiResponse.success("归档任务已触发"));
        } catch (com.qqai.exception.BizException biz) {
                // 业务异常保持原有状态码与文案，交由 GlobalExceptionHandler 输出
                throw biz;
            } catch (Exception e) {
            log.error("归档失败", e);
            throw new com.qqai.exception.BizException(500, "ARCHIVE_FAILED", "归档失败，请稍后重试");
        }
    }

    /**
     * 获取最近对话的群聊列表（附带未读消息数）。
     * 基于当前用户绑定的所有 QQ 号聚合查询，并以 group_read_state 统计未读。
     */
    @GetMapping("/recent-groups")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> getRecentGroups() {
        try {
            Long userId = securityHelper.getCurrentUserId();
            if (userId == null) {
                return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                        .body(ApiResponse.error(401, "未登录"));
            }

            List<String> userQqList = securityHelper.getCurrentUserQqBindings();
            if (userQqList.isEmpty()) {
                return ResponseEntity.ok(ApiResponse.success(Collections.emptyList()));
            }

            List<Map<String, Object>> groups = messageService.getRecentGroupsForUser(userId, userQqList);
            return ResponseEntity.ok(ApiResponse.success(groups));
        } catch (com.qqai.exception.BizException biz) {
                // 业务异常保持原有状态码与文案，交由 GlobalExceptionHandler 输出
                throw biz;
            } catch (Exception e) {
            log.error("获取最近对话失败", e);
            throw new com.qqai.exception.BizException(500, "RECENT_GROUPS_FAILED", "获取最近对话失败，请稍后重试");
        }
    }

    /**
     * 将某个群聊标记为已读
     */
    @PostMapping("/read/{groupId}")
    public ResponseEntity<ApiResponse<Map<String, Object>>> markGroupAsRead(@PathVariable String groupId) {
        try {
            Long userId = securityHelper.getCurrentUserId();
            if (userId == null) {
                return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                        .body(ApiResponse.error(401, "未登录"));
            }

            boolean hasAccess = securityHelper.hasGroupAccess(groupId);
            if (!hasAccess) {
                return ResponseEntity.status(HttpStatus.FORBIDDEN)
                        .body(ApiResponse.error(403, "无权访问该群聊"));
            }

            boolean ok = messageService.markGroupAsRead(userId, groupId);
            Map<String, Object> data = new HashMap<>();
            data.put("success", ok);
            data.put("groupId", groupId);
            return ResponseEntity.ok(ApiResponse.success(data));
        } catch (com.qqai.exception.BizException biz) {
                // 业务异常保持原有状态码与文案，交由 GlobalExceptionHandler 输出
                throw biz;
            } catch (Exception e) {
            log.error("标记已读失败", e);
            throw new com.qqai.exception.BizException(500, "MARK_READ_FAILED", "标记已读失败，请稍后重试");
        }
    }

    /**
     * 将当前用户全部群聊标记为已读
     */
    @PostMapping("/read-all")
    public ResponseEntity<ApiResponse<Map<String, Object>>> markAllAsRead() {
        try {
            Long userId = securityHelper.getCurrentUserId();
            if (userId == null) {
                return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                        .body(ApiResponse.error(401, "未登录"));
            }
            boolean ok = messageService.markAllAsRead(userId);
            Map<String, Object> data = new HashMap<>();
            data.put("success", ok);
            return ResponseEntity.ok(ApiResponse.success(data));
        } catch (com.qqai.exception.BizException biz) {
                // 业务异常保持原有状态码与文案，交由 GlobalExceptionHandler 输出
                throw biz;
            } catch (Exception e) {
            log.error("全部标为已读失败", e);
            throw new com.qqai.exception.BizException(500, "MARK_ALL_READ_FAILED", "全部标为已读失败，请稍后重试");
        }
    }

    /**
     * 按标签 / 关键词搜索群内消息：{@code GET /api/messages/search?groupId=&tag=&keyword=&limit=50}
     *
     * <ul>
     *   <li>权限口径与 {@link #getMessagesByGroupId(String, String)} 一致：未登录 401；未绑 QQ → 403
     *       「请先绑定QQ账号」；无群可见性 → 403「无权访问该群聊」；管理员跳过群可见性校验；</li>
     *   <li>{@code tag} 与 {@code keyword} 至少给一个，都不给 → 400「请提供 tag 或 keyword」；
     *       两者都给时取<b>交集</b>（{@code ai_tags LIKE %tag% AND content LIKE %keyword%}）；</li>
     *   <li>只返回未删除消息，按 {@code sendTime} 倒序，{@code limit} 收敛到 1 ~ 200（默认 50）。</li>
     * </ul>
     *
     * <p>返回 {@code data} 为数组，每项固定 10 个字段：
     * {@code id / groupId / sendTime / userNickname / userQq / messageType / contentSnippet / aiTags / aiSummaryShort / aiSentiment}。</p>
     */
    @GetMapping("/search")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> searchMessages(
            @RequestParam String groupId,
            @RequestParam(required = false) String tag,
            @RequestParam(required = false) String keyword,
            @RequestParam(defaultValue = "50") int limit) {

        Long userId = securityHelper.getCurrentUserId();
        if (userId == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(ApiResponse.error(401, "未登录"));
        }

        // 管理员跳过群可见性校验（非管理员仍需绑定 QQ + 群可见）
        if (!securityHelper.isAdmin()) {
            List<String> userQqList = securityHelper.getCurrentUserQqBindings();
            if (userQqList.isEmpty()) {
                return ResponseEntity.status(HttpStatus.FORBIDDEN)
                        .body(ApiResponse.error(403, "请先绑定QQ账号"));
            }
            if (!securityHelper.hasGroupAccess(groupId, userQqList)) {
                return ResponseEntity.status(HttpStatus.FORBIDDEN)
                        .body(ApiResponse.error(403, "无权访问该群聊"));
            }
        }

        boolean hasTag = tag != null && !tag.isBlank();
        boolean hasKeyword = keyword != null && !keyword.isBlank();
        if (!hasTag && !hasKeyword) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(ApiResponse.error(400, "请提供 tag 或 keyword"));
        }

        List<Map<String, Object>> views = messageService.searchMessages(groupId, tag, keyword, limit)
                .stream()
                .map(this::toSearchView)
                .toList();
        return ResponseEntity.ok(ApiResponse.success(views));
    }

    /** 搜索结果条目视图：字段名与前端契约逐字一致（messageType 用枚举 name()） */
    private Map<String, Object> toSearchView(Message message) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("id", message.getId());
        view.put("groupId", message.getGroupId());
        view.put("sendTime", formatSearchTime(message.getSendTime()));
        view.put("userNickname", message.getUserNickname());
        view.put("userQq", message.getUserQq());
        view.put("messageType", message.getMessageType() == null ? null : message.getMessageType().name());
        view.put("contentSnippet", buildContentSnippet(message.getContent(), SEARCH_SNIPPET_MAX_CHARS));
        view.put("aiTags", message.getAiTags());
        view.put("aiSummaryShort", message.getAiSummaryShort());
        view.put("aiSentiment", message.getAiSentiment());
        return view;
    }

    /** 搜索结果的 sendTime 统一成 {@code yyyy-MM-dd'T'HH:mm:ss}（避免 LocalDateTime.toString() 省略秒/带纳秒） */
    private static String formatSearchTime(java.time.LocalDateTime sendTime) {
        return sendTime == null ? null : SEARCH_TIME_FORMATTER.format(sendTime);
    }

    /**
     * 内容摘要：前 {@code max} 个字符（按码点截断，避免把 emoji 等代理对切成半个字符），超出补「…」。
     */
    private static String buildContentSnippet(String content, int max) {
        if (content == null) {
            return null;
        }
        if (content.codePointCount(0, content.length()) <= max) {
            return content;
        }
        return content.substring(0, content.offsetByCodePoints(0, max)) + "…";
    }

    private FileRecord.FileType getFileTypeFromMessageType(String messageType) {
        switch (messageType.toUpperCase()) {
            case "IMAGE":
                return FileRecord.FileType.IMAGE;
            case "VIDEO":
                return FileRecord.FileType.VIDEO;
            case "AUDIO":
            case "VOICE":
                return FileRecord.FileType.AUDIO;
            default:
                return FileRecord.FileType.FILE;
        }
    }
}

package com.qqai.controller;

import com.qqai.common.SecurityHelper;
import com.qqai.dto.common.ApiResponse;
import com.qqai.entity.FileRecord;
import com.qqai.entity.Message;
import com.qqai.service.AuditLogService;
import com.qqai.service.FileStorageService;
import com.qqai.service.MessageService;
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
    @Autowired
    private MessageService messageService;

    @Autowired
    private com.qqai.service.FilePurgeService filePurgeService;

    @Autowired
    private FileStorageService fileStorageService;

    @Autowired
    private SecurityHelper securityHelper;

    @Autowired
    private AuditLogService auditLogService;



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

        List<Map<String, Object>> members = messageService.getGroupMemberNicknames(groupId);
        return ResponseEntity.ok(ApiResponse.success(members));
    }

    private final java.util.concurrent.locks.ReentrantLock processLock = new java.util.concurrent.locks.ReentrantLock();

    @PostMapping("/process")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ApiResponse<Void>> processAllMessages() {
        if (!processLock.tryLock()) {
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(ApiResponse.error(409, "任务正在执行中，请勿重复调用"));
        }
        try {
            messageService.processAllUnprocessedMessages();
            return ResponseEntity.ok(ApiResponse.success());
        } finally {
            processLock.unlock();
        }
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
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(ApiResponse.error(400, "上传失败: " + e.getMessage()));
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
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(ApiResponse.error(400, "发送失败: " + e.getMessage()));
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
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(ApiResponse.error(400, "删除失败: " + e.getMessage()));
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
                    .orElseThrow(() -> new RuntimeException("消息不存在"));

            if (!userQqList.contains(message.getSelfQq())) {
                return ResponseEntity.status(HttpStatus.FORBIDDEN)
                        .body(ApiResponse.error(403, "无权删除该消息"));
            }

            messageService.deleteMessage(messageId, userId, userQqList);
            return ResponseEntity.ok(ApiResponse.success());
        } catch (RuntimeException e) {
            return ResponseEntity.badRequest().body(ApiResponse.error(400, e.getMessage()));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(ApiResponse.error(400, "删除失败: " + e.getMessage()));
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
        } catch (Exception e) {
            auditLogService.log(securityHelper.getCurrentUsername(), "MESSAGE_DELETE_BATCH",
                    "messages", "FAILURE", e.getMessage());
            return ResponseEntity.badRequest().body(ApiResponse.error(400, "删除失败: " + e.getMessage()));
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
        } catch (Exception e) {
            auditLogService.log(securityHelper.getCurrentUsername(), "MESSAGE_DELETE_BY_TYPES",
                    "group:" + groupId, "FAILURE", e.getMessage());
            return ResponseEntity.badRequest().body(ApiResponse.error(400, "删除失败: " + e.getMessage()));
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
        } catch (Exception e) {
            auditLogService.log(securityHelper.getCurrentUsername(), "GROUP_DELETE_CONVERSATION",
                    "group:" + groupId, "FAILURE", e.getMessage());
            return ResponseEntity.badRequest().body(ApiResponse.error(400, "删除群聊失败: " + e.getMessage()));
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
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(ApiResponse.error(400, "清理媒体失败: " + e.getMessage()));
        }
    }

    /**
     * 分页扫描本地媒体文件。
     *
     * 示例: GET /api/messages/media-files?type=IMAGE&page=0&size=20
     * type 可选: IMAGE / VIDEO / AUDIO / FILE / ALL，为空或 ALL 时查询全部本地文件
     */
    @GetMapping("/media-files")
    public ResponseEntity<ApiResponse<Map<String, Object>>> listMediaFiles(
            @RequestParam(required = false) String type,
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

            Pageable pageable = PageRequest.of(page, size);
            org.springframework.data.domain.Page<com.qqai.service.FilePurgeService.MediaFileDto> result =
                    filePurgeService.listMediaFiles(type, pageable);

            Map<String, Object> data = new HashMap<>();
            data.put("content", result.getContent());
            data.put("totalElements", result.getTotalElements());
            data.put("totalPages", result.getTotalPages());

            return ResponseEntity.ok(ApiResponse.success(data));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(ApiResponse.error(400, "无效的文件类型: " + type));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(ApiResponse.error(400, "查询媒体文件失败: " + e.getMessage()));
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
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(ApiResponse.error(400, "删除媒体文件失败: " + e.getMessage()));
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
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(ApiResponse.error(400, "归档失败: " + e.getMessage()));
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
        } catch (Exception e) {
            return ResponseEntity.badRequest()
                    .body(ApiResponse.error(400, "获取最近对话失败: " + e.getMessage()));
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
        } catch (Exception e) {
            return ResponseEntity.badRequest()
                    .body(ApiResponse.error(400, "标记已读失败: " + e.getMessage()));
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
        } catch (Exception e) {
            return ResponseEntity.badRequest()
                    .body(ApiResponse.error(400, "全部标为已读失败: " + e.getMessage()));
        }
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

package com.qqai.controller;

import com.qqai.entity.FileRecord;
import com.qqai.entity.Message;
import com.qqai.entity.User;
import com.qqai.entity.UserQqBinding;
import com.qqai.repository.UserQqBindingRepository;
import com.qqai.repository.UserRepository;
import com.qqai.service.FileStorageService;
import com.qqai.service.MessageService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.*;
import java.util.stream.Collectors;

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
    private UserRepository userRepository;

    @Autowired
    private UserQqBindingRepository userQqBindingRepository;

    /**
     * 获取当前登录用户ID
     */
    private Long getCurrentUserId() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.isAuthenticated()) {
            String username = authentication.getName();
            // 从数据库查询用户ID
            Optional<User> userOpt = userRepository.findByUsername(username);
            return userOpt.map(User::getId).orElse(null);
        }
        return null;
    }

    /**
     * 获取当前用户绑定的所有QQ账号
     */
    private List<String> getCurrentUserQqBindings() {
        Long userId = getCurrentUserId();
        if (userId == null) {
            return Collections.emptyList();
        }
        
        List<UserQqBinding> bindings = userQqBindingRepository.findByUserIdAndActiveTrue(userId);
        return bindings.stream()
                .map(UserQqBinding::getQqNumber)
                .collect(Collectors.toList());
    }

    @PostMapping
    public Message createMessage(@RequestBody Message message) {
        return messageService.saveMessage(message);
    }

    @GetMapping("/group/{groupId}")
    public ResponseEntity<?> getMessagesByGroupId(@PathVariable String groupId) {
        Long userId = getCurrentUserId();
        if (userId == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("error", "未登录", "code", 401));
        }
        
        // 获取用户绑定的所有QQ账号
        List<String> userQqList = getCurrentUserQqBindings();
        if (userQqList.isEmpty()) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(Map.of("error", "请先绑定QQ账号", "code", 403));
        }
        
        // 检查用户是否有权限访问该群聊（使用绑定的任意一个QQ号）
        boolean hasAccess = false;
        for (String qq : userQqList) {
            List<String> userGroupIds = messageService.getUserGroupIds(qq);
            if (userGroupIds.contains(groupId)) {
                hasAccess = true;
                break;
            }
        }
        
        if (!hasAccess) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(Map.of("error", "无权访问该群聊", "code", 403));
        }
        
        // 使用用户绑定的QQ号列表查询消息
        List<Message> messages = messageService.getMessagesByGroupIdAndUserQqList(groupId, userQqList);
        return ResponseEntity.ok(messages);
    }

    @GetMapping("/group/{groupId}/paged")
    public ResponseEntity<?> getMessagesByGroupIdPaged(
            @PathVariable String groupId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {
        Long userId = getCurrentUserId();
        if (userId == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("error", "未登录", "code", 401));
        }
        
        // 获取用户绑定的所有QQ账号
        List<String> userQqList = getCurrentUserQqBindings();
        if (userQqList.isEmpty()) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(Map.of("error", "请先绑定QQ账号", "code", 403));
        }
        
        // 检查用户是否有权限访问该群聊
        boolean hasAccess = false;
        for (String qq : userQqList) {
            List<String> userGroupIds = messageService.getUserGroupIds(qq);
            if (userGroupIds.contains(groupId)) {
                hasAccess = true;
                break;
            }
        }
        
        if (!hasAccess) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(Map.of("error", "无权访问该群聊", "code", 403));
        }
        
        Pageable pageable = PageRequest.of(page, size);
        List<Message> messages = messageService.getMessagesByGroupIdPagedAndUserQqList(groupId, userQqList, pageable);
        Long total = messageService.countMessagesByGroupIdAndUserQqList(groupId, userQqList);
        
        Map<String, Object> response = new HashMap<>();
        response.put("messages", messages);
        response.put("total", total);
        response.put("page", page);
        response.put("size", size);
        
        return ResponseEntity.ok(response);
    }

    @GetMapping("/group/{groupId}/since")
    public ResponseEntity<?> getMessagesSince(
            @PathVariable String groupId,
            @RequestParam Long afterId) {
        Long userId = getCurrentUserId();
        if (userId == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("error", "未登录", "code", 401));
        }

        List<String> userQqList = getCurrentUserQqBindings();
        if (userQqList.isEmpty()) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(Map.of("error", "请先绑定QQ账号", "code", 403));
        }

        boolean hasAccess = false;
        for (String qq : userQqList) {
            List<String> userGroupIds = messageService.getUserGroupIds(qq);
            if (userGroupIds.contains(groupId)) {
                hasAccess = true;
                break;
            }
        }

        if (!hasAccess) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(Map.of("error", "无权访问该群聊", "code", 403));
        }

        List<Message> messages = messageService.getMessagesSinceId(groupId, userQqList, afterId);
        return ResponseEntity.ok(messages);
    }

    /**
     * 获取群聊成员 QQ 号与昵称映射（用于 @消息解析）
     */
    @GetMapping("/group/{groupId}/members")
    public ResponseEntity<?> getGroupMembers(@PathVariable String groupId) {
        Long userId = getCurrentUserId();
        if (userId == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("error", "未登录", "code", 401));
        }

        List<String> userQqList = getCurrentUserQqBindings();
        if (userQqList.isEmpty()) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(Map.of("error", "请先绑定QQ账号", "code", 403));
        }

        boolean hasAccess = false;
        for (String qq : userQqList) {
            List<String> userGroupIds = messageService.getUserGroupIds(qq);
            if (userGroupIds.contains(groupId)) {
                hasAccess = true;
                break;
            }
        }

        if (!hasAccess) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(Map.of("error", "无权访问该群聊", "code", 403));
        }

        List<Map<String, Object>> members = messageService.getGroupMemberNicknames(groupId);
        return ResponseEntity.ok(members);
    }

    @PostMapping("/process")
    public void processAllMessages() {
        messageService.processAllUnprocessedMessages();
    }

    /**
     * 上传文件
     */
    @PostMapping("/upload")
    public ResponseEntity<?> uploadFile(
            @RequestParam("file") MultipartFile file,
            @RequestParam("fileType") String fileType) {
        try {
            FileRecord.FileType type = FileRecord.FileType.valueOf(fileType.toUpperCase());
            FileRecord fileRecord = fileStorageService.uploadFile(file, type);
            return ResponseEntity.ok(fileRecord);
        } catch (Exception e) {
            return ResponseEntity.badRequest().body("上传失败: " + e.getMessage());
        }
    }

    /**
     * 发送带文件的消息
     */
    @PostMapping("/send-with-file")
    public ResponseEntity<?> sendMessageWithFile(
            @RequestParam("groupId") String groupId,
            @RequestParam("userQq") String userQq,
            @RequestParam("userNickname") String userNickname,
            @RequestParam("content") String content,
            @RequestParam("messageType") String messageType,
            @RequestParam(value = "file", required = false) MultipartFile file) {
        try {
            Message message = new Message();
            message.setGroupId(groupId);
            message.setUserQq(userQq);
            message.setUserNickname(userNickname);
            message.setContent(content);
            message.setMessageType(Message.MessageType.valueOf(messageType.toUpperCase()));

            // 如果有文件，先上传文件
            if (file != null && !file.isEmpty()) {
                FileRecord.FileType fileType = getFileTypeFromMessageType(messageType);
                FileRecord fileRecord = fileStorageService.uploadFile(file, fileType);
                message.setFileId(fileRecord.getFileId());
            }

            Message savedMessage = messageService.saveMessage(message);
            return ResponseEntity.ok(savedMessage);
        } catch (Exception e) {
            return ResponseEntity.badRequest().body("发送失败: " + e.getMessage());
        }
    }

    /**
     * 获取文件信息
     */
    @GetMapping("/file/{fileId}")
    public ResponseEntity<?> getFileInfo(@PathVariable String fileId) {
        try {
            FileRecord fileRecord = fileStorageService.getFileRecord(fileId);
            return ResponseEntity.ok(fileRecord);
        } catch (Exception e) {
            return ResponseEntity.notFound().build();
        }
    }

    /**
     * 删除文件
     */
    @DeleteMapping("/file/{fileId}")
    public ResponseEntity<?> deleteFile(@PathVariable String fileId) {
        try {
            fileStorageService.deleteFile(fileId);
            return ResponseEntity.ok().build();
        } catch (Exception e) {
            return ResponseEntity.badRequest().body("删除失败: " + e.getMessage());
        }
    }

    /**
     * 删除单条消息
     */
    @DeleteMapping("/{messageId}")
    public ResponseEntity<?> deleteMessage(@PathVariable Long messageId) {
        try {
            Long userId = getCurrentUserId();
            if (userId == null) {
                return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                        .body(Map.of("error", "未登录", "code", 401));
            }

            List<String> userQqList = getCurrentUserQqBindings();
            if (userQqList.isEmpty()) {
                return ResponseEntity.status(HttpStatus.FORBIDDEN)
                        .body(Map.of("error", "请先绑定QQ账号", "code", 403));
            }

            Message message = messageService.getMessageById(messageId)
                    .orElseThrow(() -> new RuntimeException("消息不存在"));

            if (!userQqList.contains(message.getSelfQq())) {
                return ResponseEntity.status(HttpStatus.FORBIDDEN)
                        .body(Map.of("error", "无权删除该消息", "code", 403));
            }

            messageService.deleteMessage(messageId, userId);
            return ResponseEntity.ok().body(Map.of("success", true, "message", "删除成功"));
        } catch (RuntimeException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", "删除失败: " + e.getMessage()));
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
    public ResponseEntity<?> deleteMessagesBatch(@RequestBody Map<String, Object> request) {
        try {
            Long userId = getCurrentUserId();
            if (userId == null) {
                return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                        .body(Map.of("error", "未登录", "code", 401));
            }

            List<String> userQqList = getCurrentUserQqBindings();
            if (userQqList.isEmpty()) {
                return ResponseEntity.status(HttpStatus.FORBIDDEN)
                        .body(Map.of("error", "请先绑定QQ账号", "code", 403));
            }

            Object rawIds = request.get("messageIds");
            if (rawIds == null) {
                return ResponseEntity.badRequest().body(Map.of("error", "请选择要删除的消息"));
            }
            // 兼容 list 或 number 形式
            List<Long> messageIds = new java.util.ArrayList<>();
            if (rawIds instanceof java.util.Collection<?> c) {
                for (Object o : c) {
                    if (o instanceof Number n) messageIds.add(n.longValue());
                }
            }
            if (messageIds.isEmpty()) {
                return ResponseEntity.badRequest().body(Map.of("error", "请选择要删除的消息"));
            }

            boolean deleteMedia = Boolean.TRUE.equals(request.get("deleteMedia"));

            int deletedCount = messageService.deleteMessagesByIds(messageIds, userQqList, userId, deleteMedia);
            return ResponseEntity.ok().body(Map.of(
                    "success", true,
                    "deletedCount", deletedCount,
                    "deleteMedia", deleteMedia,
                    "message", "成功删除 " + deletedCount + " 条消息" + (deleteMedia ? "（同时删除了关联媒体文件）" : "")
            ));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", "删除失败: " + e.getMessage()));
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
    public ResponseEntity<?> purgeMedia(@RequestBody(required = false) Map<String, Object> request) {
        try {
            Long userId = getCurrentUserId();
            if (userId == null) {
                return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                        .body(Map.of("error", "未登录", "code", 401));
            }

            @SuppressWarnings("unchecked")
            List<String> types = (List<String>) (request == null ? null : request.get("types"));
            if (types == null || types.isEmpty()) {
                types = java.util.List.of("IMAGE", "VIDEO", "AUDIO");
            }

            com.qqai.service.FilePurgeService.PurgeResult result = filePurgeService.purgeMedia(types);

            return ResponseEntity.ok().body(Map.of(
                    "success", true,
                    "totalDeleted", result.getTotalDeleted(),
                    "freedBytes", result.getFreedBytes(),
                    "freedMB", result.getFreedMB(),
                    "note", result.getNote() == null ? "" : result.getNote(),
                    "message", "成功清理 " + result.getTotalDeleted() + " 个文件 (" + result.getFreedMB() + ")"
            ));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", "清理媒体失败: " + e.getMessage()));
        }
    }

    /**
     * 分页扫描本地媒体文件。
     *
     * 示例: GET /api/messages/media-files?type=IMAGE&page=0&size=20
     * type 可选: IMAGE / VIDEO / AUDIO / FILE / ALL，为空或 ALL 时查询全部本地文件
     */
    @GetMapping("/media-files")
    public ResponseEntity<?> listMediaFiles(
            @RequestParam(required = false) String type,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        try {
            Long userId = getCurrentUserId();
            if (userId == null) {
                return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                        .body(Map.of("error", "未登录", "code", 401));
            }

            Pageable pageable = PageRequest.of(page, size);
            org.springframework.data.domain.Page<com.qqai.service.FilePurgeService.MediaFileDto> result =
                    filePurgeService.listMediaFiles(type, pageable);

            Map<String, Object> data = new HashMap<>();
            data.put("content", result.getContent());
            data.put("totalElements", result.getTotalElements());
            data.put("totalPages", result.getTotalPages());

            return ResponseEntity.ok(Map.of("success", true, "data", data));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", "无效的文件类型: " + type));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", "查询媒体文件失败: " + e.getMessage()));
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
    public ResponseEntity<?> deleteMediaFiles(@RequestBody Map<String, Object> request) {
        try {
            Long userId = getCurrentUserId();
            if (userId == null) {
                return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                        .body(Map.of("error", "未登录", "code", 401));
            }

            Object rawIds = request.get("ids");
            if (rawIds == null) {
                return ResponseEntity.badRequest().body(Map.of("error", "请选择要删除的文件"));
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
                return ResponseEntity.badRequest().body(Map.of("error", "请选择要删除的文件"));
            }

            com.qqai.service.FilePurgeService.PurgeResult result = filePurgeService.deleteFilesByIds(ids);

            return ResponseEntity.ok().body(Map.of(
                    "success", true,
                    "totalDeleted", result.getTotalDeleted(),
                    "freedBytes", result.getFreedBytes(),
                    "freedMB", result.getFreedMB(),
                    "message", result.getNote() == null
                            ? "成功删除 " + result.getTotalDeleted() + " 个文件 (" + result.getFreedMB() + ")"
                            : result.getNote()
            ));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", "删除媒体文件失败: " + e.getMessage()));
        }
    }

    /**
     * 手动触发归档
     */
    @PostMapping("/archive")
    public ResponseEntity<?> manualArchive(@RequestParam(defaultValue = "90") int daysBefore) {
        try {
            messageService.manualArchive(daysBefore);
            return ResponseEntity.ok("归档任务已触发");
        } catch (Exception e) {
            return ResponseEntity.badRequest().body("归档失败: " + e.getMessage());
        }
    }

    /**
     * 获取最近对话的群聊列表（附带未读消息数）。
     * 基于当前用户绑定的所有 QQ 号聚合查询，并以 group_read_state 统计未读。
     */
    @GetMapping("/recent-groups")
    public ResponseEntity<?> getRecentGroups() {
        try {
            Long userId = getCurrentUserId();
            if (userId == null) {
                return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                        .body(Map.of("error", "未登录", "code", 401));
            }

            List<String> userQqList = getCurrentUserQqBindings();
            if (userQqList.isEmpty()) {
                return ResponseEntity.ok(Collections.emptyList());
            }

            List<Map<String, Object>> groups = messageService.getRecentGroupsForUser(userId, userQqList);
            return ResponseEntity.ok(groups);
        } catch (Exception e) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "获取最近对话失败: " + e.getMessage(), "code", 400));
        }
    }

    /**
     * 将某个群聊标记为已读
     */
    @PostMapping("/read/{groupId}")
    public ResponseEntity<?> markGroupAsRead(@PathVariable String groupId) {
        try {
            Long userId = getCurrentUserId();
            if (userId == null) {
                return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                        .body(Map.of("error", "未登录", "code", 401));
            }

            // 权限校验：该群聊必须属于用户绑定的某个 QQ 号
            List<String> userQqList = getCurrentUserQqBindings();
            boolean hasAccess = false;
            for (String qq : userQqList) {
                List<String> userGroupIds = messageService.getUserGroupIds(qq);
                if (userGroupIds.contains(groupId)) {
                    hasAccess = true;
                    break;
                }
            }
            if (!hasAccess) {
                return ResponseEntity.status(HttpStatus.FORBIDDEN)
                        .body(Map.of("error", "无权访问该群聊", "code", 403));
            }

            boolean ok = messageService.markGroupAsRead(userId, groupId);
            return ResponseEntity.ok(Map.of("success", ok, "groupId", groupId));
        } catch (Exception e) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "标记已读失败: " + e.getMessage(), "code", 400));
        }
    }

    /**
     * 将当前用户全部群聊标记为已读
     */
    @PostMapping("/read-all")
    public ResponseEntity<?> markAllAsRead() {
        try {
            Long userId = getCurrentUserId();
            if (userId == null) {
                return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                        .body(Map.of("error", "未登录", "code", 401));
            }
            boolean ok = messageService.markAllAsRead(userId);
            return ResponseEntity.ok(Map.of("success", ok));
        } catch (Exception e) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "全部标为已读失败: " + e.getMessage(), "code", 400));
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

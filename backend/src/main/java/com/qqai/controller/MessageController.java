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
            Optional<User> userOpt = userRepository.findByQq(username);
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
     * 获取最近对话的群聊
     * 基于用户绑定的QQ账号查询
     */
    @GetMapping("/recent-groups")
    public ResponseEntity<?> getRecentGroups() {
        try {
            Long userId = getCurrentUserId();
            if (userId == null) {
                return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                        .body(Map.of("error", "未登录", "code", 401));
            }
            
            // 获取用户绑定的所有QQ账号
            List<String> userQqList = getCurrentUserQqBindings();
            if (userQqList.isEmpty()) {
                return ResponseEntity.ok(Collections.emptyList());
            }
            
            // 获取所有绑定QQ的群聊
            List<Map<String, Object>> allGroups = new ArrayList<>();
            Set<String> addedGroupIds = new HashSet<>();
            
            for (String qq : userQqList) {
                List<Map<String, Object>> groups = messageService.getRecentGroups(qq);
                for (Map<String, Object> group : groups) {
                    String groupId = (String) group.get("groupId");
                    if (!addedGroupIds.contains(groupId)) {
                        addedGroupIds.add(groupId);
                        allGroups.add(group);
                    }
                }
            }
            
            return ResponseEntity.ok(allGroups);
        } catch (Exception e) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "获取最近对话失败: " + e.getMessage(), "code", 400));
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

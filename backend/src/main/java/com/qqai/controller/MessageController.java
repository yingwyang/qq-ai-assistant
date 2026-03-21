package com.qqai.controller;

import com.qqai.entity.FileRecord;
import com.qqai.entity.Message;
import com.qqai.service.FileStorageService;
import com.qqai.service.MessageService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/messages")
public class MessageController {
    @Autowired
    private MessageService messageService;

    @Autowired
    private FileStorageService fileStorageService;

    @PostMapping
    public Message createMessage(@RequestBody Message message) {
        return messageService.saveMessage(message);
    }

    @GetMapping("/group/{groupId}")
    public List<Message> getMessagesByGroupId(@PathVariable String groupId) {
        return messageService.getMessagesByGroupId(groupId);
    }

    @GetMapping("/group/{groupId}/paged")
    public ResponseEntity<?> getMessagesByGroupIdPaged(
            @PathVariable String groupId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {
        Pageable pageable = PageRequest.of(page, size);
        List<Message> messages = messageService.getMessagesByGroupIdPaged(groupId, pageable);
        Long total = messageService.countMessagesByGroupId(groupId);
        
        Map<String, Object> response = new HashMap<>();
        response.put("messages", messages);
        response.put("total", total);
        response.put("page", page);
        response.put("size", size);
        
        return ResponseEntity.ok(response);
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
     */
    @GetMapping("/recent-groups")
    public ResponseEntity<?> getRecentGroups(@RequestParam(value = "userId", required = false) String userId) {
        try {
            List<Map<String, Object>> recentGroups = messageService.getRecentGroups(userId);
            return ResponseEntity.ok(recentGroups);
        } catch (Exception e) {
            return ResponseEntity.badRequest().body("获取最近对话失败: " + e.getMessage());
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

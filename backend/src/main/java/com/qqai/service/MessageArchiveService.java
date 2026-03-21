package com.qqai.service;

import com.qqai.entity.Message;
import com.qqai.repository.MessageRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * 消息归档服务
 */
@Service
public class MessageArchiveService {

    @Autowired
    private MessageRepository messageRepository;

    @Value("${archive.days-before:90}")
    private int archiveDaysBefore;

    @Value("${file.storage.archive-path:./uploads/archive}")
    private String archivePath;

    /**
     * 每天凌晨2点执行归档任务
     */
    @Scheduled(cron = "${archive.cron:0 0 2 * * ?}")
    @Transactional
    public void archiveOldMessages() {
        System.out.println("开始执行消息归档任务...");
        
        LocalDateTime cutoffDate = LocalDateTime.now().minusDays(archiveDaysBefore);
        
        // 查询需要归档的消息
        List<Message> oldMessages = messageRepository.findBySendTimeBeforeAndArchivedFalse(cutoffDate);
        
        if (oldMessages.isEmpty()) {
            System.out.println("没有需要归档的消息");
            return;
        }
        
        System.out.println("找到 " + oldMessages.size() + " 条需要归档的消息");
        
        // 按月份分组归档
        String currentMonth = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy_MM"));
        String archiveFileName = "messages_" + currentMonth + "_archive.json";
        
        // 归档到文件
        archiveToFile(oldMessages, archiveFileName);
        
        // 标记为已归档
        for (Message message : oldMessages) {
            message.setArchived(true);
        }
        messageRepository.saveAll(oldMessages);
        
        System.out.println("消息归档完成，已归档 " + oldMessages.size() + " 条消息");
    }

    /**
     * 归档消息到文件
     */
    private void archiveToFile(List<Message> messages, String fileName) {
        try {
            // 确保归档目录存在
            File archiveDir = new File(archivePath);
            if (!archiveDir.exists()) {
                archiveDir.mkdirs();
            }
            
            File archiveFile = new File(archiveDir, fileName);
            
            try (FileWriter writer = new FileWriter(archiveFile, true)) {
                for (Message message : messages) {
                    String json = convertToJson(message);
                    writer.write(json + "\n");
                }
            }
            
            System.out.println("消息已归档到文件: " + archiveFile.getAbsolutePath());
        } catch (IOException e) {
            System.err.println("归档消息失败: " + e.getMessage());
            throw new RuntimeException("归档失败", e);
        }
    }

    /**
     * 将消息转换为JSON字符串
     */
    private String convertToJson(Message message) {
        return String.format(
            "{\"id\":%d,\"messageId\":\"%s\",\"groupId\":\"%s\",\"userQq\":\"%s\",\"content\":\"%s\",\"sendTime\":\"%s\"}",
            message.getId(),
            message.getMessageId() != null ? message.getMessageId() : "",
            message.getGroupId(),
            message.getUserQq(),
            message.getContent() != null ? message.getContent().replace("\"", "\\\"") : "",
            message.getSendTime().toString()
        );
    }

    /**
     * 清理已归档的消息（从数据库删除）
     */
    @Scheduled(cron = "0 0 3 * * ?") // 每天凌晨3点执行
    @Transactional
    public void cleanupArchivedMessages() {
        System.out.println("开始清理已归档消息...");
        
        LocalDateTime cutoffDate = LocalDateTime.now().minusDays(archiveDaysBefore + 30); // 归档后30天才删除
        
        List<Message> archivedMessages = messageRepository.findBySendTimeBeforeAndArchivedTrue(cutoffDate);
        
        if (!archivedMessages.isEmpty()) {
            messageRepository.deleteAll(archivedMessages);
            System.out.println("已删除 " + archivedMessages.size() + " 条已归档消息");
        }
    }

    /**
     * 手动触发归档
     */
    public void manualArchive(int daysBefore) {
        LocalDateTime cutoffDate = LocalDateTime.now().minusDays(daysBefore);
        List<Message> oldMessages = messageRepository.findBySendTimeBeforeAndArchivedFalse(cutoffDate);
        
        if (!oldMessages.isEmpty()) {
            String archiveFileName = "messages_manual_" + System.currentTimeMillis() + "_archive.json";
            archiveToFile(oldMessages, archiveFileName);
            
            for (Message message : oldMessages) {
                message.setArchived(true);
            }
            messageRepository.saveAll(oldMessages);
        }
    }
}

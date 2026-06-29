package com.qqai.config;

import com.qqai.entity.Message;
import com.qqai.repository.MessageRepository;
import com.qqai.service.MediaDownloadService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 启动后异步迁移历史未处理的语音媒体消息，将本地/远程语音文件下载并转换为 MP3。
 * 该任务仅处理 content 中仍包含 CQ 码且未指向本地路径的 VOICE 消息。
 */
@Component
public class HistoryMediaMigration implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(HistoryMediaMigration.class);

    @Autowired
    private MessageRepository messageRepository;

    @Autowired
    private MediaDownloadService mediaDownloadService;

    @Override
    public void run(String... args) {
        try {
            Thread.sleep(5000); // 等待应用完全启动
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        new Thread(this::migrateVoiceMessages, "history-media-migration").start();
    }

    private void migrateVoiceMessages() {
        try {
            List<Message> voiceMessages = messageRepository.findByMessageType(Message.MessageType.VOICE);
            if (voiceMessages == null || voiceMessages.isEmpty()) {
                return;
            }
            log.info("开始迁移历史语音消息，共 {} 条", voiceMessages.size());
            int success = 0;
            int skipped = 0;
            int failed = 0;
            for (Message message : voiceMessages) {
                String content = message.getContent();
                if (content == null || content.isBlank()) {
                    skipped++;
                    continue;
                }
                // 已经是本地路径则跳过
                if (content.startsWith("/images/") || content.startsWith("/uploads/")) {
                    skipped++;
                    continue;
                }
                if (!content.contains("[CQ:record") && !content.contains("[CQ:voice")) {
                    skipped++;
                    continue;
                }
                try {
                    String localUrl = mediaDownloadService.downloadMediaToLocal(content, message.getGroupId(), "voice", ".amr");
                    if (localUrl != null && !localUrl.isBlank()) {
                        message.setContent(localUrl);
                        messageRepository.save(message);
                        success++;
                        log.info("历史语音迁移成功, id={}, localUrl={}", message.getId(), localUrl);
                    } else {
                        failed++;
                        log.warn("历史语音迁移失败, id={}", message.getId());
                    }
                } catch (Exception e) {
                    failed++;
                    log.error("历史语音迁移异常, id={}: {}", message.getId(), e.getMessage());
                }
            }
            log.info("历史语音迁移完成: 成功={}, 跳过={}, 失败={}", success, skipped, failed);
        } catch (Exception e) {
            log.error("历史语音迁移任务异常: {}", e.getMessage(), e);
        }
    }
}

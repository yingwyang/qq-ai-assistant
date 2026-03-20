package com.qqai.service;

import com.qqai.entity.Message;
import com.qqai.repository.MessageRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

@Service
public class MessageService {
    @Autowired
    private MessageRepository messageRepository;

    @Autowired
    private AstrBotService astrBotService;

    public Message saveMessage(Message message) {
        message.setTimestamp(LocalDateTime.now());
        message.setProcessed(false);
        Message savedMessage = messageRepository.save(message);
        // 异步处理消息
        processMessage(savedMessage);
        return savedMessage;
    }

    public void processMessage(Message message) {
        try {
            String summary = astrBotService.summarizeMessage(message.getContent());
            message.setAiSummary(summary);
            message.setProcessed(true);
            messageRepository.save(message);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public List<Message> getMessagesByGroupId(String groupId) {
        return messageRepository.findByGroupId(groupId);
    }

    public List<Message> getUnprocessedMessages() {
        return messageRepository.findByProcessedFalse();
    }

    public void processAllUnprocessedMessages() {
        List<Message> unprocessedMessages = getUnprocessedMessages();
        for (Message message : unprocessedMessages) {
            processMessage(message);
        }
    }
}
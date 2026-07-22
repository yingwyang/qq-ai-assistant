package com.qqai.event;

import com.qqai.entity.Message;
import com.qqai.repository.MessageRepository;
import com.qqai.service.AstrBotService;
import com.qqai.service.MessageBroadcastService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

@Component
public class MessageEventListener {

    private static final Logger log = LoggerFactory.getLogger(MessageEventListener.class);

    private final AstrBotService astrBotService;
    private final MessageRepository messageRepository;
    private final MessageBroadcastService messageBroadcastService;

    public MessageEventListener(AstrBotService astrBotService,
                                MessageRepository messageRepository,
                                MessageBroadcastService messageBroadcastService) {
        this.astrBotService = astrBotService;
        this.messageRepository = messageRepository;
        this.messageBroadcastService = messageBroadcastService;
    }

    @Async("messageTaskExecutor")
    @EventListener
    public void handleMessageSaved(MessageSavedEvent event) {
        Message message = event.getMessage();
        if (message == null || message.getId() == null) {
            return;
        }

        try {
            String summary = astrBotService.summarizeMessage(message.getContent());
            message.setAiSummary(summary);
            message.setProcessed(true);
            Message updated = messageRepository.save(message);
            messageBroadcastService.broadcastMessageUpdate(updated);
        } catch (Exception e) {
            log.error("异步处理消息失败, id={}: {}", message.getId(), e.getMessage());
        }
    }
}

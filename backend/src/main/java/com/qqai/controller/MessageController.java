package com.qqai.controller;

import com.qqai.entity.Message;
import com.qqai.service.MessageService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/messages")
public class MessageController {
    @Autowired
    private MessageService messageService;

    @PostMapping
    public Message createMessage(@RequestBody Message message) {
        return messageService.saveMessage(message);
    }

    @GetMapping("/group/{groupId}")
    public List<Message> getMessagesByGroupId(@PathVariable String groupId) {
        return messageService.getMessagesByGroupId(groupId);
    }

    @PostMapping("/process")
    public void processAllMessages() {
        messageService.processAllUnprocessedMessages();
    }
}
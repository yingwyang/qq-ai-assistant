package com.qqai.event;

import com.qqai.entity.Message;
import org.springframework.context.ApplicationEvent;

public class MessageSavedEvent extends ApplicationEvent {
    private final Message message;

    public MessageSavedEvent(Message message) {
        super(message);
        this.message = message;
    }

    public Message getMessage() {
        return message;
    }
}

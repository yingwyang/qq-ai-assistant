package com.qqai.consumer;

import com.qqai.config.RabbitMQConfig;
import com.qqai.dto.webhook.BroadcastPayload;
import com.qqai.websocket.FrontendMessageWebSocketHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * 广播消费者
 *
 * 消费 {@link RabbitMQConfig#BROADCAST_QUEUE}，将消息推送到前端 WebSocket。
 *
 * 设计要点：
 *  - 广播是"尽力而为"操作，失败不应阻塞业务流程，因此不设重试/DLQ
 *  - 所有异常在消费者内部捕获并记录，不抛出（避免触发 RetryTemplate）
 *  - 使用 aiContainerFactory（含 jacksonMessageConverter，prefetch=3）
 */
@Component
public class BroadcastConsumer {

    private static final Logger log = LoggerFactory.getLogger(BroadcastConsumer.class);

    @Autowired
    private FrontendMessageWebSocketHandler frontendMessageWebSocketHandler;

    @RabbitListener(queues = RabbitMQConfig.BROADCAST_QUEUE, containerFactory = "aiContainerFactory")
    public void consumeBroadcast(BroadcastPayload payload) {
        try {
            frontendMessageWebSocketHandler.broadcastToGroup(
                    payload.getGroupId(), payload.getJsonPayload());
            log.debug("广播已推送: groupId={}", payload.getGroupId());
        } catch (Exception e) {
            // 广播失败不重试（WebSocket 客户端可能已断开），仅记录日志
            log.warn("广播推送失败: groupId={}, error={}", payload.getGroupId(), e.getMessage());
        }
    }
}

package com.qqai.service;

import com.qqai.config.RabbitMQConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Service;

/**
 * RabbitMQ 消息队列服务，封装生产端发送与同步接收。
 *
 * 业务侧优先使用语义化便捷方法（sendMediaDownload / sendVoiceTranscode / sendAiAnalysis / sendBroadcast），
 * 通用 send / sendToFanout 留作扩展。
 */
@Service
public class MessageQueueService {

    private static final Logger log = LoggerFactory.getLogger(MessageQueueService.class);

    private final RabbitTemplate rabbitTemplate;

    public MessageQueueService(RabbitTemplate rabbitTemplate) {
        this.rabbitTemplate = rabbitTemplate;
    }

    // ==================== 通用发送 ====================

    /**
     * 发送消息到指定 Direct Exchange + routing key。
     */
    public void send(String exchange, String routingKey, Object payload) {
        rabbitTemplate.convertAndSend(exchange, routingKey, payload);
        log.debug("MQ send → exchange={}, routingKey={}, payload={}", exchange, routingKey, payload);
    }

    /**
     * 广播消息到指定 Fanout Exchange（忽略 routing key）。
     */
    public void sendToFanout(String exchange, Object payload) {
        rabbitTemplate.convertAndSend(exchange, "", payload);
        log.debug("MQ broadcast → exchange={}, payload={}", exchange, payload);
    }

    /**
     * 同步接收并转换为指定类型（一般仅用于调试或补偿任务，业务消费者请用 @RabbitListener）。
     */
    public <T> T receive(String queueName, Class<T> type) {
        Object message = rabbitTemplate.receiveAndConvert(queueName);
        if (message == null) {
            return null;
        }
        return type.isInstance(message) ? type.cast(message) : null;
    }

    // ==================== 语义化便捷方法 ====================

    /** 投递媒体下载任务 */
    public void sendMediaDownload(Object payload) {
        send(RabbitMQConfig.MEDIA_EXCHANGE, RabbitMQConfig.MEDIA_DOWNLOAD_KEY, payload);
    }

    /** 投递语音转码任务 */
    public void sendVoiceTranscode(Object payload) {
        send(RabbitMQConfig.VOICE_EXCHANGE, RabbitMQConfig.VOICE_TRANSCODE_KEY, payload);
    }

    /** 投递 AI 分析任务 */
    public void sendAiAnalysis(Object payload) {
        send(RabbitMQConfig.AI_EXCHANGE, RabbitMQConfig.AI_ANALYSIS_KEY, payload);
    }

    /**
     * 投递群日报任务（独立队列 group.digest.queue，避免与单条摘要互相阻塞）。
     * 载荷约定为 {@link com.qqai.dto.webhook.GroupDigestPayload}。
     */
    public void sendGroupDigest(Object payload) {
        send(RabbitMQConfig.DIGEST_EXCHANGE, RabbitMQConfig.GROUP_DIGEST_KEY, payload);
    }

    /** 广播消息（新消息 / 媒体更新 / AI 更新等） */
    public void sendBroadcast(Object payload) {
        sendToFanout(RabbitMQConfig.BROADCAST_EXCHANGE, payload);
    }
}

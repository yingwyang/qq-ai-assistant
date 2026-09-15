package com.qqai.consumer;

import com.qqai.config.RabbitMQConfig;
import com.qqai.dto.webhook.MediaTaskPayload;
import com.qqai.entity.Message;
import com.qqai.repository.MessageRepository;
import com.qqai.service.MediaDownloadService;
import com.qqai.service.MessageBroadcastService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * 媒体下载消费者
 *
 * 消费 {@link RabbitMQConfig#MEDIA_DOWNLOAD_QUEUE}（图片/视频下载任务）：
 *  1. 调 {@link MediaDownloadService#downloadMediaToLocal} 下载到本地
 *  2. 成功 → 回填 message.content 为本地路径，清除 mediaPending，推送 message_update
 *  3. 失败 → 抛异常触发 RetryTemplate 重试 3 次，全部失败后 reject(requeue=false) → 进 DLQ
 *
 * DLQ 消费者（{@link RabbitMQConfig#MEDIA_DOWNLOAD_DLQ}）：
 *  - 标记 message.mediaPending=false，content 设为失败占位符，日志告警，推送 message_update
 */
@Component
public class MediaDownloadConsumer {

    private static final Logger log = LoggerFactory.getLogger(MediaDownloadConsumer.class);

    @Autowired
    private MediaDownloadService mediaDownloadService;

    @Autowired
    private MessageRepository messageRepository;

    @Autowired
    private MessageBroadcastService messageBroadcastService;

    /**
     * 消费媒体下载任务（图片/视频）。
     * 使用 mediaContainerFactory（prefetch=5，重试 3 次后进死信）。
     *
     * 注意：不加 @Transactional — downloadMediaToLocal 包含 HTTP 下载（可能耗时数十秒），
     * 在事务中会长时间持有数据库连接导致连接池耗尽。
     * findById 和 save 各自使用 JPA 内置事务即可。
     */
    @RabbitListener(queues = RabbitMQConfig.MEDIA_DOWNLOAD_QUEUE, containerFactory = "mediaContainerFactory")
    public void consumeMediaDownload(MediaTaskPayload payload) {
        log.info("消费媒体下载任务: {}", payload);

        Optional<Message> messageOpt = messageRepository.findById(payload.getMessageId());
        if (messageOpt.isEmpty()) {
            log.warn("消息不存在（可能已删除），跳过下载: messageId={}", payload.getMessageId());
            return;
        }

        // 幂等性检查：若媒体已处理完成（mediaPending=false），跳过避免重复下载
        Message message = messageOpt.get();
        if (!message.isMediaPending()) {
            log.info("消息媒体已处理完成，跳过重复下载: messageId={}", payload.getMessageId());
            return;
        }

        String localUrl = mediaDownloadService.downloadMediaToLocal(
                payload.getRawMessage(),
                payload.getGroupId(),
                payload.getMediaType(),
                payload.getExtension(),
                payload.getFileId(),
                payload.isTrustedSource());

        if (localUrl == null) {
            // 下载失败：抛异常触发重试，3 次后进 DLQ
            throw new RuntimeException("媒体下载失败: " + payload);
        }

        // 下载成功：回填 content + 清除 mediaPending（JPA save 自带事务）
        message.setContent(localUrl);
        message.setMediaPending(false);
        Message updated = messageRepository.save(message);
        messageBroadcastService.broadcastMessageUpdate(updated);
        log.info("媒体下载完成并回填: messageId={}, localUrl={}", payload.getMessageId(), localUrl);
    }

    /**
     * 死信队列消费者：媒体下载彻底失败后的兜底处理。
     * 标记 mediaPending=false + content 设为失败占位符，推送 message_update。
     * 不抛异常（避免 DLQ 消息再次被拒绝导致丢失）。
     */
    @RabbitListener(queues = RabbitMQConfig.MEDIA_DOWNLOAD_DLQ, containerFactory = "mediaContainerFactory")
    public void consumeMediaDownloadDlq(MediaTaskPayload payload) {
        log.error("【DLQ】媒体下载彻底失败（已重试 3 次）: {}", payload);

        Optional<Message> messageOpt = messageRepository.findById(payload.getMessageId());
        if (messageOpt.isEmpty()) {
            log.warn("【DLQ】消息不存在，无法回填失败状态: messageId={}", payload.getMessageId());
            return;
        }

        Message message = messageOpt.get();
        message.setMediaPending(false);
        // 根据媒体类型设置失败占位符
        String placeholder = resolveFailurePlaceholder(payload.getMediaType());
        message.setContent(placeholder);
        Message updated = messageRepository.save(message);
        messageBroadcastService.broadcastMessageUpdate(updated);
        log.warn("【DLQ】已标记消息为下载失败: messageId={}, placeholder={}", payload.getMessageId(), placeholder);
    }

    private String resolveFailurePlaceholder(String mediaType) {
        if ("images".equals(mediaType)) {
            return "[图片已过期]";
        }
        if ("video".equals(mediaType)) {
            return "[视频已过期]";
        }
        return "[媒体已过期]";
    }
}

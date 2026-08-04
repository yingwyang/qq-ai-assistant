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
 * 语音转码消费者
 *
 * 消费 {@link RabbitMQConfig#VOICE_TRANSCODE_QUEUE}（语音下载 + SILK/AMR → MP3 转码）：
 *  1. 调 {@link MediaDownloadService#downloadMediaToLocal}（mediaType="voice" 时内部自动触发 convertVoiceToMp3）
 *  2. 成功 → 回填 message.content 为本地 MP3 路径，清除 mediaPending，推送 message_update
 *  3. 失败 → 抛异常触发 RetryTemplate 重试 3 次，全部失败后 reject(requeue=false) → 进 DLQ
 *
 * 使用 voiceContainerFactory（prefetch=2，限制 CPU 密集型转码并发）。
 *
 * DLQ 消费者（{@link RabbitMQConfig#VOICE_TRANSCODE_DLQ}）：
 *  - 标记 message.mediaPending=false，content 设为 "[语音转码失败]"，日志告警，推送 message_update
 */
@Component
public class VoiceTranscodeConsumer {

    private static final Logger log = LoggerFactory.getLogger(VoiceTranscodeConsumer.class);

    private static final String VOICE_FAILURE_PLACEHOLDER = "[语音转码失败]";

    @Autowired
    private MediaDownloadService mediaDownloadService;

    @Autowired
    private MessageRepository messageRepository;

    @Autowired
    private MessageBroadcastService messageBroadcastService;

    /**
     * 消费语音转码任务。
     * 使用 voiceContainerFactory（prefetch=2，CPU 密集限制并发，重试 3 次后进死信）。
     *
     * 注意：不加 @Transactional — downloadMediaToLocal 包含 HTTP 下载 + ffmpeg 转码（可能耗时数十秒），
     * 在事务中会长时间持有数据库连接导致连接池耗尽。
     */
    @RabbitListener(queues = RabbitMQConfig.VOICE_TRANSCODE_QUEUE, containerFactory = "voiceContainerFactory")
    public void consumeVoiceTranscode(MediaTaskPayload payload) {
        log.info("消费语音转码任务: {}", payload);

        Optional<Message> messageOpt = messageRepository.findById(payload.getMessageId());
        if (messageOpt.isEmpty()) {
            log.warn("消息不存在（可能已删除），跳过转码: messageId={}", payload.getMessageId());
            return;
        }

        // 幂等性检查：若语音已处理完成（mediaPending=false），跳过避免重复转码
        Message message = messageOpt.get();
        if (!message.isMediaPending()) {
            log.info("消息语音已处理完成，跳过重复转码: messageId={}", payload.getMessageId());
            return;
        }

        // downloadMediaToLocal 在 mediaType="voice" 时会自动调用 convertVoiceToMp3
        // （ffmpeg 处理 AMR，失败时回退到 Python pysilk 处理 SILK）
        String localUrl = mediaDownloadService.downloadMediaToLocal(
                payload.getRawMessage(),
                payload.getGroupId(),
                payload.getMediaType(),
                payload.getExtension());

        if (localUrl == null) {
            // 下载/转码失败：抛异常触发重试，3 次后进 DLQ
            throw new RuntimeException("语音下载/转码失败: " + payload);
        }

        // 转码成功：回填 content + 清除 mediaPending（JPA save 自带事务）
        message.setContent(localUrl);
        message.setMediaPending(false);
        Message updated = messageRepository.save(message);
        messageBroadcastService.broadcastMessageUpdate(updated);
        log.info("语音转码完成并回填: messageId={}, localUrl={}", payload.getMessageId(), localUrl);
    }

    /**
     * 死信队列消费者：语音转码彻底失败后的兜底处理。
     * 标记 mediaPending=false + content 设为 "[语音转码失败]"，推送 message_update。
     * 不抛异常（避免 DLQ 消息再次被拒绝导致丢失）。
     */
    @RabbitListener(queues = RabbitMQConfig.VOICE_TRANSCODE_DLQ, containerFactory = "voiceContainerFactory")
    public void consumeVoiceTranscodeDlq(MediaTaskPayload payload) {
        log.error("【DLQ】语音转码彻底失败（已重试 3 次）: {}", payload);

        Optional<Message> messageOpt = messageRepository.findById(payload.getMessageId());
        if (messageOpt.isEmpty()) {
            log.warn("【DLQ】消息不存在，无法回填失败状态: messageId={}", payload.getMessageId());
            return;
        }

        Message message = messageOpt.get();
        message.setMediaPending(false);
        message.setContent(VOICE_FAILURE_PLACEHOLDER);
        Message updated = messageRepository.save(message);
        messageBroadcastService.broadcastMessageUpdate(updated);
        log.warn("【DLQ】已标记消息为语音转码失败: messageId={}, placeholder={}",
                payload.getMessageId(), VOICE_FAILURE_PLACEHOLDER);
    }
}

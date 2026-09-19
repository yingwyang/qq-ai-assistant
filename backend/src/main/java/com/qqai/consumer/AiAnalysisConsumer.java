package com.qqai.consumer;

import com.qqai.config.RabbitMQConfig;
import com.qqai.dto.webhook.AiAnalysisPayload;
import com.qqai.entity.Message;
import com.qqai.entity.Group;
import com.qqai.repository.GroupRepository;
import com.qqai.repository.MessageRepository;
import com.qqai.service.AstrBotService;
import com.qqai.service.MessageBroadcastService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

/**
 * AI 分析消费者
 *
 * 消费 {@link RabbitMQConfig#AI_ANALYSIS_QUEUE}（消息摘要生成任务）：
 *  1. 调 {@link AstrBotService#summarizeMessage(String)} 生成 AI 摘要
 *  2. 成功 → 回填 message.aiSummary + processed=true + broadcastMessageUpdate
 *  3. 失败 → 抛异常触发 RetryTemplate 重试 3 次，全部失败后 reject → 进 DLQ
 *
 * 使用 aiContainerFactory（prefetch=3，I/O 密集适度并发）。
 *
 * DLQ 消费者（{@link RabbitMQConfig#AI_ANALYSIS_DLQ}）：
 *  - 仅日志告警，不修改消息（AI 摘要缺失不影响消息可读性，message.processed 保持 false）
 */
@Component
public class AiAnalysisConsumer {

    private static final Logger log = LoggerFactory.getLogger(AiAnalysisConsumer.class);

    /** 结构化摘要解析（与按需摘要接口共用） */
    @Autowired
    private com.qqai.service.AiSummaryParser aiSummaryParser;

    @Autowired
    private AstrBotService astrBotService;

    /** 「人层」：按触发者选定的人格挑配置档案 */
    @Autowired
    private com.qqai.service.AstrBotPersonaService astrBotPersonaService;

    @Autowired
    private MessageRepository messageRepository;

    @Autowired
    private GroupRepository groupRepository;

    @Autowired
    private MessageBroadcastService messageBroadcastService;

    /**
     * 消费 AI 分析任务。
     * 使用 aiContainerFactory（prefetch=3，重试 3 次后进死信）。
     *
     * 注意：不加 @Transactional — summarizeMessage 包含远程 HTTP 调用（可能耗时数十秒），
     * 在事务中会长时间持有数据库连接导致连接池耗尽。
     */
    @RabbitListener(queues = RabbitMQConfig.AI_ANALYSIS_QUEUE, containerFactory = "aiContainerFactory")
    public void consumeAiAnalysis(AiAnalysisPayload payload) {
        log.info("消费AI分析任务: {}", payload);

        Optional<Message> messageOpt = messageRepository.findById(payload.getMessageId());
        if (messageOpt.isEmpty()) {
            log.warn("消息不存在（可能已删除），跳过AI分析: messageId={}", payload.getMessageId());
            return;
        }

        Message message = messageOpt.get();

        // 已处理过的消息不重复分析（防止 processAllUnprocessedMessages 重投递）
        if (message.isProcessed()) {
            log.info("消息已处理，跳过AI分析: messageId={}", payload.getMessageId());
            return;
        }

        // 从消息关联的群聊获取 groupType，注入摘要上下文
        String groupType = resolveGroupType(message.getGroupId());

        String renderedForSummary = astrBotService instanceof Object
                ? astrBotService.renderMessageForSummary(message)
                : payload.getContent();
        if (renderedForSummary == null || renderedForSummary.trim().isEmpty()) {
            message.setAiSummary("");
            message.setProcessed(true);
            Message updated = messageRepository.save(message);
            messageBroadcastService.broadcastMessageUpdate(updated);
            log.info("消息为空内容，跳过AI摘要 messageId={}", payload.getMessageId());
            return;
        }
        String summary;
        try {
            // 带图消息同样送图 + 切视觉配置文件（与 /messages/{id}/summarize 同一口径）
            java.util.List<String> imageUrls = astrBotService.renderMessageImageUrls(message);
            // 「人层」：用触发批量任务的用户所选人格；没有上下文时回退内置默认档案
            String personaConfig = astrBotPersonaService.resolveConfigName(
                    payload.getRequestedBy(), !imageUrls.isEmpty());
            summary = astrBotService.summarizeMessageStructured(
                    renderedForSummary, null, groupType, imageUrls, personaConfig);
        } catch (Exception e) {
            throw new RuntimeException("AI分析失败: " + payload, e);
        }
        if (summary == null || summary.isBlank()) {
            throw new RuntimeException("AI分析返回空摘要: " + payload);
        }

        // 分析成功：回填 aiSummary（原始输出）+ 结构化字段 + processed=true
        message.setAiSummary(summary);
        aiSummaryParser.applyStructured(message, summary);
        message.setProcessed(true);
        Message updated = messageRepository.save(message);
        messageBroadcastService.broadcastMessageUpdate(updated);
        log.info("AI分析完成: messageId={}, summaryLen={}, tags={}, sentiment={}",
                payload.getMessageId(), summary.length(), message.getAiTags(), message.getAiSentiment());
    }


    /**
     * 死信队列消费者：AI 分析彻底失败后的兜底处理。
     * 仅记录日志，不修改消息（processed 保持 false，aiSummary 为空）。
     * 不抛异常（避免 DLQ 消息再次被拒绝导致丢失）。
     */
    @RabbitListener(queues = RabbitMQConfig.AI_ANALYSIS_DLQ, containerFactory = "aiContainerFactory")
    public void consumeAiAnalysisDlq(AiAnalysisPayload payload) {
        log.error("【DLQ】AI分析彻底失败（已重试 3 次）: {} — 消息将无AI摘要", payload);
    }

    /**
     * 从消息关联的群聊解析 groupType
     */
    private String resolveGroupType(String groupId) {
        if (groupId == null || groupId.isBlank()) return null;
        try {
            List<Group> groups = groupRepository.safeFindByGroupId(groupId);
            if (!groups.isEmpty()) {
                String gt = groups.get(0).getGroupType();
                return (gt != null && !gt.isBlank()) ? gt : null;
            }
        } catch (Exception e) {
            log.debug("解析群类型失败 groupId={}: {}", groupId, e.getMessage());
        }
        return null;
    }
}

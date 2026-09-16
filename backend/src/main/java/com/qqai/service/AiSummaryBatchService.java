package com.qqai.service;

import com.qqai.exception.BizException;
import com.qqai.common.SecurityHelper;
import com.qqai.config.RabbitMQConfig;
import com.qqai.dto.webhook.AiAnalysisPayload;
import com.qqai.entity.Message;
import com.qqai.repository.MessageRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.core.QueueInformation;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * AI 摘要阶段 2：批量补摘要的投递、进度与止损。
 *
 * <p>设计约束（见 doc：ai-summary-design.md）：
 * <ul>
 *   <li>必须带 {@code groupId} 或 {@code start}，杜绝"一键 2200 条"；</li>
 *   <li>{@code limit} 硬上限 500（管理员 2000）；</li>
 *   <li>全站每日额度 {@code ai.summary.daily-limit}，超限直接拒绝；</li>
 *   <li>已有任务进行中（队列非空）返回 409；</li>
 *   <li>停止 = 清空 {@code ai.analysis.queue}（<b>保留 DLQ</b>），并写审计。</li>
 * </ul>
 */
@Service
public class AiSummaryBatchService {

    private static final Logger log = LoggerFactory.getLogger(AiSummaryBatchService.class);

    /** 每条消息的估算 token（设计稿口径：1.5k） */
    private static final int ESTIMATED_TOKENS_PER_MSG = 1500;
    /** 每条消息的估算耗时（秒），用于给出"约 Y 分钟" */
    private static final int ESTIMATED_SECONDS_PER_MSG = 8;

    @Value("${ai.summary.enabled:true}")
    private boolean enabled;

    @Value("${ai.summary.daily-limit:300}")
    private int dailyLimit;

    @Value("${ai.summary.min-length:8}")
    private int minLength;

    @Autowired
    private MessageRepository messageRepository;

    @Autowired
    private MessageQueueService messageQueueService;

    @Autowired
    private AuditLogService auditLogService;

    @Autowired
    private SecurityHelper securityHelper;

    @Autowired
    private AmqpAdmin amqpAdmin;

    /** 是否有任务处于"进行中" */
    private final AtomicBoolean running = new AtomicBoolean(false);

    /** 本批投递条数（用于判断"是否跑完"） */
    private final java.util.concurrent.atomic.AtomicInteger batchSize = new java.util.concurrent.atomic.AtomicInteger(0);

    /** 本批开始时的"今日已完成"基数 */
    private final java.util.concurrent.atomic.AtomicLong doneAtStart = new java.util.concurrent.atomic.AtomicLong(0);

    /**
     * 本批是否仍在进行。
     *
     * <p>注意：<b>不能</b>用"队列深度 &gt; 0"判断。消费者 prefetch=3，投递后消息会立刻被取走变成
     * "已投递未确认"，队列深度随即变 0 —— 实测这会让并发保护失效（重复投递返回 200 而不是 409）。
     * 这里改用「今日已完成 − 本批开始基数 &lt; 本批投递条数」判断，跑完自然为 false。</p>
     */
    private boolean isBatchRunning() {
        if (batchSize.get() <= 0) return false;
        long progressed = doneToday() - doneAtStart.get();
        return progressed < batchSize.get();
    }

    /**
     * 投递批量摘要任务。
     * @param groupId 指定群（与 start 至少给一个）
     * @param start   起始时间（可空）
     * @param end     结束时间（可空）
     * @param limit   本次最多投递条数
     * @param minLengthOverride 最短内容长度（空则用配置值）
     * @param onlyText 是否只处理纯文本（跳过媒体占位符）
     */
    public Map<String, Object> start(String groupId, LocalDateTime start, LocalDateTime end,
                                     Integer limit, Integer minLengthOverride, Boolean onlyText) {
        if (!enabled) {
            throw new BizException(403, "AI 摘要功能已关闭（ai.summary.enabled=false）");
        }
        boolean hasGroup = groupId != null && !groupId.isBlank();
        if (!hasGroup && start == null) {
            throw new BizException(400, "必须指定 groupId 或 start（避免一次性投递全部历史消息）");
        }

        boolean admin = securityHelper.isAdmin();
        int hardCap = admin ? 2000 : 500;
        int want = limit == null || limit <= 0 ? 100 : Math.min(limit, hardCap);

        // 并发保护：本批还没跑完（或队列里还有积压）就拒绝重复投递
        if (isBatchRunning()) {
            long progressed = doneToday() - doneAtStart.get();
            throw new BizException(409, "已有摘要任务进行中（本批 " + batchSize.get() + " 条，已完成 " + progressed
                    + " 条），请先等待或点击停止");
        }
        long queueDepth = queueDepth();
        if (queueDepth > 0) {
            throw new BizException(409, "摘要队列还有 " + queueDepth + " 条积压，请先等待或点击停止");
        }

        // 每日额度：今天已生成的摘要条数 + 本次投递量不得超过上限
        long doneToday = doneToday();
        if (dailyLimit > 0 && doneToday >= dailyLimit) {
            throw new BizException(429, "今日摘要额度已用完（" + doneToday + "/" + dailyLimit + "），请明天再试");
        }
        int remainingQuota = dailyLimit > 0 ? (int) Math.max(0, dailyLimit - doneToday) : Integer.MAX_VALUE;

        int minLen = minLengthOverride == null || minLengthOverride <= 0 ? minLength : minLengthOverride;
        boolean textOnly = onlyText == null || onlyText;

        // 候选集：未处理且未删除的消息（数据量可控，过滤放在内存里做，避免复杂 JPQL 的 null 参数问题）
        List<Message> candidates = messageRepository.findByProcessedFalseAndDeletedFalse();
        List<Message> selected = new ArrayList<>();
        int skipped = 0;
        for (Message m : candidates) {
            if (selected.size() >= Math.min(want, remainingQuota)) break;
            if (hasGroup && !groupId.equals(m.getGroupId())) { skipped++; continue; }
            if (start != null && (m.getSendTime() == null || m.getSendTime().isBefore(start))) { skipped++; continue; }
            if (end != null && (m.getSendTime() == null || m.getSendTime().isAfter(end))) { skipped++; continue; }
            String c = m.getContent() == null ? "" : m.getContent().trim();
            if (c.length() < minLen) { skipped++; continue; }
            if (textOnly && (c.startsWith("[") || Message.MessageType.TEXT != m.getMessageType())) { skipped++; continue; }
            selected.add(m);
        }

        for (Message m : selected) {
            messageQueueService.sendAiAnalysis(new AiAnalysisPayload(m.getId(), m.getContent()));
        }
        doneAtStart.set(doneToday());   // 记录本批基数
        batchSize.set(selected.size());
        running.set(!selected.isEmpty());

        int queued = selected.size();
        int estimatedTokens = queued * ESTIMATED_TOKENS_PER_MSG;
        int estimatedSeconds = queued * ESTIMATED_SECONDS_PER_MSG;

        String detail = String.format("groupId=%s, start=%s, end=%s, limit=%d, minLength=%d, onlyText=%s → 投递 %d 条，跳过 %d 条",
                groupId, start, end, want, minLen, textOnly, queued, skipped);
        auditLogService.log(currentUsername(), "AI_SUMMARY_BATCH", "messages", "SUCCESS", detail);

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("status", "ok");
        data.put("queued", queued);
        data.put("skipped", skipped);
        data.put("estimatedTokens", estimatedTokens);
        data.put("estimatedMinutes", Math.max(1, estimatedSeconds / 60));
        data.put("remainingQuota", dailyLimit > 0 ? Math.max(0, dailyLimit - doneToday - queued) : -1);
        return data;
    }

    /** 进度：队列深度 / 今日已完成 / 剩余待处理 / 是否进行中 / 配置 */
    public Map<String, Object> status() {
        long queueDepth = queueDepth();
        long doneToday = doneToday();
        long pending = messageRepository.countByProcessedFalseAndDeletedFalse();
        boolean isRunning = isBatchRunning();
        if (!isRunning) {
            running.set(false);
        }
        long batchDone = batchSize.get() <= 0 ? 0 : Math.max(0, doneToday - doneAtStart.get());
        Map<String, Object> cfg = new LinkedHashMap<>();
        cfg.put("enabled", enabled);
        cfg.put("dailyLimit", dailyLimit);
        cfg.put("minLength", minLength);

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("queueDepth", queueDepth);
        // 在途条数：本批投递数 - 已完成数（比"队列深度"更能反映真实进度，prefetch 会提前取走消息）
        data.put("processing", isRunning ? Math.max(0, batchSize.get() - batchDone) : 0);
        data.put("batchSize", batchSize.get());
        data.put("batchDone", batchDone);
        data.put("doneToday", doneToday);
        data.put("totalPending", pending);
        data.put("running", isRunning);
        data.put("config", cfg);
        return data;
    }

    /** 停止：清空 ai.analysis.queue（保留 DLQ），写审计 */
    public Map<String, Object> stop() {
        long before = queueDepth();
        try {
            amqpAdmin.purgeQueue(RabbitMQConfig.AI_ANALYSIS_QUEUE, true);
        } catch (Exception e) {
            log.warn("清空 AI 分析队列失败: {}", e.getMessage());
            throw new BizException(500, "清空队列失败：" + e.getMessage());
        }
        running.set(false);
        batchSize.set(0);
        doneAtStart.set(0);
        auditLogService.log(currentUsername(), "AI_SUMMARY_BATCH_STOP", "ai.analysis.queue", "SUCCESS",
                "清空队列，丢弃 " + before + " 条待处理任务（DLQ 保留）");
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("status", "stopped");
        data.put("purged", before);
        return data;
    }

    /** 今日已成功生成摘要的条数（以 ai_summarized_at 为准） */
    public long doneToday() {
        LocalDateTime dayStart = LocalDate.now().atStartOfDay();
        return messageRepository.countByAiSummarizedAtGreaterThanEqual(dayStart);
    }

    /** 队列深度（走 Spring AMQP 的 AmqpAdmin，不依赖 RabbitMQ 管理插件） */
    public long queueDepth() {
        try {
            QueueInformation info = amqpAdmin.getQueueInfo(RabbitMQConfig.AI_ANALYSIS_QUEUE);
            if (info == null) return 0;
            return info.getMessageCount();
        } catch (Exception e) {
            log.debug("读取队列深度失败: {}", e.getMessage());
            return 0;
        }
    }

    /** 当天 00:00（供调用方构造 start 默认值） */
    public LocalDateTime todayStart() {
        return LocalDate.now().atStartOfDay();
    }

    /** 当天 23:59:59（供调用方构造 end 默认值） */
    public LocalDateTime todayEnd() {
        return LocalDate.now().atTime(LocalTime.MAX);
    }

    private String currentUsername() {
        try {
            return securityHelper.getCurrentUsername();
        } catch (Exception e) {
            return "system";
        }
    }
}

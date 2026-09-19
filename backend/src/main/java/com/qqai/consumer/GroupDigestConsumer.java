package com.qqai.consumer;

import com.qqai.config.RabbitMQConfig;
import com.qqai.dto.webhook.GroupDigestPayload;
import com.qqai.entity.GroupDigest;
import com.qqai.exception.BizException;
import com.qqai.service.GroupDigestService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;

/**
 * 群日报消费者（AI 摘要阶段 3）。
 *
 * <p>消费 {@link RabbitMQConfig#GROUP_DIGEST_QUEUE}（独立队列，prefetch=1 串行执行）：
 * 收到 {@link GroupDigestPayload}（{@code {groupId, date, force}}）后调用
 * {@link GroupDigestService#generateDailyDigest(String, LocalDate, boolean)}。</p>
 *
 * <p><b>失败语义</b>（与 {@code AiAnalysisConsumer} 一致）：</p>
 * <ul>
 *   <li><b>技术失败</b>（大模型不可用、数据库异常等）→ 抛 {@link RuntimeException}，
 *       由 RetryTemplate 重试 3 次（指数退避 1s→2s→4s），仍失败则 reject(requeue=false)
 *       → 死信路由到 {@link RabbitMQConfig#GROUP_DIGEST_DLQ}；</li>
 *   <li><b>业务失败</b>（{@link BizException}：400 该群当天没有可摘要的消息 / 403 功能已关闭或不在白名单）
 *       → 只记日志并正常确认，<b>不重试、不进 DLQ</b>。这类失败重试多少次结果都一样，
 *       若也重试会让「安静的群」每天固定往 DLQ 丢 3 条无意义消息。</li>
 * </ul>
 *
 * <p>DLQ 消费者只记日志、不抛异常（避免死信消息被再次拒绝导致丢失）。</p>
 *
 * <p>注意：不加 {@code @Transactional} —— 生成日报包含远程 HTTP 调用（可能数十秒），
 * 在事务中会长时间占用数据库连接。</p>
 */
@Component
public class GroupDigestConsumer {

    private static final Logger log = LoggerFactory.getLogger(GroupDigestConsumer.class);

    @Autowired
    private GroupDigestService groupDigestService;

    /**
     * 消费群日报任务（重试 3 次后进死信）。
     */
    @RabbitListener(queues = RabbitMQConfig.GROUP_DIGEST_QUEUE, containerFactory = "digestContainerFactory")
    public void consumeGroupDigest(GroupDigestPayload payload) {
        if (payload == null || payload.getGroupId() == null || payload.getGroupId().isBlank()) {
            log.warn("群日报任务载荷非法（缺少 groupId），丢弃: {}", payload);
            return;
        }
        String groupId = payload.getGroupId().trim();
        LocalDate date = parseDate(payload.getDate());
        boolean force = Boolean.TRUE.equals(payload.getForce());

        log.info("消费群日报任务 groupId={}, date={}, force={}", groupId, date, force);
        try {
            GroupDigest digest = groupDigestService.generateDailyDigest(
                    groupId, date, force, payload.getRequestedBy());
            log.info("群日报任务完成 groupId={}, date={}, messages={}, model={}",
                    groupId, date, digest.getMessageCount(), digest.getModel());
        } catch (BizException e) {
            // 400（当天没有可摘要的消息）/ 403（功能已关闭、群不在白名单）：重试无意义，直接确认
            log.warn("群日报任务业务性失败，不重试 groupId={}, date={}, code={}: {}",
                    groupId, date, e.getCode(), e.getMessage());
        } catch (Exception e) {
            // 技术失败：抛出 → 重试 3 次 → 仍失败进 DLQ
            throw new RuntimeException("群日报生成失败: groupId=" + groupId + ", date=" + date + ", force=" + force, e);
        }
    }

    /**
     * 死信队列消费者：群日报彻底失败后的兜底处理。
     * 仅记录日志，不写库、不抛异常（日报缺失不影响消息可读性，用户可手动重新生成）。
     */
    @RabbitListener(queues = RabbitMQConfig.GROUP_DIGEST_DLQ, containerFactory = "digestContainerFactory")
    public void consumeGroupDigestDlq(GroupDigestPayload payload) {
        log.error("【DLQ】群日报彻底失败（已重试 3 次）: {} — 该群当天将没有日报，可手动重试生成", payload);
    }

    /**
     * 解析 ISO-8601 日期字符串；空/非法按「今天」处理（与同步接口 {@code date == null} 的语义一致）。
     */
    private static LocalDate parseDate(String raw) {
        if (raw == null || raw.isBlank()) {
            return LocalDate.now();
        }
        try {
            return LocalDate.parse(raw.trim());
        } catch (DateTimeParseException e) {
            log.warn("群日报 date 非法（{}），按今天处理", raw);
            return LocalDate.now();
        }
    }
}

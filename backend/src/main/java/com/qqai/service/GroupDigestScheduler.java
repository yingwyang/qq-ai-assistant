package com.qqai.service;

import com.qqai.dto.webhook.GroupDigestPayload;
import com.qqai.entity.GroupDigest;
import com.qqai.repository.GroupDigestRepository;
import com.qqai.repository.MessageRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.SchedulingConfigurer;
import org.springframework.scheduling.config.CronTask;
import org.springframework.scheduling.config.ScheduledTask;
import org.springframework.scheduling.config.ScheduledTaskRegistrar;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 定时群日报调度器（AI 摘要阶段 3 收尾）。
 *
 * <p><b>为什么用 {@link SchedulingConfigurer} 而不是 {@code @Scheduled(cron = "${ai.summary.digest-cron:}")}</b>：
 * Spring 的 cron 表达式为空串会直接抛 {@code IllegalArgumentException: Cron expression must consist of
 * 6 fields}，导致应用启动失败。这里改成「运行时判断」：</p>
 * <ul>
 *   <li>启动时（{@code configureTasks}）从 {@link AiSummarySettingsService} 取 {@code digestCron}，
 *       <b>为空就一个任务都不注册</b>，应用照常启动；</li>
 *   <li>后台保存配置后收到 {@link AiSummarySettingsService.SettingsChangedEvent}，
 *       先取消旧任务再按新 cron 重新注册 —— 无需重启即生效；</li>
 *   <li>cron 非法（不是 6 段表达式）只记 error 日志并跳过注册，绝不让定时任务把应用带崩。</li>
 * </ul>
 *
 * <p>到点后的行为：</p>
 * <ol>
 *   <li>{@code enabled=false} → 直接跳过（记日志）；</li>
 *   <li>确定目标群：{@code digestGroups} 非空 → 只处理配置里列出的群；为空 → 处理「今天有消息的群」
 *       （{@link MessageRepository#findGroupIdsWithMessagesBetween}）；</li>
 *   <li>逐个投递到 {@code group.digest.queue}（独立队列，prefetch=1 串行消费），
 *       跳过群白名单外的群与「当天已有日报」的群；</li>
 *   <li>写审计日志（操作人固定 {@code system}）。</li>
 * </ol>
 *
 * <p>注意：本方法只做「查库 + 投递」，真正的生成在消费者里串行执行，
 * 不会长时间占用 Spring 的调度线程。</p>
 */
@Component
public class GroupDigestScheduler implements SchedulingConfigurer {

    private static final Logger log = LoggerFactory.getLogger(GroupDigestScheduler.class);

    /** 审计日志里的操作人（定时任务无登录上下文） */
    private static final String SYSTEM_USER = "system";
    private static final String AUDIT_ACTION = "AI_SUMMARY_DIGEST_SCHEDULE";
    private static final String AUDIT_TARGET = "group.digest.queue";

    @Autowired
    private AiSummarySettingsService aiSummarySettingsService;

    @Autowired
    private MessageRepository messageRepository;

    @Autowired
    private GroupDigestRepository groupDigestRepository;

    @Autowired
    private MessageQueueService messageQueueService;

    @Autowired
    private AuditLogService auditLogService;

    /** Spring 提供的任务注册器；configureTasks 时注入，之后用于运行时换 cron */
    private ScheduledTaskRegistrar taskRegistrar;

    /** 当前已注册的定时任务（cron 变更时先取消它） */
    private ScheduledTask currentTask;

    private final Object taskLock = new Object();

    /**
     * 启动时注册（由 Spring 的 ScheduledAnnotationBeanPostProcessor 调用）。
     * digestCron 为空 → 不注册任何任务，应用正常启动。
     */
    @Override
    public void configureTasks(ScheduledTaskRegistrar taskRegistrar) {
        this.taskRegistrar = taskRegistrar;
        registerCronTask();
    }

    /** 配置变更后重新注册：保存即生效（含「把 cron 清空 = 停掉定时任务」） */
    @EventListener(AiSummarySettingsService.SettingsChangedEvent.class)
    public void onSettingsChanged(AiSummarySettingsService.SettingsChangedEvent event) {
        registerCronTask();
    }

    /**
     * 按当前配置注册 cron 任务；已注册的旧任务先取消。
     * cron 为空 → 只取消不注册；cron 非法 → 记 error 并跳过（不影响应用运行）。
     */
    private void registerCronTask() {
        synchronized (taskLock) {
            if (currentTask != null) {
                currentTask.cancel();
                currentTask = null;
            }
            if (taskRegistrar == null) {
                log.warn("定时群日报注册失败：任务注册器尚未就绪，跳过本次注册");
                return;
            }
            String cron = aiSummarySettingsService.getDigestCron();
            if (cron == null || cron.isBlank()) {
                log.info("定时群日报未启用（ai.summary.digest-cron 为空）");
                return;
            }
            if (!CronExpression.isValidExpression(cron)) {
                log.error("定时群日报 cron 表达式非法，已跳过注册: [{}]（需 6 段含秒，如 0 50 23 * * ?）", cron);
                return;
            }
            try {
                currentTask = taskRegistrar.scheduleCronTask(new CronTask(this::runScheduledDigest, cron));
                log.info("定时群日报已注册: cron=[{}]", cron);
            } catch (Exception e) {
                currentTask = null;
                log.error("定时群日报注册失败（cron=[{}]）: {}", cron, e.getMessage(), e);
            }
        }
    }

    /**
     * 到点执行：逐一投递日报任务到 {@code group.digest.queue}。
     *
     * <p>public 便于排查问题时手动触发（也可以直接被外部调用）。</p>
     */
    public void runScheduledDigest() {
        LocalDate today = LocalDate.now();
        try {
            if (!aiSummarySettingsService.isEnabled()) {
                log.info("AI 摘要总开关已关闭（ai.summary.enabled=false），跳过定时群日报 date={}", today);
                return;
            }

            List<String> targets = resolveTargetGroups(today);
            if (targets.isEmpty()) {
                log.info("定时群日报：没有需要处理的群 date={}（digestGroups 为空且今天没有消息）", today);
                auditLogService.log(SYSTEM_USER, AUDIT_ACTION, AUDIT_TARGET, "SUCCESS",
                        "date=" + today + " → 没有需要处理的群");
                return;
            }

            // 当天已有日报的群：跳过，避免重复调用大模型
            Set<String> alreadyDone = digestsOfDay(today);
            int queued = 0;
            int skipped = 0;
            int denied = 0;
            for (String rawGroupId : targets) {
                if (rawGroupId == null || rawGroupId.isBlank()) {
                    continue;
                }
                String groupId = rawGroupId.trim();
                if (!aiSummarySettingsService.isGroupAllowed(groupId)) {
                    denied++;   // 白名单外的群不投递（否则消费者会因 403 反复重试）
                    continue;
                }
                if (alreadyDone.contains(groupId)) {
                    skipped++;
                    continue;
                }
                messageQueueService.sendGroupDigest(new GroupDigestPayload(groupId, today.toString(), false));
                queued++;
            }

            String detail = String.format("date=%s, 目标群=%d, 投递=%d, 跳过(当天已有日报)=%d, 跳过(不在白名单)=%d",
                    today, targets.size(), queued, skipped, denied);
            log.info("定时群日报投递完成: {}", detail);
            auditLogService.log(SYSTEM_USER, AUDIT_ACTION, AUDIT_TARGET, "SUCCESS", detail);
        } catch (Exception e) {
            log.error("定时群日报执行失败 date={}: {}", today, e.getMessage(), e);
            auditLogService.log(SYSTEM_USER, AUDIT_ACTION, AUDIT_TARGET, "FAILURE",
                    "date=" + today + " 异常: " + e.getMessage());
        }
    }

    /**
     * 确定本次要生成日报的群：
     * {@code digestGroups} 非空 → 只处理配置里列出的群；为空 → 处理「今天有消息的群」。
     */
    public List<String> resolveTargetGroups(LocalDate date) {
        List<String> configured = aiSummarySettingsService.digestGroupList();
        if (!configured.isEmpty()) {
            return configured;
        }
        LocalDateTime start = date.atStartOfDay();
        LocalDateTime end = start.plusDays(1);
        List<String> groups = messageRepository.findGroupIdsWithMessagesBetween(start, end);
        return groups == null ? List.of() : groups;
    }

    /** 当天已生成日报的群号集合（一次查完，避免每群一次 count 查询） */
    private Set<String> digestsOfDay(LocalDate date) {
        Set<String> done = new HashSet<>();
        try {
            List<GroupDigest> list = groupDigestRepository.findByDigestDate(date);
            if (list != null) {
                for (GroupDigest d : list) {
                    if (d != null && d.getGroupId() != null) {
                        done.add(d.getGroupId());
                    }
                }
            }
        } catch (Exception e) {
            log.warn("查询当天已有日报失败（本次不跳过任何群）: {}", e.getMessage());
        }
        return done;
    }
}

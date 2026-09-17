package com.qqai.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.qqai.entity.GroupDigest;
import com.qqai.entity.Message;
import com.qqai.exception.BizException;
import com.qqai.repository.GroupDigestRepository;
import com.qqai.repository.MessageRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 群日报服务（AI 摘要阶段 3）。
 *
 * <p>一次调用覆盖该群当天几百条消息，成本远低于逐条摘要：</p>
 * <ol>
 *   <li>取当天消息（未删除 + TEXT + 内容长度 ≥ {@value #MIN_CONTENT_LENGTH}，按发送时间升序）；
 *       长度下限运行时由 {@link AiSummarySettingsService#getMinLength()} 覆盖；</li>
 *   <li>逐条截断到 {@value #PER_MESSAGE_MAX_CHARS} 字符后拼接，总长超
 *       {@link AiSummarySettingsService#getMaxInputChars()}（默认 {@value #MAX_INPUT_CHARS}）字符时
 *       「保留前半 + 中间省略 + 保留后半」；</li>
 *   <li>调用 {@link AstrBotService#chat(String, String, String)}（tag = {@value #MODEL_TAG}），
 *       提示词要求模型只输出 JSON；</li>
 *   <li>解析 JSON（剥离 ```json 围栏；失败则把纯文本当 summary，不抛异常），写入 {@code group_digest}。</li>
 * </ol>
 *
 * <p><b>开关与白名单</b>：生成与读取都先过 {@link #assertDigestAllowed(String)}
 * —— {@code ai.summary.enabled=false} → 403「AI 摘要功能已关闭」；
 * 群白名单非空且群不在名单内 → 403「该群未开启 AI 摘要」。</p>
 *
 * <p><b>幂等</b>：同群同天已有记录时直接返回已有记录，不调用大模型（表上有
 * {@code UNIQUE(group_id, digest_date)} 兜底）。</p>
 */
@Service
public class GroupDigestService {

    private static final Logger log = LoggerFactory.getLogger(GroupDigestService.class);

    /** 调用大模型时使用的日志标记（与 prompts.yml 的模板 key 无关，仅用于区分来源） */
    public static final String MODEL_TAG = "group.digest";

    /** 参与日报的最短内容长度（默认值；运行时以 {@link AiSummarySettingsService#getMinLength()} 为准） */
    public static final int MIN_CONTENT_LENGTH = 8;

    /** 单条消息截断长度 */
    public static final int PER_MESSAGE_MAX_CHARS = 200;

    /** 拼接后的输入总长上限（默认值；运行时以 {@link AiSummarySettingsService#getMaxInputChars()} 为准） */
    public static final int MAX_INPUT_CHARS = 12000;

    /** 超长时两端各保留的字符数（默认 6000 + 6000 = 12000） */
    public static final int KEEP_CHARS_PER_SIDE = 6000;

    /** 历史列表单次返回上限（防止一次拉全表） */
    public static final int MAX_HISTORY_LIMIT = 200;

    private static final int TAGS_MAX = 255;
    private static final int SENTIMENT_MAX = 16;
    private static final int SUMMARY_MAX = 5000;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Autowired
    private MessageRepository messageRepository;

    @Autowired
    private GroupDigestRepository groupDigestRepository;

    @Autowired
    private AstrBotService astrBotService;

    /**
     * 运行时配置（enabled / 群白名单 / minLength / maxInputChars）。
     * 允许为空：单元测试直接 {@code new GroupDigestService()} 时回退到本类常量默认值。
     */
    @Autowired(required = false)
    private AiSummarySettingsService aiSummarySettingsService;

    /**
     * 生成日报时实际使用的模型名。
     * 与 {@code AstrBotService} 读同一个配置项，保证 {@code group_digest.model} 记录的就是真实调用模型。
     */
    @Value("${astrbot.summary-model:}")
    private String summaryModel;

    // ==================== 对外能力 ====================

    /**
     * 生成（或读取）某群某天的日报。
     *
     * @param groupId 群号
     * @param date    归属日期，为空表示今天
     * @return 已存在或新生成的日报记录
     * @throws BizException 400 当天没有可摘要的消息；403 功能已关闭 / 群不在白名单；503 大模型不可用
     */
    public GroupDigest generateDailyDigest(String groupId, LocalDate date) {
        return generateDailyDigest(groupId, date, false);
    }

    /**
     * 生成（或读取）某群某天的日报。
     *
     * @param groupId 群号
     * @param date    归属日期，为空表示今天
     * @param force   true = 即使当天已有日报也重新调用大模型生成（就地覆盖同一条记录，不新增行）
     * @return 已存在或新生成的日报记录
     * @throws BizException 400 当天没有可摘要的消息；403 功能已关闭 / 群不在白名单；503 大模型不可用
     */
    public GroupDigest generateDailyDigest(String groupId, LocalDate date, boolean force) {
        if (groupId == null || groupId.isBlank()) {
            throw new BizException(400, "群号不能为空");
        }
        // 总开关 + 群白名单：不在白名单的群不允许生成日报（也不允许读取，见 assertDigestAllowed 的调用方）
        assertDigestAllowed(groupId);
        LocalDate target = (date == null) ? LocalDate.now() : date;

        // 幂等：同群同天已有日报且未要求重算 → 直接返回缓存，不调用大模型、不重复消耗额度
        Optional<GroupDigest> existing = groupDigestRepository.findByGroupIdAndDigestDate(groupId, target);
        if (existing.isPresent() && !force) {
            log.info("群日报已存在，返回缓存 groupId={}, date={}", groupId, target);
            return existing.get();
        }

        List<Message> candidates = loadDailyMessages(groupId, target);
        if (candidates.isEmpty()) {
            throw new BizException(400, "该群当天没有可摘要的消息");
        }

        String input = buildDigestInput(candidates);
        String raw;
        try {
            raw = astrBotService.chat(buildPrompt(groupId, target, input), null, MODEL_TAG);
        } catch (Exception e) {
            log.warn("群日报调用大模型失败 groupId={}, date={}: {}", groupId, target, e.getMessage());
            throw new BizException(503, "AI 服务暂不可用：" + e.getMessage());
        }
        if (raw == null || raw.isBlank()) {
            throw new BizException(503, "AI 服务返回为空，请稍后重试");
        }

        GroupDigest digest = existing.orElseGet(GroupDigest::new);   // force 重算时复用同一行（唯一键 group+date）
        digest.setGroupId(groupId);
        digest.setDigestDate(target);
        digest.setMessageCount(candidates.size());
        digest.setModel(resolveModelName());
        applyModelOutput(digest, raw);

        GroupDigest saved;
        try {
            saved = groupDigestRepository.save(digest);
        } catch (DataIntegrityViolationException e) {
            // 并发下另一个请求抢先写入了同一 (group_id, digest_date)：回读已有记录，保持幂等语义
            log.warn("群日报并发写入冲突，回读已有记录 groupId={}, date={}: {}", groupId, target, e.getMessage());
            saved = groupDigestRepository.findByGroupIdAndDigestDate(groupId, target)
                    .orElseThrow(() -> new BizException(500, "群日报保存失败"));
            return saved;
        }
        log.info("群日报生成完成 groupId={}, date={}, messages={}, model={}",
                groupId, target, saved.getMessageCount(), saved.getModel());
        return saved;
    }

    /** 最新一期日报（无则空）。调用前建议先过 {@link #assertDigestAllowed(String)} */
    public Optional<GroupDigest> findLatest(String groupId) {
        if (groupId == null || groupId.isBlank()) {
            return Optional.empty();
        }
        return groupDigestRepository.findFirstByGroupIdOrderByDigestDateDesc(groupId);
    }

    /** 历史日报列表（按日期倒序，limit 会被收敛到 1 ~ {@value #MAX_HISTORY_LIMIT}） */
    public List<GroupDigest> findHistory(String groupId, int limit) {
        if (groupId == null || groupId.isBlank()) {
            return Collections.emptyList();
        }
        int size = Math.min(Math.max(limit, 1), MAX_HISTORY_LIMIT);
        return groupDigestRepository.findByGroupIdOrderByDigestDateDesc(groupId, PageRequest.of(0, size));
    }

    /**
     * 组装给前端的视图。字段名与前端约定一致：
     * {@code id / groupId / digestDate / summary / tags / sentiment / messageCount / model / createdAt}。
     *
     * @return 无日报时返回 null（由接口层决定如何表达「暂无」）
     */
    public Map<String, Object> toView(GroupDigest digest) {
        if (digest == null) {
            return null;
        }
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("id", digest.getId());
        view.put("groupId", digest.getGroupId());
        view.put("digestDate", digest.getDigestDate() == null ? null : digest.getDigestDate().toString());
        view.put("summary", digest.getSummary());
        view.put("tags", digest.getTags());
        view.put("sentiment", digest.getSentiment());
        view.put("messageCount", digest.getMessageCount());
        view.put("model", digest.getModel());
        view.put("createdAt", digest.getCreatedAt() == null ? null : digest.getCreatedAt().toString());
        return view;
    }

    // ==================== 开关与白名单 ====================

    /**
     * 群日报统一开关校验：生成日报前必过，读取日报的接口（latest / digests）也调用它，
     * 保证「白名单外的群既不能生成也不能读取日报」。
     *
     * @throws BizException 403 {@code ai.summary.enabled=false} → 「AI 摘要功能已关闭」；
     *                      群白名单非空且该群不在名单内 → 「该群未开启 AI 摘要」
     */
    public void assertDigestAllowed(String groupId) {
        AiSummarySettingsService settings = aiSummarySettingsService;
        if (settings == null) {
            return;   // 单元测试直接 new 的场景：无配置服务，按默认（开启 + 不限制）处理
        }
        if (!settings.isEnabled()) {
            throw new BizException(403, "AI 摘要功能已关闭");
        }
        if (!settings.isGroupAllowed(groupId)) {
            throw new BizException(403, "该群未开启 AI 摘要");
        }
    }

    /** 参与日报的最短内容长度（运行时配置优先，未注入配置服务时用常量默认值） */
    private int configuredMinLength() {
        AiSummarySettingsService settings = aiSummarySettingsService;
        int value = settings == null ? MIN_CONTENT_LENGTH : settings.getMinLength();
        return value > 0 ? value : MIN_CONTENT_LENGTH;
    }

    /** 拼接输入总长上限（运行时配置优先，未注入配置服务时用常量默认值） */
    private int configuredMaxInputChars() {
        AiSummarySettingsService settings = aiSummarySettingsService;
        int value = settings == null ? MAX_INPUT_CHARS : settings.getMaxInputChars();
        return value > 0 ? value : MAX_INPUT_CHARS;
    }

    // ==================== 取数与拼接（纯逻辑，便于单测） ====================

    /** 取某群某天的日报候选消息（长度下限取运行时配置的 minLength） */
    public List<Message> loadDailyMessages(String groupId, LocalDate date) {
        LocalDateTime start = date.atStartOfDay();
        LocalDateTime end = start.plusDays(1);
        List<Message> list = messageRepository.findDigestCandidates(
                groupId, Message.MessageType.TEXT, configuredMinLength(), start, end);
        return list == null ? Collections.emptyList() : list;
    }

    /**
     * 把当天消息拼成模型输入：逐条截断到 {@value #PER_MESSAGE_MAX_CHARS} 字符（格式「昵称: 内容」），
     * 最后按总长（运行时配置的 maxInputChars）做「两端保留 + 中间省略」处理。
     */
    public String buildDigestInput(List<Message> messages) {
        if (messages == null || messages.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (Message m : messages) {
            if (m == null || m.getContent() == null) {
                continue;
            }
            String content = m.getContent().trim();
            if (content.isEmpty()) {
                continue;
            }
            String who = notBlank(m.getUserNickname()) ? m.getUserNickname().trim()
                    : (notBlank(m.getUserQq()) ? m.getUserQq().trim() : "未知");
            String line = truncate(who + ": " + content, PER_MESSAGE_MAX_CHARS);
            if (sb.length() > 0) {
                sb.append('\n');
            }
            sb.append(line);
        }
        return clipMiddle(sb.toString());
    }

    /** 超长输入处理：按运行时配置的上限保留两端（默认前 6000 + 中间省略 + 后 6000） */
    public String clipMiddle(String text) {
        return clipMiddle(text, configuredMaxInputChars());
    }

    /** 超长输入处理（显式指定上限，便于单测） */
    public String clipMiddle(String text, int maxInputChars) {
        if (text == null) {
            return "";
        }
        int max = maxInputChars > 0 ? maxInputChars : MAX_INPUT_CHARS;
        if (text.length() <= max) {
            return text;
        }
        int keep = max / 2;
        int omitted = text.length() - keep * 2;
        return text.substring(0, keep)
                + "\n…（中间省略 " + omitted + " 字）…\n"
                + text.substring(text.length() - keep);
    }

    /** 群日报提示词：要求模型只输出 JSON */
    public String buildPrompt(String groupId, LocalDate date, String content) {
        return "你是一个群聊日报助手。以下是 QQ 群 " + groupId + " 在 " + date + " 的聊天记录：\n\n"
                + content + "\n\n"
                + "请阅读以上聊天记录，生成这个群「今日速览」。\n"
                + "【输出要求】只输出一个 JSON 对象，不要输出任何解释、前后缀或 Markdown 代码块围栏。\n"
                + "JSON 格式：{\"summary\":\"一句话总览（80 字内）\",\"tags\":[\"标签1\",\"标签2\"],\"sentiment\":\"positive|neutral|negative\"}\n"
                + "字段说明：\n"
                + "- summary：一句话概括今天群里主要聊了什么（80 字内）\n"
                + "- tags：2-5 个主题标签（如：组队、攻略、吐槽）\n"
                + "- sentiment：整体氛围，只能从 positive（积极）/ neutral（中性）/ negative（消极）中三选一";
    }

    // ==================== 解析（模仿 AiSummaryParser 的写法） ====================

    /**
     * 把模型输出解析进实体字段。
     *
     * <p>剥离 ```json 围栏后用 Jackson 解析；解析失败不抛异常，
     * 直接把纯文本当作 summary（界面上至少还能看到内容）。</p>
     */
    public void applyModelOutput(GroupDigest digest, String raw) {
        if (digest == null) {
            return;
        }
        String text = stripFence(raw);
        if (text.isEmpty()) {
            digest.setSummary("");
            return;
        }
        try {
            JsonNode node = MAPPER.readTree(text);
            if (node != null && node.isObject()) {
                JsonNode tags = node.get("tags");
                if (tags != null && tags.isArray()) {
                    StringBuilder sb = new StringBuilder();
                    for (JsonNode t : tags) {
                        String v = t.asText("").trim();
                        if (v.isEmpty()) {
                            continue;
                        }
                        if (sb.length() > 0) {
                            sb.append(',');
                        }
                        sb.append(v);
                    }
                    digest.setTags(truncate(sb.toString(), TAGS_MAX));
                }
                JsonNode sentiment = node.get("sentiment");
                if (sentiment != null && !sentiment.isNull()) {
                    digest.setSentiment(truncate(sentiment.asText("").trim(), SENTIMENT_MAX));
                }
                JsonNode summary = node.get("summary");
                String out = (summary != null && !summary.isNull()) ? summary.asText("") : text;
                digest.setSummary(truncate(out.trim(), SUMMARY_MAX));
                return;
            }
        } catch (Exception e) {
            log.debug("群日报输出不是 JSON，按纯文本处理: groupId={}", digest.getGroupId());
        }
        digest.setSummary(truncate(text, SUMMARY_MAX));
    }

    // ==================== 内部工具 ====================

    private String resolveModelName() {
        return notBlank(summaryModel) ? summaryModel.trim() : "astrbot-default";
    }

    private static String stripFence(String raw) {
        if (raw == null) {
            return "";
        }
        String text = raw.trim();
        if (text.startsWith("```")) {
            text = text.replaceAll("^```[a-zA-Z]*\\s*", "").replaceAll("\\s*```$", "").trim();
        }
        return text;
    }

    private static String truncate(String s, int max) {
        if (s == null) {
            return null;
        }
        return s.length() > max ? s.substring(0, max) : s;
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }
}

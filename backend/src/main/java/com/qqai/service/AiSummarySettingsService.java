package com.qqai.service;

import com.qqai.exception.BizException;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.core.env.Environment;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * AI 摘要运行时配置（阶段 2/3 收尾）。
 *
 * <p><b>为什么需要它</b>：{@code enabled / dailyLimit / minLength / maxInputChars / groupWhitelist /
 * digestCron / digestGroups} 必须能在后台改完立即生效，而不是只读 yml。做法是：</p>
 * <ol>
 *   <li>本服务持有内存态（volatile 字段），所有业务侧改为向它取值；</li>
 *   <li>启动时读初值：先看 {@link ConfigService} 落盘的覆盖文件
 *       （{@code data/application-override.properties}，即后台改过的值），
 *       没有再回退到 Spring 环境（application.yml / 环境变量 / 系统属性）；</li>
 *   <li>PUT 时更新内存 + 通过 {@link ConfigService#updateConfig(Map)} 落盘并写系统属性，
 *       <b>完全复用项目既有的配置存取机制</b>，不另造一套；</li>
 *   <li>更新后发布 {@link SettingsChangedEvent}，让定时日报调度器重新注册 cron。</li>
 * </ol>
 *
 * <p>字段名（{@code enabled / dailyLimit / minLength / maxInputChars / groupWhitelist / digestCron /
 * digestGroups}）是与前端冻结的契约，见 {@code AdminBackup.vue} 的摘要设置区块。</p>
 */
@Service
public class AiSummarySettingsService {

    private static final Logger log = LoggerFactory.getLogger(AiSummarySettingsService.class);

    // ==================== 配置键（与 application.yml 的 ai.summary.* 一一对应） ====================
    public static final String KEY_ENABLED = "ai.summary.enabled";
    public static final String KEY_DAILY_LIMIT = "ai.summary.daily-limit";
    public static final String KEY_MIN_LENGTH = "ai.summary.min-length";
    public static final String KEY_MAX_INPUT_CHARS = "ai.summary.max-input-chars";
    public static final String KEY_GROUP_WHITELIST = "ai.summary.group-whitelist";
    public static final String KEY_DIGEST_CRON = "ai.summary.digest-cron";
    public static final String KEY_DIGEST_GROUPS = "ai.summary.digest-groups";

    // ==================== 默认值（与 application.yml 的 ai.summary.* 默认值保持一致） ====================
    private static final boolean DEFAULT_ENABLED = true;
    private static final int DEFAULT_DAILY_LIMIT = 300;
    private static final int DEFAULT_MIN_LENGTH = 8;
    private static final int DEFAULT_MAX_INPUT_CHARS = 12000;

    /** 取值区间（与前端表单 min/max 一致），越界值会被收敛，避免把链路参数配坏 */
    private static final int DAILY_LIMIT_MIN = 0;
    private static final int DAILY_LIMIT_MAX = 100000;
    private static final int MIN_LENGTH_MIN = 1;
    private static final int MIN_LENGTH_MAX = 200;
    private static final int MAX_INPUT_CHARS_MIN = 1000;
    private static final int MAX_INPUT_CHARS_MAX = 200000;

    @Autowired
    private Environment env;

    @Autowired
    private ConfigService configService;

    @Autowired
    private ApplicationEventPublisher eventPublisher;

    // ==================== 内存态（运行时真实生效的值） ====================
    private volatile boolean enabled = DEFAULT_ENABLED;
    private volatile int dailyLimit = DEFAULT_DAILY_LIMIT;
    private volatile int minLength = DEFAULT_MIN_LENGTH;
    private volatile int maxInputChars = DEFAULT_MAX_INPUT_CHARS;
    private volatile String groupWhitelist = "";
    private volatile String digestCron = "";
    private volatile String digestGroups = "";

    @PostConstruct
    public void init() {
        reload();
    }

    // ==================== 读 ====================

    /** 总开关：false 时所有摘要接口（单条/批量/群日报）都应返回 403 */
    public boolean isEnabled() {
        return enabled;
    }

    /** 全站每日摘要条数上限；&lt;= 0 表示不限制（与既有语义一致） */
    public int getDailyLimit() {
        return dailyLimit;
    }

    /** 参与摘要的最短内容长度 */
    public int getMinLength() {
        return minLength;
    }

    /** 群日报拼接的输入总长上限 */
    public int getMaxInputChars() {
        return maxInputChars;
    }

    /** 群白名单原文（逗号分隔，空 = 不限制） */
    public String getGroupWhitelist() {
        return groupWhitelist;
    }

    /** 定时日报 cron 原文（6 段含秒；空 = 不定时） */
    public String getDigestCron() {
        return digestCron;
    }

    /** 定时日报的群列表原文（逗号分隔；空 = 由调度器改为「今天有消息的群」） */
    public String getDigestGroups() {
        return digestGroups;
    }

    /** 群白名单列表（空 = 不限制） */
    public List<String> whitelistGroupList() {
        return splitGroups(groupWhitelist);
    }

    /** 定时日报的群列表（空 = 不代表「没有群」，而是「今天有消息的群」） */
    public List<String> digestGroupList() {
        return splitGroups(digestGroups);
    }

    /**
     * 群白名单校验：白名单为空 = 不限制；非空 = 只有列表内的群能生成/读取日报。
     */
    public boolean isGroupAllowed(String groupId) {
        List<String> list = whitelistGroupList();
        if (list.isEmpty()) {
            return true;
        }
        return groupId != null && list.contains(groupId.trim());
    }

    /**
     * 配置快照。字段名与前端冻结契约完全一致：
     * {@code { enabled, dailyLimit, minLength, maxInputChars, groupWhitelist, digestCron, digestGroups }}。
     */
    public Map<String, Object> toView() {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("enabled", enabled);
        view.put("dailyLimit", dailyLimit);
        view.put("minLength", minLength);
        view.put("maxInputChars", maxInputChars);
        view.put("groupWhitelist", groupWhitelist);
        view.put("digestCron", digestCron);
        view.put("digestGroups", digestGroups);
        return view;
    }

    // ==================== 写 ====================

    /**
     * 部分字段更新：只处理 body 里出现的键，其余保持不变。
     *
     * @param body 前端提交的字段集合（值可能是 Boolean / Number / String）
     * @return 更新后的配置快照
     * @throws BizException 400 digestCron 不是合法的 6 段 cron 表达式
     */
    public synchronized Map<String, Object> update(Map<String, Object> body) {
        if (body == null || body.isEmpty()) {
            return toView();
        }
        Map<String, String> persist = new LinkedHashMap<>();

        if (body.containsKey("enabled")) {
            boolean value = parseBool(text(body.get("enabled")), enabled);
            this.enabled = value;
            persist.put(KEY_ENABLED, String.valueOf(value));
        }
        if (body.containsKey("dailyLimit")) {
            int value = clamp(parseInt(text(body.get("dailyLimit")), dailyLimit), DAILY_LIMIT_MIN, DAILY_LIMIT_MAX);
            this.dailyLimit = value;
            persist.put(KEY_DAILY_LIMIT, String.valueOf(value));
        }
        if (body.containsKey("minLength")) {
            int value = clamp(parseInt(text(body.get("minLength")), minLength), MIN_LENGTH_MIN, MIN_LENGTH_MAX);
            this.minLength = value;
            persist.put(KEY_MIN_LENGTH, String.valueOf(value));
        }
        if (body.containsKey("maxInputChars")) {
            int value = clamp(parseInt(text(body.get("maxInputChars")), maxInputChars),
                    MAX_INPUT_CHARS_MIN, MAX_INPUT_CHARS_MAX);
            this.maxInputChars = value;
            persist.put(KEY_MAX_INPUT_CHARS, String.valueOf(value));
        }
        if (body.containsKey("groupWhitelist")) {
            String value = text(body.get("groupWhitelist"));
            this.groupWhitelist = value;
            persist.put(KEY_GROUP_WHITELIST, value);
        }
        if (body.containsKey("digestCron")) {
            String value = text(body.get("digestCron"));
            if (!value.isEmpty() && !CronExpression.isValidExpression(value)) {
                throw new BizException(400, "digestCron 表达式非法（需 6 段含秒，如 0 50 23 * * ?）");
            }
            this.digestCron = value;
            persist.put(KEY_DIGEST_CRON, value);
        }
        if (body.containsKey("digestGroups")) {
            String value = text(body.get("digestGroups"));
            this.digestGroups = value;
            persist.put(KEY_DIGEST_GROUPS, value);
        }

        // 落盘 + 系统属性覆盖：复用 ConfigService 既有机制（data/application-override.properties）
        if (!persist.isEmpty() && configService != null) {
            try {
                configService.updateConfig(persist);
            } catch (Exception e) {
                log.warn("AI 摘要配置落盘失败（内存已生效，重启后可能回退到 yml 默认值）: {}", e.getMessage());
            }
        }

        Map<String, Object> snapshot = toView();
        log.info("AI 摘要配置已更新: {}", snapshot);
        publishChanged(snapshot);
        return snapshot;
    }

    /**
     * 重新从「落盘覆盖文件 → Spring 环境」读取全部初值。
     * 供启动（{@link #init()}）与需要手动刷新的场景使用。
     */
    public synchronized void reload() {
        Map<String, String> persisted = persistedOverrides();
        this.enabled = parseBool(value(persisted, KEY_ENABLED), DEFAULT_ENABLED);
        this.dailyLimit = clamp(parseInt(value(persisted, KEY_DAILY_LIMIT), DEFAULT_DAILY_LIMIT),
                DAILY_LIMIT_MIN, DAILY_LIMIT_MAX);
        this.minLength = clamp(parseInt(value(persisted, KEY_MIN_LENGTH), DEFAULT_MIN_LENGTH),
                MIN_LENGTH_MIN, MIN_LENGTH_MAX);
        this.maxInputChars = clamp(parseInt(value(persisted, KEY_MAX_INPUT_CHARS), DEFAULT_MAX_INPUT_CHARS),
                MAX_INPUT_CHARS_MIN, MAX_INPUT_CHARS_MAX);
        this.groupWhitelist = value(persisted, KEY_GROUP_WHITELIST);
        this.digestCron = value(persisted, KEY_DIGEST_CRON);
        this.digestGroups = value(persisted, KEY_DIGEST_GROUPS);
        log.info("AI 摘要配置已加载: enabled={}, dailyLimit={}, minLength={}, maxInputChars={}, "
                        + "groupWhitelist=[{}], digestCron=[{}], digestGroups=[{}]",
                enabled, dailyLimit, minLength, maxInputChars, groupWhitelist, digestCron, digestGroups);
    }

    // ==================== 内部工具 ====================

    /**
     * 单键取值：已落盘的覆盖值优先（键存在即生效，空串表示「被后台显式清空」），
     * 否则回退到 Spring 环境（application.yml / 环境变量 / 系统属性）。取值统一 trim。
     */
    private String value(Map<String, String> persisted, String key) {
        if (persisted.containsKey(key)) {
            String v = persisted.get(key);
            return v == null ? "" : v.trim();
        }
        String v = env == null ? null : env.getProperty(key);
        return v == null ? "" : v.trim();
    }

    /** 读取落盘覆盖文件（失败不影响启动，按无覆盖处理） */
    private Map<String, String> persistedOverrides() {
        if (configService == null) {
            return Collections.emptyMap();
        }
        try {
            Map<String, String> overrides = configService.getPersistedOverrides();
            return overrides == null ? Collections.emptyMap() : overrides;
        } catch (Exception e) {
            log.warn("读取配置覆盖文件失败，按 yml/环境变量取值: {}", e.getMessage());
            return Collections.emptyMap();
        }
    }

    /** 配置变更事件：定时日报调度器监听后重新注册 cron，实现「保存即生效」 */
    private void publishChanged(Map<String, Object> snapshot) {
        if (eventPublisher == null) {
            return;
        }
        try {
            eventPublisher.publishEvent(new SettingsChangedEvent(snapshot));
        } catch (Exception e) {
            log.warn("发布 AI 摘要配置变更事件失败（配置已生效，但定时任务可能需重启后换 cron）: {}", e.getMessage());
        }
    }

    private static String text(Object raw) {
        return raw == null ? "" : String.valueOf(raw).trim();
    }

    private static boolean parseBool(String raw, boolean fallback) {
        if (raw == null || raw.isEmpty()) {
            return fallback;
        }
        String v = raw.trim().toLowerCase();
        if ("true".equals(v) || "1".equals(v) || "yes".equals(v) || "on".equals(v)) {
            return true;
        }
        if ("false".equals(v) || "0".equals(v) || "no".equals(v) || "off".equals(v)) {
            return false;
        }
        return fallback;
    }

    private static int parseInt(String raw, int fallback) {
        if (raw == null || raw.isEmpty()) {
            return fallback;
        }
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static int clamp(int value, int min, int max) {
        return Math.min(Math.max(value, min), max);
    }

    /**
     * 解析逗号分隔的群号列表：兼容英文/中文逗号、分号与空白分隔，去空项并去重（保持顺序）。
     */
    public static List<String> splitGroups(String raw) {
        if (raw == null || raw.isBlank()) {
            return Collections.emptyList();
        }
        Set<String> groups = new LinkedHashSet<>();
        for (String part : raw.split("[,，;；\\s]+")) {
            String g = part.trim();
            if (!g.isEmpty()) {
                groups.add(g);
            }
        }
        return new ArrayList<>(groups);
    }

    /**
     * 配置变更事件。用事件而不是直接注入调度器，避免
     * {@code GroupDigestScheduler → AiSummarySettingsService → GroupDigestScheduler} 的循环依赖。
     */
    public static class SettingsChangedEvent {

        private final Map<String, Object> snapshot;

        public SettingsChangedEvent(Map<String, Object> snapshot) {
            this.snapshot = snapshot;
        }

        public Map<String, Object> getSnapshot() {
            return snapshot;
        }
    }
}

package com.qqai.service;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 媒体保留策略（运行时配置）：超过 N 天的媒体文件由定时任务自动清理。
 *
 * <p><b>为什么需要它</b>：{@code enabled} / {@code days} 必须能在后台改完立即生效、且重启后仍生效，
 * 而不是只改 yml。做法与 {@link AiSummarySettingsService} 保持一致，复用项目既有的配置存取机制：</p>
 * <ol>
 *   <li>本服务持有内存态（volatile 字段），定时任务与预览接口都向它取值；</li>
 *   <li>启动时读初值：先看 {@link ConfigService} 落盘的覆盖文件
 *       （{@code data/application-override.properties}，即后台改过的值），
 *       没有再回退到 Spring 环境（application.yml / 环境变量 / 系统属性）；</li>
 *   <li>PUT 时更新内存 + 通过 {@link ConfigService#updateConfig(Map)} 落盘并写系统属性。</li>
 * </ol>
 *
 * <p><b>默认关闭</b>：一个会自动删除用户数据的定时任务，默认开启等于埋雷，必须由管理员显式打开。</p>
 *
 * <p><b>删除口径</b>：按文件「修改时间」（即媒体管理列表里显示的创建时间）计算年龄，
 * 与 {@code GET /api/messages/media-files} 的时间筛选保持同一口径，避免出现
 * 「列表里看着只有 3 天、定时任务却按 100 天删掉」这类对不上的情况。</p>
 */
@Service
public class MediaRetentionSettingsService {

    private static final Logger log = LoggerFactory.getLogger(MediaRetentionSettingsService.class);

    // ==================== 配置键（与 application.yml 的 file.retention.* 一一对应） ====================
    public static final String KEY_ENABLED = "file.retention.enabled";
    public static final String KEY_DAYS = "file.retention.days";

    /** 参与自动清理的类型（配置项；管理端只暴露开关与天数，改类型请改配置） */
    private static final String KEY_TYPES = "file.retention.types";

    // ==================== 默认值（与 application.yml 默认值保持一致） ====================
    private static final boolean DEFAULT_ENABLED = false;
    private static final int DEFAULT_DAYS = 90;
    private static final String DEFAULT_TYPES = "IMAGE,VIDEO,AUDIO";

    /** 天数区间：下限 1 天（防止「0 天」把刚收到的媒体全删），上限 10 年 */
    static final int DAYS_MIN = 1;
    static final int DAYS_MAX = 3650;

    /** 允许的类型白名单：不把 FILE（pdf/zip/doc 等）纳入自动清理——那类文件的误删代价不同 */
    private static final List<String> ALLOWED_TYPES = List.of("IMAGE", "VIDEO", "AUDIO");

    @Autowired
    private Environment env;

    @Autowired
    private ConfigService configService;

    // ==================== 内存态（运行时真实生效的值） ====================
    private volatile boolean enabled = DEFAULT_ENABLED;
    private volatile int days = DEFAULT_DAYS;
    private volatile String types = DEFAULT_TYPES;

    @PostConstruct
    public void init() {
        reload();
    }

    // ==================== 读 ====================

    /** 总开关：false 时定时任务直接跳过，一次文件扫描都不做 */
    public boolean isEnabled() {
        return enabled;
    }

    /** 保留天数：修改时间早于「现在 - days」的媒体文件会被清理 */
    public int getDays() {
        return days;
    }

    /** 参与自动清理的类型列表（已过滤到 IMAGE/VIDEO/AUDIO，空列表退化为默认三类） */
    public List<String> typeList() {
        List<String> list = new ArrayList<>();
        if (types != null) {
            for (String raw : types.split(",")) {
                String t = raw == null ? "" : raw.trim().toUpperCase();
                if (ALLOWED_TYPES.contains(t) && !list.contains(t)) {
                    list.add(t);
                }
            }
        }
        return list.isEmpty() ? new ArrayList<>(ALLOWED_TYPES) : list;
    }

    /**
     * 配置快照。字段名即与前端冻结的契约：{@code { enabled, days, types }}。
     */
    public Map<String, Object> toView() {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("enabled", enabled);
        view.put("days", days);
        view.put("types", typeList());
        return view;
    }

    // ==================== 写 ====================

    /**
     * 部分字段更新：只处理 body 里出现的键（{@code enabled} / {@code days}），其余保持不变。
     *
     * @param body 前端提交的字段集合（值可能是 Boolean / Number / String）
     * @return 更新后的配置快照
     */
    public synchronized Map<String, Object> update(Map<String, Object> body) {
        if (body == null || body.isEmpty()) {
            return toView();
        }
        Map<String, String> persist = new LinkedHashMap<>();

        if (body.containsKey("enabled")) {
            boolean value = parseBool(String.valueOf(body.get("enabled")), enabled);
            this.enabled = value;
            persist.put(KEY_ENABLED, String.valueOf(value));
        }
        if (body.containsKey("days")) {
            int raw = parseInt(String.valueOf(body.get("days")), days);
            int value = Math.max(DAYS_MIN, Math.min(DAYS_MAX, raw));
            this.days = value;
            persist.put(KEY_DAYS, String.valueOf(value));
        }

        if (!persist.isEmpty() && configService != null) {
            try {
                configService.updateConfig(persist);
            } catch (Exception e) {
                log.warn("媒体保留策略落盘失败（内存已生效，重启后可能回退到 yml 默认值）: {}", e.getMessage());
            }
        }

        Map<String, Object> snapshot = toView();
        log.info("媒体保留策略已更新: {}", snapshot);
        return snapshot;
    }

    /**
     * 重新从「落盘覆盖文件 → Spring 环境」读取全部初值（启动时调用）。
     */
    public synchronized void reload() {
        Map<String, String> persisted = persistedOverrides();
        this.enabled = parseBool(value(persisted, KEY_ENABLED), DEFAULT_ENABLED);
        int rawDays = parseInt(value(persisted, KEY_DAYS), DEFAULT_DAYS);
        this.days = Math.max(DAYS_MIN, Math.min(DAYS_MAX, rawDays));
        String rawTypes = value(persisted, KEY_TYPES);
        this.types = rawTypes == null || rawTypes.isEmpty() ? DEFAULT_TYPES : rawTypes;
        log.info("媒体保留策略已加载: enabled={}, days={}, types={}", enabled, days, typeList());
    }

    // ==================== 内部工具 ====================

    /**
     * 单键取值：已落盘的覆盖值优先（键存在即生效，空串表示「被后台显式清空」），
     * 否则回退到 Spring 环境。取值统一 trim。
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
}

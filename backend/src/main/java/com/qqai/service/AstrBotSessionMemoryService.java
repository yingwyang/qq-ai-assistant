package com.qqai.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.File;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * AstrBot 会话记忆（session 历史）工具：负责「同一对话的记忆键」与「删对话时连带清记忆」。
 *
 * <p><b>记忆从哪来</b>：AstrBot 4.28 把每轮对话按 {@code session_id} 存进自己的
 * {@code data_v4.db}（{@code conversations} / {@code platform_message_history}），下一轮再把该会话
 * 的历史拼进模型上下文。我们请求体里的 {@code context} 字段它并不消费（实测：新 session 只传
 * {@code context}，模型仍不知道历史）。所以**会话键才是记忆的真正开关**。</p>
 *
 * <p><b>为什么用本项目 conversationId 做键</b>：①「新对话」= 新 session，旧记忆不再注入；
 * ②未选群聊也有记忆（此前只有 groupId 才传 session_id）；③人格拼进键里，换人格换 session
 * （AstrBot 会话记录里存了 persona，且优先级高于请求里的档案）。</p>
 *
 * <p><b>删除会话时的级联清理</b>：AstrBot 的开放 API Key 没有 data scope（实测 403），
 * 无法走它的 REST 删会话；这里直接对 {@code data_v4.db} 做**精确匹配**的删除：
 * 只删 session 形如 {@code qqai-<conversationId>} 或 {@code qqai-<conversationId>@人格} 的行，
 * 用 Java 侧过滤而不是 SQL LIKE（conversationId 里有下划线，LIKE 会误伤）。
 * 全程 best-effort：失败只记日志，不影响我们自己的删除。</p>
 */
@Service
public class AstrBotSessionMemoryService {

    private static final Logger log = LoggerFactory.getLogger(AstrBotSessionMemoryService.class);

    /** 记忆键前缀（与我们自己会话 id 拼接，便于精确清理） */
    public static final String SESSION_PREFIX = "qqai-";

    @Value("${astrbot.data-path:}")
    private String astrBotDataPath;

    public AstrBotSessionMemoryService() {
    }

    /** 供测试注入数据目录 */
    AstrBotSessionMemoryService(String dataPath) {
        this.astrBotDataPath = dataPath;
    }

    /**
     * 构造 AstrBot 的 session_id。
     *
     * @param conversationId 本项目会话 id（形如 conv_1793…）
     * @param personaKey     人格标识（slug 或短哈希）；空 = 不使用人格
     */
    public static String buildSessionId(String conversationId, String personaKey) {
        if (conversationId == null || conversationId.isBlank()) return null;
        String base = SESSION_PREFIX + conversationId.trim();
        return personaKey == null || personaKey.isBlank() ? base : base + "@" + personaKey.trim();
    }

    /**
     * 人格标识：优先 slug；中文人格 slug 可能为空，回落成短哈希，避免所有人格共用 {@code @}。
     */
    public static String personaKey(String personaId) {
        if (personaId == null || personaId.isBlank()) return null;
        String slug = AstrBotPersonaService.slug(personaId);
        if (slug != null && !slug.isBlank()) return slug;
        return "p" + Integer.toHexString(personaId.hashCode());
    }

    /**
     * 判断某个 AstrBot session 是否属于该对话。
     */
    public static boolean belongsToConversation(String sessionId, String conversationId) {
        if (sessionId == null || conversationId == null) return false;
        String base = SESSION_PREFIX + conversationId.trim();
        return sessionId.equals(base) || sessionId.startsWith(base + "@");
    }

    /**
     * 精确删掉一个一次性 session（摘要/分析用的 {@code qqai-<uuid>}）的三张表记录。
     *
     * <p>这类 session 因为「每次新建、避免人格/工具污染」而只被用一次；不清理就会在 AstrBot
     * 的「数据与日志 → 对话」里每调一次摘要就多一条（实测曾累积 198 条摘要 + 50 条分析记录）。</p>
     */
    public Map<String, Object> purgeSession(String sessionId) {
        Map<String, Object> result = new LinkedHashMap<>();
        if (sessionId == null || sessionId.isBlank()) return result;
        if (astrBotDataPath == null || astrBotDataPath.isBlank()) return result;
        File dbFile = new File(astrBotDataPath, "data_v4.db");
        if (!dbFile.exists()) return result;
        try (Connection conn = DriverManager.getConnection("jdbc:sqlite:" + dbFile.getAbsolutePath())) {
            conn.setAutoCommit(false);
            // conversations.user_id 是完整 umo，只能取尾段精确比对
            List<String> convIds = new ArrayList<>();
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT conversation_id, user_id FROM conversations WHERE platform_id = 'webchat'");
                 ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String raw = rs.getString(2);
                    if (raw != null && raw.endsWith("!" + sessionId)) convIds.add(rs.getString(1));
                }
            }
            int deleted = 0;
            try (PreparedStatement ps = conn.prepareStatement(
                    "DELETE FROM conversations WHERE platform_id = 'webchat' AND conversation_id = ?")) {
                for (String cid : convIds) {
                    ps.setString(1, cid);
                    deleted += ps.executeUpdate();
                }
            }
            int history = deleteBySession(conn, "platform_message_history", "user_id", sessionId);
            int sessions = deleteBySession(conn, "platform_sessions", "session_id", sessionId);
            conn.commit();
            result.put("conversationsDeleted", deleted);
            result.put("historyDeleted", history);
            result.put("sessionsDeleted", sessions);
        } catch (Exception e) {
            log.debug("清理一次性会话失败 session={}: {}", sessionId, e.getMessage());
            result.put("error", e.getMessage());
        }
        return result;
    }

    /** 分析对话保留天数：用户要求保留分析对话并补标题，只清 30 天前的 */
    public static final int ANALYZER_RETENTION_DAYS = 30;

    /** 异步补标题的单线程执行器（daemon，不阻止应用退出） */
    private final java.util.concurrent.ScheduledExecutorService titleExecutor =
            java.util.concurrent.Executors.newSingleThreadScheduledExecutor(runnable -> {
                Thread thread = new Thread(runnable, "astrbot-session-title");
                thread.setDaemon(true);
                return thread;
            });

    /**
     * 是否需要清理该会话：
     * <ul>
     *   <li>后台摘要（user=summarizer）：一次性产物，立即清；</li>
     *   <li>用户触发的分析（user=analyzer）：**保留**，只清超过 {@link #ANALYZER_RETENTION_DAYS} 天的；</li>
     *   <li>真实网页对话（qqai-conv_*、裸 uuid、群号@人格）：永不清理。</li>
     * </ul>
     *
     * @param expired 该会话是否已超过保留期（SQL 侧按 updated_at 判断）
     */
    public static boolean shouldPurgeSession(String user, boolean expired) {
        if ("summarizer".equals(user)) return true;
        return "analyzer".equals(user) && expired;
    }

    /**
     * 扫一遍 AstrBot 会话库，按 {@link #shouldPurgeSession} 清理。
     *
     * <p><b>为什么需要它</b>：AstrBot 是在回复之后才异步落库会话记录的，接口返回后立刻删可能
     * 扑空（实测泄漏过）。定时扫底 + 手动接口双保险：摘要不囤积，分析只按保留期清。</p>
     */
    public Map<String, Object> purgeOneShotSessions() {
        Map<String, Object> result = new LinkedHashMap<>();
        if (astrBotDataPath == null || astrBotDataPath.isBlank()) {
            result.put("skipped", "astrbot.data-path 未配置");
            return result;
        }
        File dbFile = new File(astrBotDataPath, "data_v4.db");
        if (!dbFile.exists()) {
            result.put("skipped", "data_v4.db 不存在");
            return result;
        }
        String lookupSql = "SELECT conversation_id, user_id, "
                + "(updated_at < datetime('now', '-" + ANALYZER_RETENTION_DAYS + " days')) AS expired "
                + "FROM conversations WHERE platform_id = 'webchat'";
        try (Connection conn = DriverManager.getConnection("jdbc:sqlite:" + dbFile.getAbsolutePath())) {
            conn.setAutoCommit(false);
            List<String> convIds = new ArrayList<>();
            java.util.Set<String> sessions = new java.util.LinkedHashSet<>();
            try (PreparedStatement ps = conn.prepareStatement(lookupSql);
                 ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String raw = rs.getString(2);
                    if (raw == null) continue;
                    int bang = raw.lastIndexOf('!');
                    String session = bang >= 0 ? raw.substring(bang + 1) : raw;
                    String user = bang > 0 ? raw.substring(raw.lastIndexOf('!', bang - 1) + 1, bang) : "";
                    boolean expired = rs.getInt(3) == 1;
                    if (shouldPurgeSession(user, expired)) {
                        convIds.add(rs.getString(1));
                        sessions.add(session);
                    }
                }
            }
            int convDeleted = 0;
            try (PreparedStatement ps = conn.prepareStatement(
                    "DELETE FROM conversations WHERE platform_id = 'webchat' AND conversation_id = ?")) {
                for (String cid : convIds) {
                    ps.setString(1, cid);
                    convDeleted += ps.executeUpdate();
                }
            }
            int history = 0;
            int sess = 0;
            for (String sid : sessions) {
                history += deleteBySession(conn, "platform_message_history", "user_id", sid);
                sess += deleteBySession(conn, "platform_sessions", "session_id", sid);
            }
            conn.commit();
            result.put("conversationsDeleted", convDeleted);
            result.put("historyDeleted", history);
            result.put("sessionsDeleted", sess);
            if (convDeleted > 0 || history > 0 || sess > 0) {
                log.info("清扫会话（摘要即时清 / 分析超 {} 天清）：conversations={}, history={}, sessions={}",
                        ANALYZER_RETENTION_DAYS, convDeleted, history, sess);
            }
        } catch (Exception e) {
            log.warn("清扫会话失败: {}", e.getMessage());
            result.put("error", e.getMessage());
        }
        return result;
    }

    /** 每 2 分钟扫一遍（启动后 60 秒开始），兜住「回复后才异步落库」的竞态 */
    @org.springframework.scheduling.annotation.Scheduled(initialDelay = 60_000, fixedDelay = 120_000)
    public void sweepOneShotSessions() {
        purgeOneShotSessions();
    }

    /**
     * 给某个 session 对应的 AstrBot 会话写标题（按 umo 尾段精确匹配）。
     *
     * @return true 表示已写入（会话行当时已存在）
     */
    public boolean setTitleForSession(String sessionId, String title) {
        if (sessionId == null || sessionId.isBlank() || title == null || title.isBlank()) return false;
        if (astrBotDataPath == null || astrBotDataPath.isBlank()) return false;
        File dbFile = new File(astrBotDataPath, "data_v4.db");
        if (!dbFile.exists()) return false;
        try (Connection conn = DriverManager.getConnection("jdbc:sqlite:" + dbFile.getAbsolutePath())) {
            conn.setAutoCommit(false);
            List<String> ids = new ArrayList<>();
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT conversation_id, user_id FROM conversations WHERE platform_id = 'webchat'");
                 ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String raw = rs.getString(2);
                    if (raw != null && raw.endsWith("!" + sessionId)) ids.add(rs.getString(1));
                }
            }
            int updated = 0;
            try (PreparedStatement ps = conn.prepareStatement(
                    "UPDATE conversations SET title = ? WHERE platform_id = 'webchat' AND conversation_id = ?")) {
                for (String cid : ids) {
                    ps.setString(1, title);
                    ps.setString(2, cid);
                    updated += ps.executeUpdate();
                }
            }
            conn.commit();
            return updated > 0;
        } catch (Exception e) {
            log.debug("写入会话标题失败 session={}: {}", sessionId, e.getMessage());
            return false;
        }
    }

    /**
     * 异步补标题：AstrBot 回复后才异步落库，所以 1 秒后开始重试（每 2 秒一次，约 30 秒）。
     */
    public void setTitleForSessionAsync(String sessionId, String title) {
        if (sessionId == null || sessionId.isBlank() || title == null || title.isBlank()) return;
        final int[] attempts = {0};
        Runnable task = new Runnable() {
            @Override
            public void run() {
                try {
                    if (setTitleForSession(sessionId, title)) return;
                } catch (Exception e) {
                    log.debug("补标题失败（将重试）: {}", e.getMessage());
                }
                if (++attempts[0] < 15) {
                    titleExecutor.schedule(this, 2, java.util.concurrent.TimeUnit.SECONDS);
                }
            }
        };
        titleExecutor.schedule(task, 1, java.util.concurrent.TimeUnit.SECONDS);
    }

    private int deleteBySession(Connection conn, String table, String column, String sessionId) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "DELETE FROM " + table + " WHERE platform_id = 'webchat' AND " + column + " = ?")) {
            ps.setString(1, sessionId);
            return ps.executeUpdate();
        }
    }

    /**
     * 删除某对话在 AstrBot 侧的记忆（会话记录 + 平台消息历史 + 平台会话行）。
     *
     * @return 各表删除计数；失败时带 {@code error}，但绝不抛出
     */
    public Map<String, Object> purgeConversation(String conversationId) {
        Map<String, Object> result = new LinkedHashMap<>();
        if (conversationId == null || conversationId.isBlank()) {
            result.put("skipped", "conversationId 为空");
            return result;
        }
        if (astrBotDataPath == null || astrBotDataPath.isBlank()) {
            result.put("skipped", "astrbot.data-path 未配置");
            return result;
        }
        File dbFile = new File(astrBotDataPath, "data_v4.db");
        if (!dbFile.exists()) {
            result.put("skipped", "data_v4.db 不存在：" + dbFile.getAbsolutePath());
            return result;
        }

        try (Connection conn = DriverManager.getConnection("jdbc:sqlite:" + dbFile.getAbsolutePath())) {
            conn.setAutoCommit(false);
            int conversations = deleteMatching(conn, "conversations",
                    "SELECT conversation_id, user_id FROM conversations WHERE platform_id = 'webchat'",
                    "conversation_id", "user_id", conversationId);
            int messages = deleteMatching(conn, "platform_message_history",
                    "SELECT DISTINCT user_id FROM platform_message_history WHERE platform_id = 'webchat'",
                    null, "user_id", conversationId);
            int sessions = deleteMatching(conn, "platform_sessions",
                    "SELECT DISTINCT session_id FROM platform_sessions WHERE platform_id = 'webchat'",
                    null, "session_id", conversationId);
            conn.commit();

            result.put("conversationsDeleted", conversations);
            result.put("historyDeleted", messages);
            result.put("sessionsDeleted", sessions);
            log.info("已清理 AstrBot 会话记忆 conversationId={} → conversations={}, history={}, sessions={}",
                    conversationId, conversations, messages, sessions);
        } catch (Exception e) {
            log.warn("清理 AstrBot 会话记忆失败（不影响删除对话本身）conversationId={}: {}", conversationId, e.getMessage());
            result.put("error", e.getMessage());
        }
        return result;
    }

    /**
     * 扫描一张表，把 session 属于该对话的行删掉。
     *
     * @param selectSql     查询语句，至少返回 {@code keyColumn}，session 在 {@code sessionColumn}
     * @param deleteColumn  要删除的列名；为 null 表示用 {@code sessionColumn}
     * @param sessionColumn 取 session 值的列名
     * @param distinct      查询是否已经 DISTINCT（决定去重写法）
     */
    private int deleteMatching(Connection conn, String table, String selectSql, String deleteColumn,
                               String sessionColumn, String conversationId) throws SQLException {
        List<String> keys = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(selectSql);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                String raw = rs.getString(sessionColumn);
                // conversations.user_id 是完整 umo（webchat:FriendMessage:webchat!用户!session），
                // 取最后一段才是 session；平台历史/会话表里存的本身就是 session。
                String session = raw == null ? null : raw.substring(raw.lastIndexOf('!') + 1);
                if (!belongsToConversation(session, conversationId)) continue;
                keys.add(deleteColumn == null ? session : rs.getString(deleteColumn));
            }
        } catch (SQLException e) {
            // 老版本 AstrBot 可能没有这张表/列：跳过即可
            log.debug("跳过记忆清理查询（表不存在或无该列）: {}", e.getMessage());
            return 0;
        }
        if (keys.isEmpty()) return 0;

        String column = deleteColumn == null ? sessionColumn : deleteColumn;
        // 必须带 platform_id 条件：同一 session 值可能在别的平台（如 telegram）也有一行
        String sql = "DELETE FROM " + table + " WHERE platform_id = 'webchat' AND " + column + " = ?";
        int deleted = 0;
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            for (String key : keys) {
                ps.setString(1, key);
                deleted += ps.executeUpdate();
            }
        }
        return deleted;
    }
}

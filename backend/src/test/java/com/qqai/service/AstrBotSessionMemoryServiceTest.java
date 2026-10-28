package com.qqai.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 会话记忆键与级联清理测试。
 *
 * <p>锁两件事：①session 键必须「按对话 + 人格」且能精确判定归属（不能靠 LIKE 误伤
 * {@code conv_1} 与 {@code conv_1x}）；②删对话时 AstrBot 侧的会话/历史/平台会话行被一起删掉。</p>
 */
class AstrBotSessionMemoryServiceTest {

    @Test
    @DisplayName("session 键：按对话，可选人格后缀")
    void buildsSessionId() {
        assertEquals("qqai-conv_abc", AstrBotSessionMemoryService.buildSessionId("conv_abc", null));
        assertEquals("qqai-conv_abc", AstrBotSessionMemoryService.buildSessionId("conv_abc", "  "));
        assertEquals("qqai-conv_abc@ray", AstrBotSessionMemoryService.buildSessionId("conv_abc", "ray"));
        assertNull(AstrBotSessionMemoryService.buildSessionId(null, "ray"));
    }

    @Test
    @DisplayName("人格键：ASCII 走 slug，中文回落短哈希（不再所有人格共用 @）")
    void personaKeyFallsBackToHash() {
        // slug 自带短哈希后缀（形如 ray_1b82a），避免不同人格撞名
        assertTrue(AstrBotSessionMemoryService.personaKey("ray").startsWith("ray_"));
        String cn = AstrBotSessionMemoryService.personaKey("灰泽满");
        assertNotNull(cn);
        assertTrue(cn.startsWith("p_"), "中文人格的 slug 应为 p_<hex>，实际=" + cn);
        assertFalse(cn.isBlank());
        assertNull(AstrBotSessionMemoryService.personaKey(null));
    }

    @Test
    @DisplayName("归属判定：精确匹配对话，前缀相似的其它对话/其它会话不算")
    void belongsToConversation() {
        assertTrue(AstrBotSessionMemoryService.belongsToConversation("qqai-conv_abc", "conv_abc"));
        assertTrue(AstrBotSessionMemoryService.belongsToConversation("qqai-conv_abc@ray", "conv_abc"));
        assertFalse(AstrBotSessionMemoryService.belongsToConversation("qqai-conv_abcd", "conv_abc"));
        assertFalse(AstrBotSessionMemoryService.belongsToConversation("qqai-conv_abcX@ray", "conv_abc"));
        assertFalse(AstrBotSessionMemoryService.belongsToConversation("qqai-conv_abc@ray", "conv_ab"));
        assertFalse(AstrBotSessionMemoryService.belongsToConversation("group123@ray", "conv_abc"));
        assertFalse(AstrBotSessionMemoryService.belongsToConversation(null, "conv_abc"));
    }

    @Test
    @DisplayName("删对话：只删该对话的 conversations / 历史 / 平台会话行")
    void purgeDeletesOnlyMatchingConversation(@TempDir Path dir) throws Exception {
        Path db = dir.resolve("data_v4.db");
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + db.toAbsolutePath());
             Statement st = c.createStatement()) {
            st.execute("CREATE TABLE conversations (conversation_id TEXT, platform_id TEXT, user_id TEXT)");
            st.execute("CREATE TABLE platform_message_history (platform_id TEXT, user_id TEXT, content TEXT)");
            st.execute("CREATE TABLE platform_sessions (session_id TEXT, platform_id TEXT)");

            st.execute("INSERT INTO conversations VALUES ('c1','webchat','webchat:FriendMessage:webchat!u1!qqai-conv_abc')");
            st.execute("INSERT INTO conversations VALUES ('c2','webchat','webchat:FriendMessage:webchat!u1!qqai-conv_abc@p1')");
            st.execute("INSERT INTO conversations VALUES ('c3','webchat','webchat:FriendMessage:webchat!u1!qqai-conv_abcd')");
            st.execute("INSERT INTO conversations VALUES ('c4','telegram','tg:!qqai-conv_abc')");

            st.execute("INSERT INTO platform_message_history VALUES ('webchat','qqai-conv_abc','hi')");
            st.execute("INSERT INTO platform_message_history VALUES ('webchat','qqai-conv_abc@p1','hi2')");
            st.execute("INSERT INTO platform_message_history VALUES ('webchat','qqai-conv_abcd','other')");
            st.execute("INSERT INTO platform_message_history VALUES ('telegram','qqai-conv_abc','tg')");

            st.execute("INSERT INTO platform_sessions VALUES ('qqai-conv_abc','webchat')");
            st.execute("INSERT INTO platform_sessions VALUES ('qqai-conv_abc@p1','webchat')");
            st.execute("INSERT INTO platform_sessions VALUES ('qqai-conv_abcd','webchat')");
        }

        AstrBotSessionMemoryService service = new AstrBotSessionMemoryService(dir.toString());
        Map<String, Object> result = service.purgeConversation("conv_abc");

        assertEquals(2, result.get("conversationsDeleted"), "应删掉主会话与人格变体两行");
        assertEquals(2, result.get("historyDeleted"));
        assertEquals(2, result.get("sessionsDeleted"));
        assertNull(result.get("error"));

        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + db.toAbsolutePath());
             Statement st = c.createStatement()) {
            assertEquals(2, count(st, "SELECT COUNT(*) FROM conversations"));
            assertEquals(2, count(st, "SELECT COUNT(*) FROM platform_message_history"));
            assertEquals(1, count(st, "SELECT COUNT(*) FROM platform_sessions"));
            try (ResultSet rs = st.executeQuery("SELECT conversation_id FROM conversations ORDER BY conversation_id")) {
                rs.next();
                assertEquals("c3", rs.getString(1));
                rs.next();
                assertEquals("c4", rs.getString(1));
            }
        }
    }

    @Test
    @DisplayName("一次性 session：只删该 session 的行，长期会话不受影响")
    void purgeSessionDeletesExactSessionOnly(@TempDir Path dir) throws Exception {
        Path db = dir.resolve("data_v4.db");
        String sid = "qqai-11111111-2222-3333-4444-555555555555";
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + db.toAbsolutePath());
             Statement st = c.createStatement()) {
            st.execute("CREATE TABLE conversations (conversation_id TEXT, platform_id TEXT, user_id TEXT)");
            st.execute("CREATE TABLE platform_message_history (platform_id TEXT, user_id TEXT, content TEXT)");
            st.execute("CREATE TABLE platform_sessions (session_id TEXT, platform_id TEXT)");

            st.execute("INSERT INTO conversations VALUES ('c1','webchat','webchat:FriendMessage:webchat!summarizer!' || '" + sid + "')");
            st.execute("INSERT INTO conversations VALUES ('c2','webchat','webchat:FriendMessage:webchat!web_user!qqai-conv_abc')");
            st.execute("INSERT INTO platform_message_history VALUES ('webchat','" + sid + "','hi')");
            st.execute("INSERT INTO platform_message_history VALUES ('webchat','qqai-conv_abc','hi2')");
            st.execute("INSERT INTO platform_sessions VALUES ('" + sid + "','webchat')");
            st.execute("INSERT INTO platform_sessions VALUES ('qqai-conv_abc','webchat')");
        }

        Map<String, Object> result = new AstrBotSessionMemoryService(dir.toString()).purgeSession(sid);

        assertEquals(1, result.get("conversationsDeleted"));
        assertEquals(1, result.get("historyDeleted"));
        assertEquals(1, result.get("sessionsDeleted"));
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + db.toAbsolutePath());
             Statement st = c.createStatement()) {
            assertEquals(1, count(st, "SELECT COUNT(*) FROM conversations"));
            assertEquals(1, count(st, "SELECT COUNT(*) FROM platform_message_history"));
            assertEquals(1, count(st, "SELECT COUNT(*) FROM platform_sessions"));
        }
    }

    @Test
    @DisplayName("清理规则：摘要即时清；分析只清超 30 天；真实网页会话永不匹配")
    void purgeRules() {
        assertTrue(AstrBotSessionMemoryService.shouldPurgeSession("summarizer", false));
        assertTrue(AstrBotSessionMemoryService.shouldPurgeSession("summarizer", true));
        assertFalse(AstrBotSessionMemoryService.shouldPurgeSession("analyzer", false), "分析对话要保留（用户要求）");
        assertTrue(AstrBotSessionMemoryService.shouldPurgeSession("analyzer", true), "超过保留期才清");
        assertFalse(AstrBotSessionMemoryService.shouldPurgeSession("web_user", true));
        assertFalse(AstrBotSessionMemoryService.shouldPurgeSession(null, true));
    }

    @Test
    @DisplayName("清扫：摘要删、过期分析删、近期分析保留、真实网页会话保留")
    void purgeOneShotSessions(@TempDir Path dir) throws Exception {
        Path db = dir.resolve("data_v4.db");
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + db.toAbsolutePath());
             Statement st = c.createStatement()) {
            st.execute("CREATE TABLE conversations (conversation_id TEXT, platform_id TEXT, user_id TEXT, title TEXT, updated_at DATETIME)");
            st.execute("CREATE TABLE platform_message_history (platform_id TEXT, user_id TEXT, content TEXT)");
            st.execute("CREATE TABLE platform_sessions (session_id TEXT, platform_id TEXT)");

            st.execute("INSERT INTO conversations VALUES ('c1','webchat','webchat:FriendMessage:webchat!summarizer!d8c6291a-7013-41e0-bb9f-fc026e0ad2f4',NULL,datetime('now'))");
            st.execute("INSERT INTO conversations VALUES ('c2','webchat','webchat:FriendMessage:webchat!analyzer!qqai-99999999-8888-7777-6666-555555555555',NULL,datetime('now'))");
            st.execute("INSERT INTO conversations VALUES ('c3','webchat','webchat:FriendMessage:webchat!analyzer!qqai-88888888-7777-6666-5555-444444444444',NULL,datetime('now','-40 days'))");
            st.execute("INSERT INTO conversations VALUES ('c4','webchat','webchat:FriendMessage:webchat!web_user!qqai-conv_abc@p_1b3b5f4',NULL,datetime('now'))");
            st.execute("INSERT INTO conversations VALUES ('c5','webchat','webchat:FriendMessage:webchat!web_user!d0cf8da2-86e4-41d0-adf4-86e87b925b8b',NULL,datetime('now','-90 days'))");

            st.execute("INSERT INTO platform_message_history VALUES ('webchat','d8c6291a-7013-41e0-bb9f-fc026e0ad2f4','a')");
            st.execute("INSERT INTO platform_message_history VALUES ('webchat','qqai-99999999-8888-7777-6666-555555555555','b')");
            st.execute("INSERT INTO platform_message_history VALUES ('webchat','qqai-88888888-7777-6666-5555-444444444444','c')");
            st.execute("INSERT INTO platform_message_history VALUES ('webchat','qqai-conv_abc@p_1b3b5f4','d')");
            st.execute("INSERT INTO platform_sessions VALUES ('d8c6291a-7013-41e0-bb9f-fc026e0ad2f4','webchat')");
            st.execute("INSERT INTO platform_sessions VALUES ('qqai-88888888-7777-6666-5555-444444444444','webchat')");
            st.execute("INSERT INTO platform_sessions VALUES ('qqai-conv_abc@p_1b3b5f4','webchat')");
        }

        Map<String, Object> result = new AstrBotSessionMemoryService(dir.toString()).purgeOneShotSessions();

        assertEquals(2, result.get("conversationsDeleted"), "只删摘要 c1 与过期分析 c3");
        assertEquals(2, result.get("historyDeleted"));
        assertEquals(2, result.get("sessionsDeleted"));
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + db.toAbsolutePath());
             Statement st = c.createStatement()) {
            assertEquals(3, count(st, "SELECT COUNT(*) FROM conversations"), "近期分析 + 两条真实会话保留");
            assertEquals(2, count(st, "SELECT COUNT(*) FROM platform_message_history"));
            assertEquals(1, count(st, "SELECT COUNT(*) FROM platform_sessions"));
            try (ResultSet rs = st.executeQuery("SELECT conversation_id FROM conversations ORDER BY conversation_id")) {
                rs.next(); assertEquals("c2", rs.getString(1));
                rs.next(); assertEquals("c4", rs.getString(1));
                rs.next(); assertEquals("c5", rs.getString(1));
            }
        }
    }

    @Test
    @DisplayName("补标题：只写目标 session，不碰其它会话")
    void setTitleForSession(@TempDir Path dir) throws Exception {
        Path db = dir.resolve("data_v4.db");
        String sid = "qqai-11111111-2222-3333-4444-555555555555";
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + db.toAbsolutePath());
             Statement st = c.createStatement()) {
            st.execute("CREATE TABLE conversations (conversation_id TEXT, platform_id TEXT, user_id TEXT, title TEXT, updated_at DATETIME)");
            st.execute("INSERT INTO conversations VALUES ('c1','webchat','webchat:FriendMessage:webchat!analyzer!' || '" + sid + "',NULL,datetime('now'))");
            st.execute("INSERT INTO conversations VALUES ('c2','webchat','webchat:FriendMessage:webchat!web_user!qqai-conv_abc',NULL,datetime('now'))");
        }
        AstrBotSessionMemoryService service = new AstrBotSessionMemoryService(dir.toString());
        String title = "群分析 · 群聊速览 · 测试群 · 10-28 20:50";
        assertTrue(service.setTitleForSession(sid, title));
        assertFalse(service.setTitleForSession("not-exist-session", title));

        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + db.toAbsolutePath());
             Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT conversation_id, title FROM conversations ORDER BY conversation_id")) {
            rs.next(); assertEquals("c1", rs.getString(1)); assertEquals(title, rs.getString(2));
            rs.next(); assertEquals("c2", rs.getString(1)); assertNull(rs.getString(2));
        }
    }

    @Test
    @DisplayName("数据目录/库缺失时跳过，不抛异常")
    void purgeSkipsWhenDbMissing(@TempDir Path dir) {
        Map<String, Object> missing = new AstrBotSessionMemoryService(dir.toString()).purgeConversation("conv_x");
        assertEquals("data_v4.db 不存在：" + dir.resolve("data_v4.db"), missing.get("skipped"));

        Map<String, Object> blank = new AstrBotSessionMemoryService("").purgeConversation("conv_x");
        assertNotNull(blank.get("skipped"));
    }

    private int count(Statement st, String sql) throws Exception {
        try (ResultSet rs = st.executeQuery(sql)) {
            rs.next();
            return rs.getInt(1);
        }
    }
}

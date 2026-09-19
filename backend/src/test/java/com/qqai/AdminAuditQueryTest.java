package com.qqai;

import com.qqai.entity.AuditLog;
import com.qqai.repository.AuditLogRepository;
import com.qqai.service.AuditLogService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.test.annotation.DirtiesContext;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 审计日志查询测试（目标 / 结果 / 时间范围筛选）。
 *
 * 覆盖点：新增的 Specification 组合不能改变 keyword 的历史语义（匹配操作人而不是目标），
 * 空条件不能产生约束，时间范围按天含首尾。
 */
@SpringBootTest
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
class AdminAuditQueryTest {

    @Autowired
    private AuditLogService auditLogService;

    @Autowired
    private AuditLogRepository auditLogRepository;

    private final PageRequest firstPage = PageRequest.of(0, 50, Sort.by(Sort.Direction.DESC, "timestamp"));

    @BeforeEach
    void seed() {
        auditLogRepository.deleteAll();
        save("alice", "ROLE_CHANGE", "user:7", "SUCCESS", "提权", LocalDateTime.of(2026, 9, 1, 10, 0));
        save("bob", "USER_DELETE", "user:9", "FAILURE", "删除失败", LocalDateTime.of(2026, 9, 10, 10, 0));
        save("alice", "FILE_PURGE", "files:3", "SUCCESS", "清理文件", LocalDateTime.of(2026, 9, 19, 10, 0));
    }

    private void save(String username, String action, String target, String result, String detail, LocalDateTime ts) {
        AuditLog log = new AuditLog();
        log.setUsername(username);
        log.setAction(action);
        log.setTarget(target);
        log.setResult(result);
        log.setDetail(detail);
        log.setTimestamp(ts);
        auditLogRepository.save(log);
    }

    private List<AuditLog> query(String keyword, String action, String target, String result,
                                 LocalDateTime from, LocalDateTime to) {
        Page<AuditLog> page = auditLogService.getAuditLogs(keyword, action, target, result, from, to, firstPage);
        return page.getContent();
    }

    @Test
    @DisplayName("无筛选条件返回全部")
    void shouldReturnAllWithoutFilters() {
        assertEquals(3, query(null, null, null, null, null, null).size());
        assertEquals(3, query("   ", "", "  ", "", null, null).size());
    }

    @Test
    @DisplayName("keyword 仍然匹配操作人（不是目标）")
    void keywordMatchesUsernameOnly() {
        List<AuditLog> result = query("alice", null, null, null, null, null);
        assertEquals(2, result.size());
        assertTrue(result.stream().allMatch(l -> "alice".equals(l.getUsername())));

        // "user:9" 只出现在 target 里，keyword 不该命中
        assertTrue(query("user:9", null, null, null, null, null).isEmpty());
    }

    @Test
    @DisplayName("target 独立模糊匹配操作目标")
    void targetMatchesTargetField() {
        assertEquals(2, query(null, null, "user:", null, null, null).size());
        assertEquals(1, query(null, null, "files:", null, null, null).size());
    }

    @Test
    @DisplayName("action 与 result 精确匹配，result 忽略大小写")
    void actionAndResultFilters() {
        assertEquals(1, query(null, "USER_DELETE", null, null, null, null).size());
        assertEquals(1, query(null, null, null, "FAILURE", null, null).size());
        assertEquals(1, query(null, null, null, "failure", null, null).size());
        assertEquals(2, query(null, null, null, "SUCCESS", null, null).size());
        assertTrue(query(null, "NOT_EXIST", null, null, null, null).isEmpty());
    }

    @Test
    @DisplayName("时间范围含首尾当天")
    void dateRangeInclusive() {
        assertEquals(1, query(null, null, null, null,
                LocalDateTime.of(2026, 9, 1, 0, 0), LocalDateTime.of(2026, 9, 1, 23, 59, 59)).size());
        assertEquals(2, query(null, null, null, null,
                LocalDateTime.of(2026, 9, 10, 0, 0), LocalDateTime.of(2026, 9, 19, 23, 59, 59)).size());
        assertEquals(3, query(null, null, null, null,
                LocalDateTime.of(2026, 9, 1, 0, 0), LocalDateTime.of(2026, 9, 30, 23, 59, 59)).size());
    }

    @Test
    @DisplayName("多条件组合取交集")
    void combinedFilters() {
        List<AuditLog> result = query("alice", "FILE_PURGE", "files:", "SUCCESS",
                LocalDateTime.of(2026, 9, 1, 0, 0), LocalDateTime.of(2026, 9, 30, 23, 59, 59));
        assertEquals(1, result.size());
        assertEquals("alice", result.get(0).getUsername());

        assertTrue(query("bob", "ROLE_CHANGE", null, null, null, null).isEmpty());
    }

    @Test
    @DisplayName("结果按时间倒序")
    void sortedByTimestampDesc() {
        List<AuditLog> result = query(null, null, null, null, null, null);
        assertEquals("FILE_PURGE", result.get(0).getAction());
        assertEquals("USER_DELETE", result.get(1).getAction());
        assertEquals("ROLE_CHANGE", result.get(2).getAction());
    }
}

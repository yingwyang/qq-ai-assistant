package com.qqai.service;

import com.qqai.entity.AuditLog;
import com.qqai.repository.AuditLogRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

/**
 * 审计日志服务 - 记录关键管理操作并支持查询
 */
@Service
public class AuditLogService {

    private static final Logger log = LoggerFactory.getLogger(AuditLogService.class);

    @Autowired
    private AuditLogRepository auditLogRepository;

    /**
     * 记录一条审计日志。失败不影响主流程。
     */
    public void log(String username, String action, String target, String result, String detail) {
        try {
            AuditLog auditLog = new AuditLog();
            auditLog.setUsername(username);
            auditLog.setAction(action);
            auditLog.setTarget(target);
            auditLog.setResult(result);
            auditLog.setDetail(detail);
            auditLog.setTimestamp(LocalDateTime.now());
            auditLogRepository.save(auditLog);
        } catch (Exception e) {
            log.warn("写入审计日志失败: action={}, target={}, error={}", action, target, e.getMessage());
        }
    }

    /**
     * 分页查询审计日志。
     *
     * @param keyword  操作人关键字（可为空）
     * @param action   操作类型（可为空）
     * @param pageable 分页参数
     */
    public Page<AuditLog> getAuditLogs(String keyword, String action, Pageable pageable) {
        boolean hasKeyword = keyword != null && !keyword.isBlank();
        boolean hasAction = action != null && !action.isBlank();
        if (hasKeyword && hasAction) {
            return auditLogRepository.findByUsernameContainingAndAction(keyword, action, pageable);
        }
        if (hasKeyword) {
            return auditLogRepository.findByUsernameContaining(keyword, pageable);
        }
        if (hasAction) {
            return auditLogRepository.findByAction(action, pageable);
        }
        return auditLogRepository.findAllByOrderByTimestampDesc(pageable);
    }
}

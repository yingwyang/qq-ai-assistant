package com.qqai.service;

import com.qqai.entity.AuditLog;
import com.qqai.repository.AuditLogRepository;
import jakarta.persistence.criteria.Predicate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

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
        return getAuditLogs(keyword, action, null, null, null, null, pageable);
    }

    /**
     * 带目标 / 结果 / 时间范围的审计日志查询。
     *
     * <p>逐项组合筛选会带来 2^5 种派生方法组合，这里改用 Specification 统一组装；
     * keyword 的语义保持历史行为（匹配 username 操作人），target 是独立的模糊匹配字段。</p>
     *
     * @param keyword 操作人关键字（匹配 username，可为空）
     * @param action  操作类型（精确匹配，可为空）
     * @param target  操作目标关键字（匹配 target，可为空）
     * @param result  操作结果 SUCCESS / FAILURE（精确匹配，可为空）
     * @param from    起始时间（含，可为空）
     * @param to      结束时间（含，可为空）
     */
    public Page<AuditLog> getAuditLogs(String keyword, String action, String target, String result,
                                      LocalDateTime from, LocalDateTime to, Pageable pageable) {
        final String kw = trimToNull(keyword);
        final String act = trimToNull(action);
        final String tgt = trimToNull(target);
        final String res = trimToNull(result);

        Specification<AuditLog> spec = (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            if (kw != null) {
                predicates.add(cb.like(cb.lower(root.get("username")), "%" + kw.toLowerCase() + "%"));
            }
            if (act != null) {
                predicates.add(cb.equal(root.get("action"), act));
            }
            if (tgt != null) {
                predicates.add(cb.like(cb.lower(root.get("target")), "%" + tgt.toLowerCase() + "%"));
            }
            if (res != null) {
                predicates.add(cb.equal(cb.upper(root.get("result")), res.toUpperCase()));
            }
            if (from != null) {
                predicates.add(cb.greaterThanOrEqualTo(root.get("timestamp"), from));
            }
            if (to != null) {
                predicates.add(cb.lessThanOrEqualTo(root.get("timestamp"), to));
            }
            return predicates.isEmpty() ? cb.conjunction() : cb.and(predicates.toArray(new Predicate[0]));
        };

        return auditLogRepository.findAll(spec, pageable);
    }

    private static String trimToNull(String value) {
        if (value == null) return null;
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}

package com.qqai.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.qqai.common.SecurityHelper;
import com.qqai.dto.common.ApiResponse;
import com.qqai.entity.CreditRule;
import com.qqai.entity.CreditTransaction;
import com.qqai.entity.User;
import com.qqai.entity.UserCredit;
import com.qqai.entity.enums.CreditDirection;
import com.qqai.entity.enums.CreditTransactionType;
import com.qqai.exception.BizException;
import com.qqai.repository.UserCreditRepository;
import com.qqai.repository.UserRepository;
import com.qqai.service.AuditLogService;
import com.qqai.service.CreditRuleService;
import com.qqai.service.CreditService;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@RestController
@RequestMapping("/api/credits/admin")
public class AdminCreditsController {

    private static final Logger log = LoggerFactory.getLogger(AdminCreditsController.class);

    @Autowired
    private SecurityHelper securityHelper;

    @Autowired
    private CreditRuleService creditRuleService;

    @Autowired
    private CreditService creditService;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private UserCreditRepository userCreditRepository;

    @Autowired(required = false)
    private AuditLogService auditLogService;

    /** 模拟现金账：按规则折算现金收支 + 汇总聚合 */
    @Autowired
    private com.qqai.service.CashLedgerService cashLedgerService;

    @Autowired
    private com.qqai.repository.CreditTransactionRepository creditTransactionRepository;

    private final ObjectMapper objectMapper = new ObjectMapper()
            .registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule())
            .disable(com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    // ==================== 模拟现金账 ====================

    /**
     * 现金收支汇总（含按日趋势、按类别构成、按类型明细），给后台图表用。
     *
     * @param days 未显式给 start/end 时，默认看最近多少天（1–365）
     */
    @GetMapping("/cash/summary")
    public ResponseEntity<ApiResponse<Map<String, Object>>> cashSummary(
            @RequestParam(required = false) String start,
            @RequestParam(required = false) String end,
            @RequestParam(defaultValue = "30") int days) {
        securityHelper.requireAdmin();
        LocalDate to = end != null && !end.isBlank() ? LocalDate.parse(end) : LocalDate.now();
        LocalDate from = start != null && !start.isBlank()
                ? LocalDate.parse(start)
                : to.minusDays(Math.max(0, Math.min(days, 365) - 1));

        List<CreditTransaction> rows = loadCashWindow(from, to);
        Map<String, Object> summary = cashLedgerService.summarize(rows, from, to);
        summary.put("truncated", rows.size() >= MAX_CASH_ROWS);
        return ResponseEntity.ok(ApiResponse.success(summary));
    }

    /** 汇总窗口内最多取多少条流水（防止一次拉全表） */
    private static final int MAX_CASH_ROWS = 20000;

    private List<CreditTransaction> loadCashWindow(LocalDate from, LocalDate to) {
        LocalDateTime startDt = from.atStartOfDay();
        LocalDateTime endDt = to.atTime(LocalTime.MAX);
        List<CreditTransaction> rows =
                creditTransactionRepository.findByCreatedAtBetweenOrderByCreatedAtAsc(startDt, endDt);
        if (rows.size() > MAX_CASH_ROWS) {
            log.warn("现金汇总窗口内流水过多({} 条)，只取前 {} 条", rows.size(), MAX_CASH_ROWS);
            return rows.subList(rows.size() - MAX_CASH_ROWS, rows.size());
        }
        return rows;
    }

    /**
     * 手工记一笔现金收支（<b>模拟</b>）：写入一条 {@code CASH_INCOME}/{@code CASH_EXPENSE} 流水。
     *
     * <p>积分不变（amount=0），只有现金列有值；可选关联到某个用户，便于在用户维度对账。</p>
     */
    @PostMapping("/cash")
    public ResponseEntity<ApiResponse<Map<String, Object>>> createCashEntry(@RequestBody Map<String, Object> body) {
        securityHelper.requireAdmin();
        String direction = String.valueOf(body.getOrDefault("direction", "IN")).toUpperCase();
        if (!"IN".equals(direction) && !"OUT".equals(direction)) {
            throw new BizException(400, "direction 只能是 IN 或 OUT");
        }
        BigDecimal amount = parseCashAmount(body.get("amount"));
        if (amount.signum() <= 0) throw new BizException(400, "金额必须大于 0");
        if (amount.compareTo(new BigDecimal("9999999")) > 0) throw new BizException(400, "金额过大");

        String remark = body.get("remark") == null ? "" : String.valueOf(body.get("remark")).trim();
        String category = body.get("category") == null ? com.qqai.service.CashLedgerService.CATEGORY_MANUAL
                : String.valueOf(body.get("category")).trim().toUpperCase();
        Long userId = body.get("userId") == null || String.valueOf(body.get("userId")).isBlank()
                ? null : Long.valueOf(String.valueOf(body.get("userId")));
        if (userId != null && userRepository.findById(userId).isEmpty()) {
            throw new BizException(404, "用户不存在: " + userId);
        }

        CreditTransaction tx = new CreditTransaction();
        tx.setUserId(userId == null ? 0L : userId);   // 0 = 全站级记账，不对应具体用户
        tx.setType("IN".equals(direction)
                ? CreditTransactionType.CASH_INCOME : CreditTransactionType.CASH_EXPENSE);
        tx.setDirection("IN".equals(direction) ? CreditDirection.IN : CreditDirection.OUT);
        tx.setAmount(0);
        tx.setBalanceAfter(userId == null ? 0
                : userCreditRepository.findByUserId(userId).map(UserCredit::getBalance).orElse(0));
        tx.setCashAmount("IN".equals(direction) ? amount : amount.negate());
        tx.setCashCategory(category);
        tx.setRemark("【手工记账】" + (remark.isEmpty() ? com.qqai.service.CashLedgerService.categoryLabel(category) : remark));
        tx.setAdminUserId(securityHelper.getCurrentUserId());
        CreditTransaction saved = creditTransactionRepository.save(tx);
        if (auditLogService != null) {
            auditLogService.log(securityHelper.getCurrentUsername(), "CASH_MANUAL_ENTRY", "credits",
                    "SUCCESS", "方向=" + direction + " 金额=" + amount + " 备注=" + remark);
        }
        log.info("管理员手工记账（模拟）: id={}, {} {} 元, 类别={}, 备注={}",
                saved.getId(), direction, amount, category, remark);
        Map<String, Object> data = new HashMap<>();
        data.put("id", saved.getId());
        data.put("direction", direction);
        data.put("amount", amount);
        data.put("cashAmount", saved.getCashAmount());
        data.put("category", category);
        return ResponseEntity.ok(ApiResponse.success(data));
    }

    private static BigDecimal parseCashAmount(Object raw) {
        if (raw == null) throw new BizException(400, "金额不能为空");
        try {
            return new BigDecimal(String.valueOf(raw)).setScale(2, java.math.RoundingMode.HALF_UP);
        } catch (Exception e) {
            throw new BizException(400, "金额格式不正确: " + raw);
        }
    }

    @GetMapping("/rule")
    public ResponseEntity<ApiResponse<CreditRule>> getRule() {
        securityHelper.requireAdmin();
        CreditRule rule = creditRuleService.getRule();
        return ResponseEntity.ok(ApiResponse.success(rule));
    }

    /**
     * 出厂默认规则（后台「恢复默认值」用；只读，不落库）。
     * 与 {@code CreditRuleService#buildDefaultRule} 同一份定义，前端不再各自维护一套默认值。
     */
    @GetMapping("/rule/defaults")
    public ResponseEntity<ApiResponse<CreditRule>> getDefaultRule() {
        securityHelper.requireAdmin();
        return ResponseEntity.ok(ApiResponse.success(creditRuleService.defaultRule()));
    }

    /**
     * 保存积分规则。
     *
     * <p>校验统一放在 {@code CreditRuleService.validate()}（抛 400 BizException）：
     * 之前这里是「controller 里一堆 if + BizException(String)」，而 {@code BizException(String)}
     * 的默认 code 是 500 —— 管理员填错一个折扣会看到「服务器错误」。现在只保留 service 这一层，
     * 文案与状态码集中在一处，任何调用方都绕不过去。</p>
     */
    @PutMapping("/rule")
    public ResponseEntity<ApiResponse<CreditRule>> updateRule(@RequestBody CreditRule updates) {
        securityHelper.requireAdmin();
        CreditRule saved = creditRuleService.setRule(updates);
        creditRuleService.evictCache();

        Long adminUserId = securityHelper.getCurrentUserId();
        String adminUsername = securityHelper.getCurrentUsername();
        log.info("管理员{}({}) 更新积分规则", adminUsername, adminUserId);
        if (auditLogService != null) {
            auditLogService.log(adminUsername, "CREDIT_RULE_UPDATE", "credit-rule:1", "SUCCESS", "规则已更新");
        }
        return ResponseEntity.ok(ApiResponse.success(saved));
    }

    @GetMapping("/user-credits")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getUserCredits(
            @RequestParam(required = false) String keyword,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        securityHelper.requireAdmin();
        Pageable pageable = PageRequest.of(page, Math.min(size, 200));
        Page<User> userPage;
        if (keyword != null && !keyword.isBlank()) {
            userPage = userRepository.findByUsernameContainingOrNicknameContaining(keyword, keyword, pageable);
        } else {
            userPage = userRepository.findAll(pageable);
        }

        List<Map<String, Object>> content = new ArrayList<>();
        for (User user : userPage.getContent()) {
            Map<String, Object> row = new HashMap<>();
            row.put("userId", user.getId());
            row.put("username", user.getUsername());
            row.put("nickname", user.getNickname());
            row.put("role", user.getRole());
            row.put("active", user.isActive());

            Optional<UserCredit> creditOpt = userCreditRepository.findByUserId(user.getId());
            if (creditOpt.isPresent()) {
                UserCredit c = creditOpt.get();
                row.put("balance", c.getBalance());
                row.put("totalEarned", c.getTotalEarned());
                row.put("totalSpent", c.getTotalSpent());
                row.put("consumptionBanned", c.getConsumptionBanned());
                row.put("subscriptionTier", c.getSubscriptionTier() != null ? c.getSubscriptionTier().name() : "FREE");
                row.put("subscriptionExpiresAt", c.getSubscriptionExpiresAt());
            } else {
                row.put("balance", 0);
                row.put("totalEarned", 0);
                row.put("totalSpent", 0);
                row.put("consumptionBanned", false);
                row.put("subscriptionTier", "FREE");
                row.put("subscriptionExpiresAt", null);
            }
            content.add(row);
        }

        Map<String, Object> pageData = new HashMap<>();
        pageData.put("content", content);
        pageData.put("totalElements", userPage.getTotalElements());
        pageData.put("totalPages", userPage.getTotalPages());
        pageData.put("number", userPage.getNumber());
        pageData.put("size", userPage.getSize());
        pageData.put("first", userPage.isFirst());
        pageData.put("last", userPage.isLast());
        return ResponseEntity.ok(ApiResponse.success(pageData));
    }

    /**
     * 管理员调账入口：支持正数发放 / 负数扣减，记录操作人 adminUserId 与审计日志。
     * 权限：securityHelper.requireAdminUserId() 强制校验管理员身份并返回操作人 ID。
     * 委托：实际调账逻辑在 CreditService.adminAdjust 内（行锁 + 流水 + 审计字段）。
     * 审计：成功后写 AuditLog（CREDIT_ADJUST），失败由 BizException 抛出不会进入审计分支。
     */
    @PostMapping("/adjust")
    public ResponseEntity<ApiResponse<Map<String, Object>>> adjust(@RequestBody Map<String, Object> body) {
        Long adminUserId = securityHelper.requireAdminUserId();
        Number userIdNum = (Number) body.get("userId");
        Number amountNum = (Number) body.get("amount");
        String reason = (String) body.get("reason");
        if (userIdNum == null) throw new BizException("userId不能为空");
        if (amountNum == null) throw new BizException("amount不能为空");
        Long userId = userIdNum.longValue();
        int amount = amountNum.intValue();

        if (!userRepository.existsById(userId)) {
            throw new BizException("目标用户不存在");
        }

        CreditTransaction tx = creditService.adminAdjust(userId, amount, reason, adminUserId);
        UserCredit after = creditService.getBalanceWithTier(userId);

        Map<String, Object> data = new HashMap<>();
        data.put("transactionId", tx.getId());
        data.put("userId", userId);
        data.put("amount", amount);
        data.put("type", tx.getType() != null ? tx.getType().name() : null);
        data.put("newBalance", after.getBalance());

        String adminUsername = securityHelper.getCurrentUsername();
        log.info("管理员{}({}) 调整用户{}积分: {}", adminUsername, adminUserId, userId, amount);
        if (auditLogService != null) {
            auditLogService.log(adminUsername, "CREDIT_ADJUST",
                    "user:" + userId + "|tx:" + tx.getId(), "SUCCESS",
                    "amount=" + amount + " reason=" + reason);
        }
        return ResponseEntity.ok(ApiResponse.success(data));
    }

    @GetMapping("/transactions")
    public void getTransactionsAdmin(
            @RequestParam(required = false) Long userId,
            @RequestParam(required = false) String type,
            @RequestParam(required = false) String direction,
            @RequestParam(required = false) String start,
            @RequestParam(required = false) String end,
            @RequestParam(required = false) Long min,
            @RequestParam(required = false) Long max,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String relatedId,
            @RequestParam(required = false) String export,
            HttpServletResponse response) throws Exception {
        securityHelper.requireAdmin();

        CreditTransactionType txType = parseTxType(type);
        CreditDirection dir = parseDirection(direction);
        LocalDateTime startDt = parseStart(start);
        LocalDateTime endDt = parseEnd(end);
        Pageable pageable = PageRequest.of(page, Math.min(size, 500));

        if ("1".equals(export) || "true".equalsIgnoreCase(export)) {
            List<CreditTransaction> all = new ArrayList<>();
            int fetchPage = 0;
            while (true) {
                Page<CreditTransaction> p = creditService.pageTransactionsAdmin(
                        userId, txType, dir, startDt, endDt, min, max, relatedId,
                        PageRequest.of(fetchPage, 500));
                all.addAll(p.getContent());
                if (p.isLast() || all.size() >= 50000) break;
                fetchPage++;
            }
            String filename = "credit-transactions-" + LocalDate.now() + ".json";
            response.setContentType(MediaType.APPLICATION_JSON_VALUE + ";charset=UTF-8");
            response.setHeader(HttpHeaders.CONTENT_DISPOSITION,
                    "attachment; filename*=UTF-8''" + URLEncoder.encode(filename, StandardCharsets.UTF_8));
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(response.getOutputStream(), all);
            return;
        }

        Page<CreditTransaction> txPage = creditService.pageTransactionsAdmin(
                userId, txType, dir, startDt, endDt, min, max, relatedId, pageable);

        List<Map<String, Object>> content = new ArrayList<>();
        Map<Long, Map<String, Object>> cashIndex = cashLedgerService.enrich(txPage.getContent());
        for (CreditTransaction tx : txPage.getContent()) {
            content.add(txToMap(tx, cashIndex.get(tx.getId())));
        }
        Map<String, Object> pageData = new HashMap<>();
        pageData.put("content", content);
        pageData.put("totalElements", txPage.getTotalElements());
        pageData.put("totalPages", txPage.getTotalPages());
        pageData.put("number", txPage.getNumber());
        pageData.put("size", txPage.getSize());
        pageData.put("first", txPage.isFirst());
        pageData.put("last", txPage.isLast());
        pageData.put("summary", creditService.summaryTransactionsAdmin(
                userId, txType, dir, startDt, endDt, min, max, relatedId));

        ApiResponse<Map<String, Object>> resp = ApiResponse.success(pageData);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE + ";charset=UTF-8");
        objectMapper.writeValue(response.getOutputStream(), resp);
    }

    private Map<String, Object> txToMap(CreditTransaction tx, Map<String, Object> cashInfo) {
        Map<String, Object> m = new HashMap<>();
        m.put("id", tx.getId());
        m.put("userId", tx.getUserId());
        m.put("type", tx.getType() != null ? tx.getType().name() : null);
        m.put("direction", tx.getDirection() != null ? tx.getDirection().name() : null);
        m.put("amount", tx.getAmount());
        m.put("balanceAfter", tx.getBalanceAfter());
        m.put("remark", tx.getRemark());
        m.put("relatedId", tx.getRelatedId());
        m.put("adminUserId", tx.getAdminUserId());
        m.put("createdAt", tx.getCreatedAt());
        // 模拟现金：金额（元，带符号）+ 类别（前端表格/图表直接用）
        if (cashInfo != null) {
            m.put("cashAmount", cashInfo.get("cashAmount"));
            m.put("cashCategory", cashInfo.get("cashCategory"));
            m.put("cashCategoryLabel", cashInfo.get("cashCategoryLabel"));
        } else {
            m.put("cashAmount", null);
            m.put("cashCategory", null);
            m.put("cashCategoryLabel", null);
        }
        return m;
    }

    /** 兼容旧调用（导出等场景只需积分字段） */
    private Map<String, Object> txToMap(CreditTransaction tx) {
        return txToMap(tx, null);
    }

    private CreditTransactionType parseTxType(String type) {
        if (type == null || type.isBlank()) return null;
        try { return CreditTransactionType.valueOf(type); }
        catch (Exception ignore) { return null; }
    }

    private CreditDirection parseDirection(String direction) {
        if (direction == null || direction.isBlank()) return null;
        try { return CreditDirection.valueOf(direction); }
        catch (Exception ignore) { return null; }
    }

    private LocalDateTime parseStart(String s) {
        if (s == null || s.isBlank()) return null;
        try {
            if (s.length() <= 10) return LocalDate.parse(s, DateTimeFormatter.ISO_LOCAL_DATE).atStartOfDay();
            return LocalDateTime.parse(s, DateTimeFormatter.ISO_LOCAL_DATE_TIME);
        } catch (Exception e) { return null; }
    }

    private LocalDateTime parseEnd(String s) {
        if (s == null || s.isBlank()) return null;
        try {
            if (s.length() <= 10) return LocalDate.parse(s, DateTimeFormatter.ISO_LOCAL_DATE).atTime(LocalTime.MAX);
            return LocalDateTime.parse(s, DateTimeFormatter.ISO_LOCAL_DATE_TIME);
        } catch (Exception e) { return null; }
    }
}

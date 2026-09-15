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

    private final ObjectMapper objectMapper = new ObjectMapper()
            .registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule())
            .disable(com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    @GetMapping("/rule")
    public ResponseEntity<ApiResponse<CreditRule>> getRule() {
        securityHelper.requireAdmin();
        CreditRule rule = creditRuleService.getRule();
        return ResponseEntity.ok(ApiResponse.success(rule));
    }

    @PutMapping("/rule")
    public ResponseEntity<ApiResponse<CreditRule>> updateRule(@RequestBody CreditRule updates) {
        securityHelper.requireAdmin();
        if (updates.getNewUserBonus() != null && updates.getNewUserBonus() < 0)
            throw new BizException("新人奖励不能为负");
        if (updates.getSignInPoints() != null && updates.getSignInPoints() < 0)
            throw new BizException("签到积分不能为负");
        if (updates.getTokenUnit() != null && updates.getTokenUnit() <= 0)
            throw new BizException("tokenUnit必须大于0");
        if (updates.getPromptRate() != null && updates.getPromptRate() < 0)
            throw new BizException("promptRate不能为负");
        if (updates.getCompletionRate() != null && updates.getCompletionRate() < 0)
            throw new BizException("completionRate不能为负");
        if (updates.getMinCost() != null && updates.getMinCost() < 0)
            throw new BizException("minCost不能为负");
        if (updates.getDefaultCostPerMsg() != null && updates.getDefaultCostPerMsg() < 0)
            throw new BizException("defaultCostPerMsg不能为负");
        if (updates.getPlanLitePrice() != null && updates.getPlanLitePrice().compareTo(BigDecimal.ZERO) < 0)
            throw new BizException("套餐价格不能为负");
        if (updates.getPlanProPrice() != null && updates.getPlanProPrice().compareTo(BigDecimal.ZERO) < 0)
            throw new BizException("套餐价格不能为负");
        if (updates.getPlanProPlusPrice() != null && updates.getPlanProPlusPrice().compareTo(BigDecimal.ZERO) < 0)
            throw new BizException("套餐价格不能为负");
        if (updates.getPlanUltraPrice() != null && updates.getPlanUltraPrice().compareTo(BigDecimal.ZERO) < 0)
            throw new BizException("套餐价格不能为负");
        if (updates.getPlanLiteCredit() != null && updates.getPlanLiteCredit() < 0)
            throw new BizException("套餐积分不能为负");
        if (updates.getPlanProCredit() != null && updates.getPlanProCredit() < 0)
            throw new BizException("套餐积分不能为负");
        if (updates.getPlanProPlusCredit() != null && updates.getPlanProPlusCredit() < 0)
            throw new BizException("套餐积分不能为负");
        if (updates.getPlanUltraCredit() != null && updates.getPlanUltraCredit() < 0)
            throw new BizException("套餐积分不能为负");
        if (updates.getPlanDurationDays() != null && updates.getPlanDurationDays() <= 0)
            throw new BizException("套餐时长必须大于0");

        // 精细化计费字段校验
        if (updates.getImageExtraCost() != null && updates.getImageExtraCost() < 0)
            throw new BizException("图片额外费用不能为负");
        if (updates.getAnalyzeBaseCost() != null && updates.getAnalyzeBaseCost() < 0)
            throw new BizException("分析基础费用不能为负");
        if (updates.getAnalyzeCostPerMsg() != null && updates.getAnalyzeCostPerMsg() < 0)
            throw new BizException("分析每条消息费用不能为负");
        if (updates.getTtsCharsPerCredit() != null && updates.getTtsCharsPerCredit() <= 0)
            throw new BizException("TTS字符数必须大于0");
        if (updates.getTtsMinCost() != null && updates.getTtsMinCost() < 0)
            throw new BizException("TTS最小费用不能为负");
        if (updates.getMonthlyFreeQuota() != null && updates.getMonthlyFreeQuota() < 0)
            throw new BizException("月度免费配额不能为负");
        if (updates.getOvertaxRate() != null && updates.getOvertaxRate() < 1.0)
            throw new BizException("超配额倍率不能小于1.0");
        // 月卡折扣校验（0.01~1.0 之间，>1 没有意义）
        double d = 1.0;
        if (updates.getSmallMonthCardDiscount() != null) {
            d = updates.getSmallMonthCardDiscount();
            if (d < 0.01 || d > 1.0) throw new BizException("小月卡折扣必须在 0.01~1.0 之间");
        }
        if (updates.getLargeMonthCardDiscount() != null) {
            d = updates.getLargeMonthCardDiscount();
            if (d < 0.01 || d > 1.0) throw new BizException("大月卡折扣必须在 0.01~1.0 之间");
        }
        if (updates.getAllTierDiscount() != null) {
            d = updates.getAllTierDiscount();
            if (d < 0.01 || d > 1.0) throw new BizException("ALL状态折扣必须在 0.01~1.0 之间");
        }
        // 上下文长度校验
        if (updates.getContextExtraCostPerMsg() != null && updates.getContextExtraCostPerMsg() < 0)
            throw new BizException("上下文每条费用不能为负");
        if (updates.getContextFreeMsgCount() != null && updates.getContextFreeMsgCount() < 0)
            throw new BizException("上下文免费条数不能为负");
        // 每日封顶校验（0=不限）
        if (updates.getDailyCapCost() != null && updates.getDailyCapCost() < 0)
            throw new BizException("每日封顶消耗不能为负");

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
        for (CreditTransaction tx : txPage.getContent()) {
            content.add(txToMap(tx));
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

    private Map<String, Object> txToMap(CreditTransaction tx) {
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
        return m;
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

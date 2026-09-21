package com.qqai.controller;

import com.qqai.common.SecurityHelper;
import com.qqai.dto.common.ApiResponse;
import com.qqai.entity.CreditTransaction;
import com.qqai.entity.SignInRecord;
import com.qqai.entity.UserCredit;
import com.qqai.entity.enums.CreditDirection;
import com.qqai.entity.enums.CreditTransactionType;
import com.qqai.entity.enums.SubscriptionTier;
import com.qqai.service.CreditService;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/credits")
public class CreditsController {

    private static final Logger log = LoggerFactory.getLogger(CreditsController.class);

    @Autowired
    private CreditService creditService;

    @Autowired
    private SecurityHelper securityHelper;

    @GetMapping("/balance")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getBalance() {
        Long userId = securityHelper.requireCurrentUserId();
        UserCredit account = creditService.getBalanceWithTier(userId);
        boolean todaySigned = creditService.hasSignedInToday(userId);
        int streakDays = creditService.getCurrentStreakDays(userId);

        Map<String, Object> data = new HashMap<>();
        data.put("balance", account.getBalance());
        data.put("totalEarned", account.getTotalEarned());
        data.put("totalSpent", account.getTotalSpent());
        data.put("todaySignInDone", todaySigned);
        data.put("streakDays", streakDays);
        // 每日签到可得积分 = 基础签到分 + 月卡每日额外积分（无月卡时为 0），供界面展示。
        // 注意：monthlyCardBonus 取"今日还可领"的口径（今日已发过则为 0），保证界面数字=实际到账；
        //       monthlyCardBonusTier 取档位口径（双持 = 小100+大300 = 400），
        //       用于在已发放时标注"含大小月卡额外 +400（今日已发放）"。
        int cardBonusTier = creditService.resolveActiveMonthlyCardBonus(userId);
        int cardBonusToday = creditService.todayMonthlyCardBonus(userId);
        int baseSignIn = creditService.getSafeSignInPoints();
        data.put("signInBasePoints", baseSignIn);
        data.put("monthlyCardBonus", cardBonusToday);
        data.put("monthlyCardBonusTier", cardBonusTier);
        data.put("monthlyCardTier", creditService.resolveActiveMonthlyCardCombo(userId));
        data.put("signInPoints", baseSignIn + cardBonusToday);
        data.put("subscriptionTier", account.getSubscriptionTier() != null
                ? account.getSubscriptionTier().name() : "FREE");
        data.put("subscriptionExpiresAt", account.getSubscriptionExpiresAt());
        return ResponseEntity.ok(ApiResponse.success(data));
    }

    @PostMapping("/sign-in")
    public ResponseEntity<ApiResponse<Map<String, Object>>> signIn() {
        Long userId = securityHelper.requireCurrentUserId();
        CreditService.SignInResult result = creditService.signInToday(userId);
        UserCredit after = creditService.getBalanceWithTier(userId);

        Map<String, Object> data = new HashMap<>();
        // points 为本次到账合计（基础签到分 + 月卡每日额外积分）
        data.put("points", result.getTotalPoints());
        data.put("basePoints", result.basePoints);
        data.put("monthlyCardBonus", result.monthlyCardBonus);
        data.put("streakDays", result.record.getStreakDays());
        data.put("newBalance", after.getBalance());
        return ResponseEntity.ok(ApiResponse.success(data));
    }

    @GetMapping("/sign-in/status")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getSignInStatus(
            @RequestParam(required = false, defaultValue = "7") String range) {
        Long userId = securityHelper.requireCurrentUserId();
        Map<String, Object> data = creditService.getSignInStatus(userId, range);
        return ResponseEntity.ok(ApiResponse.success(data));
    }

    @GetMapping("/transactions")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getTransactions(
            @RequestParam(required = false) String type,
            @RequestParam(required = false) String direction,
            @RequestParam(required = false) String start,
            @RequestParam(required = false) String end,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String relatedId) {
        Long userId = securityHelper.requireCurrentUserId();

        CreditTransactionType txType = null;
        if (type != null && !type.isBlank()) {
            try { txType = CreditTransactionType.valueOf(type); }
            catch (Exception ignore) {
                // 忽略：补偿逻辑失败不影响主流程
            }
        }
        CreditDirection dir = null;
        if (direction != null && !direction.isBlank()) {
            try { dir = CreditDirection.valueOf(direction); }
            catch (Exception ignore) {
                // 忽略：补偿逻辑失败不影响主流程
            }
        }
        LocalDateTime startDt = parseStart(start);
        LocalDateTime endDt = parseEnd(end);

        Pageable pageable = com.qqai.common.PageLimits.of(page, size);
        Page<CreditTransaction> txPage = creditService.pageTransactions(
                userId, txType, dir, startDt, endDt, relatedId, pageable);

        // 收入/支出合计合并为一条聚合 SQL（原先两次同条件扫描）
        long[] totals = creditService.sumIncomeAndSpend(userId, txType, dir, startDt, endDt, relatedId);
        long incomeTotal = totals[0];
        long spendTotal = totals[1];

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
        pageData.put("incomeTotal", incomeTotal);
        pageData.put("spendTotal", spendTotal);
        pageData.put("net", incomeTotal - spendTotal);
        return ResponseEntity.ok(ApiResponse.success(pageData));
    }

    @GetMapping("/trend")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> getTrend(
            @RequestParam(defaultValue = "7") int days) {
        Long userId = securityHelper.requireCurrentUserId();
        if (days <= 0) days = 7;
        if (days > 365) days = 365;
        Map<String, Object> trend = creditService.getTrend(userId, days);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> series = (List<Map<String, Object>>) trend.get("series");
        return ResponseEntity.ok(ApiResponse.success(series));
    }

    @GetMapping("/rewards")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> getRewards() {
        Long userId = securityHelper.requireCurrentUserId();
        Map<String, Object> rewards = creditService.getRewards(userId);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> items = (List<Map<String, Object>>) rewards.get("items");
        return ResponseEntity.ok(ApiResponse.success(items));
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

    private LocalDateTime parseStart(String s) {
        if (s == null || s.isBlank()) return null;
        try {
            if (s.length() <= 10) return LocalDate.parse(s, DateTimeFormatter.ISO_LOCAL_DATE).atStartOfDay();
            return LocalDateTime.parse(s, DateTimeFormatter.ISO_LOCAL_DATE_TIME);
        } catch (Exception e) {
            log.warn("积分流水起始时间解析失败，按不限制处理: value={}", s);
            return null;
        }
    }

    private LocalDateTime parseEnd(String s) {
        if (s == null || s.isBlank()) return null;
        try {
            if (s.length() <= 10) return LocalDate.parse(s, DateTimeFormatter.ISO_LOCAL_DATE).atTime(LocalTime.MAX);
            return LocalDateTime.parse(s, DateTimeFormatter.ISO_LOCAL_DATE_TIME);
        } catch (Exception e) {
            log.warn("积分流水结束时间解析失败，按不限制处理: value={}", s);
            return null;
        }
    }
}

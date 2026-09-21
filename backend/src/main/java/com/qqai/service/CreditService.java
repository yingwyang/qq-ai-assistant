package com.qqai.service;

import com.qqai.entity.CreditTransaction;
import com.qqai.entity.CreditRule;
import com.qqai.entity.SignInRecord;
import com.qqai.entity.SubscriptionOrder;
import com.qqai.entity.UserCredit;
import com.qqai.entity.enums.CreditDirection;
import com.qqai.entity.enums.CreditTransactionType;
import com.qqai.entity.enums.OrderStatus;
import com.qqai.entity.enums.SubscriptionTier;
import com.qqai.exception.BizException;
import com.qqai.exception.CreditErrorCode;
import com.qqai.repository.CreditTransactionRepository;
import com.qqai.repository.MonthlyBonusRecordRepository;
import com.qqai.repository.SignInRecordRepository;
import com.qqai.repository.SubscriptionOrderRepository;
import com.qqai.repository.UserCreditRepository;
import com.qqai.entity.MonthlyBonusRecord;
import jakarta.persistence.EntityManager;
import jakarta.persistence.TypedQuery;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigInteger;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Service
public class CreditService {

    private static final Logger log = LoggerFactory.getLogger(CreditService.class);

    private static final int DEFAULT_NEW_USER_BONUS = 500;
    private static final int DEFAULT_SIGN_IN_POINTS = 150;

    private static final List<SubscriptionTier> MONTHLY_CARD_TIERS = Arrays.asList(
            SubscriptionTier.SMALL_MONTH_CARD, SubscriptionTier.LARGE_MONTH_CARD);

    // 月卡每日签到额外积分（小月卡 / 大月卡 / 双持叠加）自 2026-09-19 起读「规则配置」表单，
    // 不再写死常量：见 CreditRule.planSmallMonthCardDailyBonus / planLargeMonthCardDailyBonus。

    @Autowired
    private UserCreditRepository userCreditRepository;

    @Autowired
    private CreditTransactionRepository creditTransactionRepository;

    @Autowired
    private SignInRecordRepository signInRecordRepository;

    @Autowired
    private SubscriptionOrderRepository subscriptionOrderRepository;

    @Autowired
    private MonthlyBonusRecordRepository monthlyBonusRecordRepository;

    @Autowired
    private CreditRuleService creditRuleService;

    @Autowired
    private EntityManager entityManager;

    /**
     * 确保用户积分账户存在；首次调用时初始化账户并发放新人积分。
     * 幂等：账户已存在则直接返回，不重复发新人积分。
     * 事务边界：账户初始化与新人积分流水在同一事务内落库，失败整体回滚。
     */
    @Transactional
    public UserCredit ensureAccount(Long userId) {
        Optional<UserCredit> existingOpt = userCreditRepository.findByUserId(userId);
        if (existingOpt.isPresent()) {
            return existingOpt.get();
        }
        UserCredit account = new UserCredit();
        account.setUserId(userId);
        account.setBalance(0);
        account.setTotalEarned(0);
        account.setTotalSpent(0);
        account.setConsumptionBanned(false);
        account.setSubscriptionTier(SubscriptionTier.FREE);
        UserCredit saved = userCreditRepository.save(account);

        Integer newUserBonus = safeNewUserBonus();
        if (newUserBonus > 0) {
            grantPointsInternal(saved, newUserBonus, CreditTransactionType.NEW_USER_BONUS, "新人注册赠送", null, null);
            saved = userCreditRepository.save(saved);
            log.info("用户{} 新人赠送积分: {}", userId, newUserBonus);
        }
        return saved;
    }

    /**
     * 发放积分（正数入账）：余额增加、累加 totalEarned，并写入 IN 方向流水。
     * 事务边界：余额更新与流水写入同事务，保证一致。
     * 并发安全：先对账户行加悲观锁（findByUserIdWithLock）再读改写，避免并发发放的丢更新
     * （账户带 @Version 时表现为 OptimisticLockingFailureException，用户侧看到 500）。
     */
    @Transactional
    public CreditTransaction grantPoints(Long userId, int amount, CreditTransactionType type, String remark, String relatedId, Long adminUserId) {
        if (amount <= 0) {
            throw new BizException("发放积分必须大于0");
        }
        UserCredit account = lockOrCreateAccount(userId);
        CreditTransaction tx = grantPointsInternal(account, amount, type, remark, relatedId, adminUserId);
        userCreditRepository.save(account);
        return tx;
    }

    /**
     * 取账户并加行锁；账户不存在时先初始化（ensureAccount 幂等）再重新加锁读取。
     * 说明：创建分支本身由 user_id 唯一约束兜底，极端并发创建失败会整体回滚，不会产生半成品账户。
     */
    private UserCredit lockOrCreateAccount(Long userId) {
        Optional<UserCredit> locked = userCreditRepository.findByUserIdWithLock(userId);
        if (locked.isPresent()) {
            return locked.get();
        }
        ensureAccount(userId);
        return userCreditRepository.findByUserIdWithLock(userId)
                .orElseThrow(() -> new BizException("积分账户初始化失败，请重试"));
    }

    private CreditTransaction grantPointsInternal(UserCredit account, int amount, CreditTransactionType type, String remark, String relatedId, Long adminUserId) {
        int newBalance = account.getBalance() + amount;
        account.setBalance(newBalance);
        account.setTotalEarned(account.getTotalEarned() + amount);

        CreditTransaction tx = new CreditTransaction();
        tx.setUserId(account.getUserId());
        tx.setType(type);
        tx.setDirection(CreditDirection.IN);
        tx.setAmount(amount);
        tx.setBalanceAfter(newBalance);
        tx.setRemark(remark);
        tx.setRelatedId(relatedId);
        tx.setAdminUserId(adminUserId);
        return creditTransactionRepository.save(tx);
    }

    /**
     * 扣减积分（消费）：行锁防透支，余额与 OUT 流水同事务落库。
     * 并发安全：通过 findByUserIdWithLock (SELECT ... FOR UPDATE) 串行化对同一账户的并发扣减，
     * 余额不足抛 INSUFFICIENT_CREDITS；账户被禁消费抛业务异常。
     * 设计理由：扣费是高并发关键路径，必须用悲观行锁避免超卖，与流水写入同事务保证可审计。
     */
    @Transactional
    public CreditTransaction spendPoints(Long userId, int amount, CreditTransactionType type, String remark, String relatedId) {
        if (amount <= 0) {
            throw new BizException("消耗积分必须大于0");
        }
        UserCredit account = userCreditRepository.findByUserIdWithLock(userId)
                .orElseThrow(() -> new BizException("积分账户不存在"));

        if (Boolean.TRUE.equals(account.getConsumptionBanned())) {
            throw new BizException("账户已被禁止消费");
        }

        int currentBalance = account.getBalance();
        if (currentBalance < amount) {
            throw new BizException(400, CreditErrorCode.INSUFFICIENT_CREDITS,
                    "积分不足，当前余额: " + currentBalance + "，需要: " + amount);
        }

        int newBalance = currentBalance - amount;
        account.setBalance(newBalance);
        account.setTotalSpent(account.getTotalSpent() + amount);

        CreditTransaction tx = new CreditTransaction();
        tx.setUserId(userId);
        tx.setType(type);
        tx.setDirection(CreditDirection.OUT);
        tx.setAmount(amount);
        tx.setBalanceAfter(newBalance);
        tx.setRemark(remark);
        tx.setRelatedId(relatedId);
        creditTransactionRepository.save(tx);
        userCreditRepository.save(account);
        return tx;
    }

    // ==================== 精细化统一扣费引擎 ====================

    /**
     * 统一扣费结果：封装扣费金额、扣费后余额、免费标记等，供 Controller 直接返回给前端。
     */
    public static class CreditCostResult {
        private final int cost;
        private final int balanceAfter;
        private final boolean adminFree;
        private final boolean quotaFree;
        private final String remark;

        public CreditCostResult(int cost, int balanceAfter, boolean adminFree, boolean quotaFree, String remark) {
            this.cost = cost;
            this.balanceAfter = balanceAfter;
            this.adminFree = adminFree;
            this.quotaFree = quotaFree;
            this.remark = remark;
        }

        public int getCost() { return cost; }
        public int getBalanceAfter() { return balanceAfter; }
        public boolean isAdminFree() { return adminFree; }
        public boolean isQuotaFree() { return quotaFree; }
        public String getRemark() { return remark; }
    }

    /**
     * 会话前置门禁（只读，不改余额）：在调用大模型之前判断「这次对话是否需要付费且余额不够」。
     *
     * 为什么需要：原实现是「先调 LLM、先落库回复、最后才扣费」，余额不足只在最后一步报错，
     * 于是 0 余额用户可以反复发消息（回复已经生成并入库，前端报错但刷新就能看到），等于免费刷模型。
     * 这里只做保守拦截：只要存在任何可能免费的通道（管理员免费 / 月度配额未用完 / 已达每日封顶）就放行，
     * 只有确定要付费且当前余额连最低消费（1 积分）都不够时才提前拒绝；精确金额仍由 spendForChat 计算。
     */
    public void assertChatAffordable(Long userId, boolean isAdmin) {
        if (userId == null) return;
        CreditRule rule = creditRuleService.getRule();
        UserCredit account = ensureAccount(userId);

        if (isAdmin && Boolean.TRUE.equals(rule.getAdminFree())) return;

        int monthlyQuota = safeVal(rule.getMonthlyFreeQuota(), 0);
        if (monthlyQuota > 0) {
            LocalDateTime monthStart = LocalDateTime.of(LocalDate.now().withDayOfMonth(1), LocalTime.MIN);
            long monthlyCount = creditTransactionRepository
                    .countByUserIdAndTypeAndCreatedAtAfter(userId, CreditTransactionType.AI_CHAT, monthStart);
            if (monthlyCount < monthlyQuota) return;
        }

        if (checkDailyCapFree(userId, rule)) return;

        int balance = account.getBalance();
        if (balance < 1) {
            throw new BizException(400, CreditErrorCode.INSUFFICIENT_CREDITS,
                    "积分不足，当前余额: " + balance + "，请先充值或升级订阅后再发起对话");
        }
    }

    /**
     * AI 聊天扣费（兼容旧调用，contextMsgCount=0）。
     */
    @Transactional
    public CreditCostResult spendForChat(Long userId, String model, Integer promptTokens,
                                           Integer completionTokens, int imageCount,
                                           String conversationId, boolean isAdmin) {
        return spendForChat(userId, model, promptTokens, completionTokens, imageCount, 0, conversationId, isAdmin);
    }

    /**
     * AI 聊天扣费：基于模型费率 × token 用量 + 多模态图片额外费用 + 上下文长度增量 + 月度免费配额，
     * 并叠加月卡折扣 / 阶梯累计折扣 / 每日封顶保护。
     *
     * 计费公式：
     *   baseCost      = max(minCost, ceil((p*promptRate + c*completionRate) / tokenUnit)) 或 defaultCostPerMsg
     *   modelRate     = modelRates JSON 中对应模型的倍数（找不到用 default）
     *   imageExtra    = imageCount × imageExtraCost
     *   contextExtra  = max(0, contextMsgCount - contextFreeMsgCount) × contextExtraCostPerMsg
     *   rawCost       = baseCost + imageExtra + contextExtra
     *   overtaxRate   = 月度免费配额耗尽后的倍率
     *   tierDiscount  = 按账号 SubscriptionTier 应用月卡折扣（小/大/ALL）
     *   tieredDiscount= 按本月累计 OUT 金额匹配阶梯阈值
     *   dailyCapFree  = 当日累计 OUT ≥ dailyCapCost 时本次免费
     *   cost          = ceil(rawCost × modelRate × overtaxRate × tierDiscount × tieredDiscount)
     *
     * 月度免费配额：当月已用 AI_CHAT 次数 < monthlyFreeQuota 时本次免费（cost=0）。
     *
     * @param model            AI 模型名（如 "gpt-4", "qwen-7b"）
     * @param promptTokens     输入 token 数（null/0 时回退 defaultCostPerMsg）
     * @param completionTokens 输出 token 数
     * @param imageCount       本次消息中包含的图片数量（0=纯文本）
     * @param contextMsgCount  本次对话已有的历史消息条数（用于上下文长度增量）
     * @param conversationId   会话 ID（写入 relatedId）
     * @param isAdmin          是否管理员（配合 adminFree 规则）
     */
    @Transactional
    public CreditCostResult spendForChat(Long userId, String model, Integer promptTokens,
                                           Integer completionTokens, int imageCount, int contextMsgCount,
                                           String conversationId, boolean isAdmin) {
        CreditRule rule = creditRuleService.getRule();
        UserCredit account = ensureAccount(userId);

        // 管理员免费
        if (isAdmin && Boolean.TRUE.equals(rule.getAdminFree())) {
            writeZeroAmountTx(userId, CreditTransactionType.AI_CHAT,
                    (model != null ? model : "unknown") + "/ADMIN_FREE", conversationId, account.getBalance());
            return new CreditCostResult(0, account.getBalance(), true, false, "ADMIN_FREE");
        }

        // 1. 基础 token 费用
        int baseCost = calculateTokenCost(rule, promptTokens, completionTokens);

        // 2. 模型费率倍数
        double modelRate = parseJsonRate(rule.getModelRates(), model);

        // 3. 多模态图片额外费用
        int imageExtra = imageCount > 0 ? imageCount * safeVal(rule.getImageExtraCost(), 5) : 0;

        // 4. 上下文长度增量费用
        int contextExtra = calculateContextCost(rule, contextMsgCount);

        // 5. 合计基础费用
        int rawCost = baseCost + imageExtra + contextExtra;

        // 6. 月度免费配额检查
        int monthlyQuota = safeVal(rule.getMonthlyFreeQuota(), 0);
        boolean quotaFree = false;
        if (monthlyQuota > 0) {
            LocalDateTime monthStart = LocalDateTime.of(LocalDate.now().withDayOfMonth(1), LocalTime.MIN);
            long monthlyCount = creditTransactionRepository
                    .countByUserIdAndTypeAndCreatedAtAfter(userId, CreditTransactionType.AI_CHAT, monthStart);
            if (monthlyCount < monthlyQuota) {
                quotaFree = true;
            }
        }

        // 7. 每日封顶保护
        boolean dailyCapFree = checkDailyCapFree(userId, rule);

        int finalCost;
        String remark = (model != null ? model : "unknown")
                + (imageCount > 0 ? "/img:" + imageCount : "")
                + (contextExtra > 0 ? "/ctx:" + contextMsgCount : "")
                + (quotaFree ? "/QUOTA_FREE" : "")
                + (dailyCapFree ? "/DAILY_CAP_FREE" : "");

        if (quotaFree || dailyCapFree) {
            finalCost = 0;
            writeZeroAmountTx(userId, CreditTransactionType.AI_CHAT, remark, conversationId, account.getBalance());
            return new CreditCostResult(0, account.getBalance(), false, quotaFree || dailyCapFree, remark);
        }

        // 8. 超配额倍率
        double overtaxRate = rule.getOvertaxRate() != null ? rule.getOvertaxRate() : 1.0;

        // 9. 月卡折扣（按账号 tier）
        double tierDiscount = getTierDiscount(account.getSubscriptionTier(), rule);

        // 10. 阶梯累计折扣（按本月已消费）
        double tieredDiscount = getTieredDiscount(userId, rule.getTieredDiscountThresholds());

        double multiplier = modelRate * overtaxRate * tierDiscount * tieredDiscount;
        finalCost = Math.max(1, (int) Math.ceil(rawCost * multiplier));

        // 11. 扣费
        CreditTransaction tx = spendPoints(userId, finalCost, CreditTransactionType.AI_CHAT, remark, conversationId);
        return new CreditCostResult(finalCost, tx.getBalanceAfter(), false, false, remark);
    }

    /**
     * 预估 AI 聊天费用（不实际扣费，用于前端展示"本次将消耗约 X 积分"）。
     * 与 spendForChat 使用相同计算逻辑，但不写流水、不扣余额。
     */
    public CreditCostResult estimateChatCost(Long userId, String model, Integer promptTokens,
                                              Integer completionTokens, int imageCount, int contextMsgCount,
                                              boolean isAdmin) {
        CreditRule rule = creditRuleService.getRule();
        UserCredit account = ensureAccount(userId);

        if (isAdmin && Boolean.TRUE.equals(rule.getAdminFree())) {
            return new CreditCostResult(0, account.getBalance(), true, false, "ADMIN_FREE");
        }
        int baseCost = calculateTokenCost(rule, promptTokens, completionTokens);
        double modelRate = parseJsonRate(rule.getModelRates(), model);
        int imageExtra = imageCount > 0 ? imageCount * safeVal(rule.getImageExtraCost(), 5) : 0;
        int contextExtra = calculateContextCost(rule, contextMsgCount);
        int rawCost = baseCost + imageExtra + contextExtra;

        int monthlyQuota = safeVal(rule.getMonthlyFreeQuota(), 0);
        boolean quotaFree = false;
        if (monthlyQuota > 0) {
            LocalDateTime monthStart = LocalDateTime.of(LocalDate.now().withDayOfMonth(1), LocalTime.MIN);
            long monthlyCount = creditTransactionRepository
                    .countByUserIdAndTypeAndCreatedAtAfter(userId, CreditTransactionType.AI_CHAT, monthStart);
            if (monthlyCount < monthlyQuota) quotaFree = true;
        }
        boolean dailyCapFree = checkDailyCapFree(userId, rule);
        if (quotaFree || dailyCapFree) {
            return new CreditCostResult(0, account.getBalance(), false, true, "QUOTA_OR_CAP_FREE");
        }
        double overtaxRate = rule.getOvertaxRate() != null ? rule.getOvertaxRate() : 1.0;
        double tierDiscount = getTierDiscount(account.getSubscriptionTier(), rule);
        double tieredDiscount = getTieredDiscount(userId, rule.getTieredDiscountThresholds());
        int cost = Math.max(1, (int) Math.ceil(rawCost * modelRate * overtaxRate * tierDiscount * tieredDiscount));
        return new CreditCostResult(cost, account.getBalance(), false, false, "ESTIMATE");
    }

    /**
     * AI 聊天失败退费：根据原扣费流水 relatedId（conversationId）退回对应金额。
     * 仅退该 conversationId 最近一次 AI_CHAT OUT 流水的金额，幂等性由 relatedId=refund:{cid} 保证。
     */
    @Transactional
    public CreditCostResult refundForChat(Long userId, String conversationId, String reason) {
        return refundByRelatedId(userId, CreditTransactionType.AI_CHAT, conversationId, reason);
    }

    /**
     * AI 分析扣费：基础费用 + 每条消息增量 × 消息条数 × 分析类型倍率。
     *
     * 计费公式：
     *   cost = ceil((analyzeBaseCost + messageCount × analyzeCostPerMsg) × typeRate)
     *
     * @param messageCount 分析的消息条数
     * @param analysisType 分析类型（如 "summary", "analysis", "key-points"）
     * @param groupId       群 ID（写入 relatedId）
     * @param isAdmin      是否管理员
     */
    @Transactional
    public CreditCostResult spendForAnalyze(Long userId, int messageCount, String analysisType,
                                             String groupId, boolean isAdmin) {
        CreditRule rule = creditRuleService.getRule();
        UserCredit account = ensureAccount(userId);

        // 管理员免费
        if (isAdmin && Boolean.TRUE.equals(rule.getAdminFree())) {
            String remark = "analyze:" + (analysisType != null ? analysisType : "default") + "/ADMIN_FREE";
            writeZeroAmountTx(userId, CreditTransactionType.AI_ANALYZE, remark, groupId, account.getBalance());
            return new CreditCostResult(0, account.getBalance(), true, false, remark);
        }

        // 每日封顶保护
        boolean dailyCapFree = checkDailyCapFree(userId, rule);
        if (dailyCapFree) {
            String remark = "analyze:" + (analysisType != null ? analysisType : "default")
                    + "/msgs:" + Math.max(0, messageCount) + "/DAILY_CAP_FREE";
            writeZeroAmountTx(userId, CreditTransactionType.AI_ANALYZE, remark, groupId, account.getBalance());
            return new CreditCostResult(0, account.getBalance(), false, true, remark);
        }

        // 计算费用
        int base = safeVal(rule.getAnalyzeBaseCost(), 10);
        int perMsg = safeVal(rule.getAnalyzeCostPerMsg(), 1);
        double typeRate = parseJsonRate(rule.getAnalyzeTypeRates(), analysisType);

        int rawCost = base + Math.max(0, messageCount) * perMsg;

        // 月卡折扣 + 阶梯累计折扣
        double tierDiscount = getTierDiscount(account.getSubscriptionTier(), rule);
        double tieredDiscount = getTieredDiscount(userId, rule.getTieredDiscountThresholds());

        int cost = Math.max(1, (int) Math.ceil(rawCost * typeRate * tierDiscount * tieredDiscount));

        String remark = "analyze:" + (analysisType != null ? analysisType : "default")
                + "/msgs:" + Math.max(0, messageCount);
        CreditTransaction tx = spendPoints(userId, cost, CreditTransactionType.AI_ANALYZE, remark, groupId);
        return new CreditCostResult(cost, tx.getBalanceAfter(), false, false, remark);
    }

    /**
     * AI 分析失败退费：按 relatedId（groupId）退回最近一次 AI_ANALYZE 扣费。
     */
    @Transactional
    public CreditCostResult refundForAnalyze(Long userId, String groupId, String reason) {
        return refundByRelatedId(userId, CreditTransactionType.AI_ANALYZE, groupId, reason);
    }

    /**
     * TTS 语音合成扣费：按字符数计费，叠加月卡折扣 + 阶梯累计折扣 + 每日封顶保护。
     *
     * 计费公式：
     *   cost = max(ttsMinCost, ceil(textLength / ttsCharsPerCredit) × tierDiscount × tieredDiscount)
     *
     * @param text       待合成的文本
     * @param character  角色名（写入 remark）
     */
    @Transactional
    public CreditCostResult spendForTts(Long userId, String text, String character, boolean isAdmin, String requestKey) {
        CreditRule rule = creditRuleService.getRule();
        UserCredit account = ensureAccount(userId);

        // 管理员免费
        if (isAdmin && Boolean.TRUE.equals(rule.getAdminFree())) {
            String remark = "tts:" + (character != null ? character : "default") + "/ADMIN_FREE";
            writeZeroAmountTx(userId, CreditTransactionType.TTS_SYNTHESIS, remark, requestKey, account.getBalance());
            return new CreditCostResult(0, account.getBalance(), true, false, remark);
        }

        // 每日封顶保护
        boolean dailyCapFree = checkDailyCapFree(userId, rule);
        if (dailyCapFree) {
            String remark = "tts:" + (character != null ? character : "default") + "/DAILY_CAP_FREE";
            writeZeroAmountTx(userId, CreditTransactionType.TTS_SYNTHESIS, remark, requestKey, account.getBalance());
            return new CreditCostResult(0, account.getBalance(), false, true, remark);
        }

        int charCount = text != null ? text.length() : 0;
        int charsPerCredit = safeVal(rule.getTtsCharsPerCredit(), 50);
        int minCost = safeVal(rule.getTtsMinCost(), 2);

        int rawCost = (int) Math.ceil((double) charCount / charsPerCredit);

        // 月卡折扣 + 阶梯累计折扣
        double tierDiscount = getTierDiscount(account.getSubscriptionTier(), rule);
        double tieredDiscount = getTieredDiscount(userId, rule.getTieredDiscountThresholds());

        int cost = Math.max(minCost, (int) Math.ceil(rawCost * tierDiscount * tieredDiscount));

        String remark = "tts:" + (character != null ? character : "default") + "/chars:" + charCount;
        CreditTransaction tx = spendPoints(userId, cost, CreditTransactionType.TTS_SYNTHESIS, remark, requestKey);
        return new CreditCostResult(cost, tx.getBalanceAfter(), false, false, remark);
    }

    /**
     * TTS 失败退费：按 relatedId 退回最近一次 TTS_SYNTHESIS 扣费。
     * 由于 TTS 没有业务 relatedId，使用 remark 中的时间戳特征进行匹配。
     */
    @Transactional
    public CreditCostResult refundForTts(Long userId, String refundKey, String reason) {
        // 通用退费：通过 refund:{key} 作为幂等键
        return refundByRelatedId(userId, CreditTransactionType.TTS_SYNTHESIS, refundKey, reason);
    }

    /**
     * 群类型识别扣费：固定 1 积分，管理员免费。
     */
    @Transactional
    public CreditCostResult spendForGroupType(Long userId, String groupId, boolean isAdmin) {
        CreditRule rule = creditRuleService.getRule();
        UserCredit account = ensureAccount(userId);

        if (isAdmin && Boolean.TRUE.equals(rule.getAdminFree())) {
            writeZeroAmountTx(userId, CreditTransactionType.AI_CHAT,
                    "群类型识别/ADMIN_FREE", "group:" + groupId, account.getBalance());
            return new CreditCostResult(0, account.getBalance(), true, false, "ADMIN_FREE");
        }

        CreditTransaction tx = spendPoints(userId, 1, CreditTransactionType.AI_CHAT,
                "群类型识别", "group:" + groupId);
        return new CreditCostResult(1, tx.getBalanceAfter(), false, false, "群类型识别");
    }

    // ====== 精细化计费辅助方法 ======

    /**
     * 基础 token 计费（不含模型倍数和图片费用）。
     */
    private int calculateTokenCost(CreditRule rule, Integer promptTokens, Integer completionTokens) {
        int p = (promptTokens != null) ? promptTokens : 0;
        int c = (completionTokens != null) ? completionTokens : 0;

        if (p == 0 && c == 0) {
            return safeVal(rule.getDefaultCostPerMsg(), 10);
        }

        int tokenUnit = safeVal(rule.getTokenUnit(), 1000);
        int promptRate = safeVal(rule.getPromptRate(), 2);
        int completionRate = safeVal(rule.getCompletionRate(), 4);
        int minCost = safeVal(rule.getMinCost(), 5);

        long numerator = (long) p * promptRate + (long) c * completionRate;
        int raw = (int) Math.ceil((double) numerator / tokenUnit);
        return Math.max(minCost, raw);
    }

    /**
     * 上下文长度增量计费：超过免费条数后，每条历史消息额外消耗 contextExtraCostPerMsg 积分。
     */
    private int calculateContextCost(CreditRule rule, int contextMsgCount) {
        if (contextMsgCount <= 0) return 0;
        int perMsg = safeVal(rule.getContextExtraCostPerMsg(), 0);
        if (perMsg <= 0) return 0;
        int freeMsgs = safeVal(rule.getContextFreeMsgCount(), 0);
        int billable = Math.max(0, contextMsgCount - freeMsgs);
        return billable * perMsg;
    }

    /**
     * 根据账号 SubscriptionTier 返回对应的折扣倍数。
     * FREE / LITE / PRO / PROPLUS / ULTRA / MEGA（直购积分）→ 1.0（无折扣，月卡专属权益）
     * SMALL_MONTH_CARD → smallMonthCardDiscount
     * LARGE_MONTH_CARD → largeMonthCardDiscount
     * ALL → allTierDiscount
     */
    private double getTierDiscount(SubscriptionTier tier, CreditRule rule) {
        if (tier == null) return 1.0;
        switch (tier) {
            case SMALL_MONTH_CARD:
                return rule.getSmallMonthCardDiscount() != null ? rule.getSmallMonthCardDiscount() : 1.0;
            case LARGE_MONTH_CARD:
                return rule.getLargeMonthCardDiscount() != null ? rule.getLargeMonthCardDiscount() : 1.0;
            case ALL:
                return rule.getAllTierDiscount() != null ? rule.getAllTierDiscount() : 1.0;
            default:
                return 1.0;
        }
    }

    /**
     * 月度阶梯累计折扣：按本月累计 OUT 金额匹配阶梯阈值，取最大符合阈值的折扣。
     * JSON 例：{"1000":0.95,"5000":0.9,"20000":0.85}
     * 匹配规则：累计消费 ≥ 5000 时取 0.9，≥ 1000 时取 0.95，<1000 时返回 1.0。
     */
    private double getTieredDiscount(Long userId, String thresholdsJson) {
        if (thresholdsJson == null || thresholdsJson.isBlank()) return 1.0;
        long monthlySpent = getMonthlySpent(userId);
        double bestDiscount = 1.0;
        try {
            // 解析所有 "阈值":折扣 对，找最大符合阈值的
            java.util.regex.Pattern p = java.util.regex.Pattern.compile(
                    "\"(\\d+)\"\\s*:\\s*([0-9.]+)");
            java.util.regex.Matcher m = p.matcher(thresholdsJson);
            while (m.find()) {
                long threshold = Long.parseLong(m.group(1));
                double discount = Double.parseDouble(m.group(2));
                if (monthlySpent >= threshold && discount < bestDiscount) {
                    bestDiscount = discount;
                }
            }
        } catch (Exception e) {
            log.warn("解析阶梯折扣JSON失败: {}", e.getMessage());
        }
        return bestDiscount;
    }

    /**
     * 获取用户本月（自然月）累计 OUT 金额。
     */
    private long getMonthlySpent(Long userId) {
        LocalDateTime monthStart = LocalDateTime.of(LocalDate.now().withDayOfMonth(1), LocalTime.MIN);
        return creditTransactionRepository.sumSpendByFilters(
                userId, null, CreditDirection.OUT, monthStart, null, null);
    }

    /**
     * 获取用户今日累计 OUT 金额。
     */
    private long getTodaySpent(Long userId) {
        LocalDateTime todayStart = LocalDateTime.of(LocalDate.now(), LocalTime.MIN);
        return creditTransactionRepository.sumSpendByFilters(
                userId, null, CreditDirection.OUT, todayStart, null, null);
    }

    /**
     * 检查每日封顶：若 dailyCapCost > 0 且今日累计 OUT ≥ dailyCapCost，返回 true 表示本次应免费。
     */
    private boolean checkDailyCapFree(Long userId, CreditRule rule) {
        int cap = safeVal(rule.getDailyCapCost(), 0);
        if (cap <= 0) return false;
        return getTodaySpent(userId) >= cap;
    }

    /**
     * 通用退费方法：按 (userId, type, relatedId) 找出最近一次 OUT 流水，
     * 等额回补余额并写 IN 方向的 REFUND 流水；幂等性通过 relatedId=refund:{originalRelatedId} 保证。
     *
     * @param userId     用户 ID
     * @param type       原扣费类型（AI_CHAT/AI_ANALYZE/TTS_SYNTHESIS）
     * @param relatedId  原流水 relatedId
     * @param reason     退费原因（写入 remark）
     */
    @Transactional
    public CreditCostResult refundByRelatedId(Long userId, CreditTransactionType type,
                                                String relatedId, String reason) {
        if (relatedId == null || relatedId.isBlank()) {
            throw new BizException("退费失败：relatedId 不能为空");
        }
        // 幂等检查：已存在同 relatedId 的 REFUND 流水则直接返回
        String refundRelatedId = "refund:" + relatedId;
        if (creditTransactionRepository.countByUserIdAndRelatedId(userId, refundRelatedId) > 0) {
            UserCredit account = ensureAccount(userId);
            return new CreditCostResult(0, account.getBalance(), false, false, "REFUND_DUP");
        }

        // 查找最近一次对应的 OUT 流水
        Page<CreditTransaction> page = creditTransactionRepository
                .findByUserIdAndTypeOrderByCreatedAtDesc(userId, type, PageRequest.of(0, 50));
        List<CreditTransaction> candidates = (page != null) ? page.getContent() : Collections.emptyList();
        CreditTransaction target = null;
        for (CreditTransaction tx : candidates) {
            if (CreditDirection.OUT.equals(tx.getDirection())
                    && relatedId.equals(tx.getRelatedId())
                    && tx.getAmount() != null && tx.getAmount() > 0) {
                target = tx;
                break;
            }
        }
        if (target == null) {
            throw new BizException("退费失败：未找到匹配的原扣费流水 relatedId=" + relatedId);
        }

        int refundAmount = target.getAmount();
        UserCredit account = userCreditRepository.findByUserIdWithLock(userId)
                .orElseThrow(() -> new BizException("积分账户不存在"));
        int newBalance = account.getBalance() + refundAmount;
        account.setBalance(newBalance);
        // 退费不回退 totalSpent（保留历史消费统计），但可考虑用 negative balance 的逻辑；
        // 这里采用"退费不修改 totalSpent"的设计，便于审计实际消费量。
        userCreditRepository.save(account);

        CreditTransaction refundTx = new CreditTransaction();
        refundTx.setUserId(userId);
        refundTx.setType(CreditTransactionType.REFUND);
        refundTx.setDirection(CreditDirection.IN);
        refundTx.setAmount(refundAmount);
        refundTx.setBalanceAfter(newBalance);
        refundTx.setRemark("退费/" + (reason != null ? reason : type.name())
                + "/原流水#" + target.getId());
        refundTx.setRelatedId(refundRelatedId);
        creditTransactionRepository.save(refundTx);
        log.info("用户{} 退费成功 类型={} relatedId={} 金额={} 余额={}",
                userId, type, relatedId, refundAmount, newBalance);
        return new CreditCostResult(refundAmount, newBalance, false, false, "REFUND");
    }

    /**
     * 从 JSON 格式的费率映射中查找指定 key 对应的倍数。
     * JSON 格式：{"default":1.0, "gpt-4":3.0, "qwen-7b":1.0}
     * 匹配规则：先精确匹配 key，找不到则用 "default"，都没有则返回 1.0。
     */
    private double parseJsonRate(String json, String key) {
        if (json == null || json.isBlank()) return 1.0;
        if (key == null || key.isEmpty()) return 1.0;
        try {
            // 轻量级 JSON 解析，不引入 Jackson 依赖
            // 匹配 "key":number 格式
            String normalized = key.replace("\"", "\\\"");
            java.util.regex.Pattern p = java.util.regex.Pattern.compile(
                    "\"" + java.util.regex.Pattern.quote(key) + "\"\\s*:\\s*([0-9.]+)");
            java.util.regex.Matcher m = p.matcher(json);
            if (m.find()) {
                return Double.parseDouble(m.group(1));
            }
            // 回退到 default
            java.util.regex.Pattern pd = java.util.regex.Pattern.compile(
                    "\"default\"\\s*:\\s*([0-9.]+)");
            java.util.regex.Matcher md = pd.matcher(json);
            if (md.find()) {
                return Double.parseDouble(md.group(1));
            }
        } catch (Exception e) {
            log.warn("解析费率JSON失败 json={} key={}: {}", json, key, e.getMessage());
        }
        return 1.0;
    }

    /**
     * 写一条 amount=0 的审计流水（用于管理员免费 / 配额免费场景）。
     */
    private void writeZeroAmountTx(Long userId, CreditTransactionType type,
                                    String remark, String relatedId, int balanceAfter) {
        try {
            CreditTransaction tx = new CreditTransaction();
            tx.setUserId(userId);
            tx.setType(type);
            tx.setDirection(CreditDirection.OUT);
            tx.setAmount(0);
            tx.setBalanceAfter(balanceAfter);
            tx.setRemark(remark);
            tx.setRelatedId(relatedId);
            creditTransactionRepository.save(tx);
        } catch (Exception e) {
            log.warn("记录零额审计流水失败: {}", e.getMessage());
        }
    }

    private int safeVal(Integer v, int def) {
        return v != null ? v : def;
    }

    /**
     * 每日签到：写入签到记录并发放签到积分，连续签到天数基于昨日记录累加。
     * 2026-09-16 起：持有有效月卡的用户，签到会同时发放月卡每日额外积分（小月卡 +100 / 大月卡 +300），
     * 即界面上的「每日签到积分」= 基础签到分 + 月卡加成；月卡加成走 grantMonthlyCardDailyBonus 幂等入口，
     * 同一天不会重复到账。
     * 并发控制：先对账户行加悲观锁（同一用户的签到被串行化），再判断今日是否已签到，
     * 因此正常并发下不会走到唯一约束冲突；仍保留冲突兜底，但**不再在已中毒的事务里继续查询**
     * （旧实现捕获约束冲突后仍在同一事务内查询，会抛 UnexpectedRollbackException）。
     * 事务边界：签到记录与积分流水在同一事务内落库。
     */
    @Transactional
    public SignInResult signInToday(Long userId) {
        LocalDate today = LocalDate.now();
        // 先用账户行锁把同一用户的并发签到串行化，再做「今日是否已签到」判定
        UserCredit account = lockOrCreateAccount(userId);
        if (signInRecordRepository.existsByUserIdAndSignInDate(userId, today)) {
            throw new BizException(400, CreditErrorCode.ALREADY_SIGNED_IN, "今日已签到");
        }

        int streakDays = calculateStreakDays(userId, today);
        int basePoints = safeSignInPoints();
        // 月卡加成：先取"本次可得"，实际发放交给幂等入口（今日已发过则为 0）
        int cardBonus = todayMonthlyCardBonus(userId);

        SignInRecord record = new SignInRecord();
        record.setUserId(userId);
        record.setSignInDate(today);
        record.setPoints(basePoints + cardBonus);
        record.setStreakDays(streakDays);
        try {
            signInRecordRepository.save(record);
        } catch (DataIntegrityViolationException e) {
            // 兜底：账户行锁已把同一用户的并发签到串行化，走到这里说明存在绕过服务层的写入。
            // 事务此刻已被标记 rollback-only，绝不能再查询（旧实现在这里继续查询会抛
            // UnexpectedRollbackException，把真实的唯一约束冲突掩盖掉）。
            log.warn("用户{} 签到唯一约束冲突（并发兜底），本次按已签到处理", userId);
            throw new BizException(400, CreditErrorCode.ALREADY_SIGNED_IN, "今日已签到");
        }

        grantPointsInternal(account, basePoints, CreditTransactionType.SIGN_IN,
                "连续签到" + streakDays + "天", today.toString(), null);
        userCreditRepository.save(account);

        // 月卡每日额外积分（幂等；无月卡或今日已发过时为 0）
        int grantedBonus = 0;
        if (cardBonus > 0) {
            grantedBonus = grantMonthlyCardDailyBonus(userId);
            if (grantedBonus != cardBonus) {
                // 极端并发下加成被别人先领了：把签到记录里的合计改成真实值
                record.setPoints(basePoints + grantedBonus);
            }
        }

        log.info("用户{} 签到成功，获得{}积分（基础{}+月卡{}），连续{}天",
                userId, basePoints + grantedBonus, basePoints, grantedBonus, streakDays);
        return new SignInResult(record, basePoints, grantedBonus);
    }

    /**
     * 月卡每日额外积分（自 2026-09-16 起随「每日签到」发放，不再在登录时静默发放）。
     * 通过订单表查询用户是否有未过期的月卡（PAID状态+expiresAt>=now），取最高档月卡（大月卡优先）。
     * 不依赖 tier 字段，避免直购积分高 tier 覆盖月卡 tier 导致用户无法领取月卡奖励的问题。
     * 幂等：relatedId=MONTHLY_CARD_DAILY-{yyyy-MM-dd} + monthly_bonus_record 唯一约束，
     *       同一天重复调用不会重复发放。
     * 小月卡每日 +100 积分，大月卡每日 +300 积分，**双持叠加为 +400**。
     *
     * @return 本次实际发放的积分；0 表示无有效月卡或今日已发放
     */
    @Transactional
    public int grantMonthlyCardDailyBonus(Long userId) {
        LocalDate today = LocalDate.now();
        String relatedId = "MONTHLY_CARD_DAILY-" + today;

        // 幂等加固：先检查 MonthlyBonusRecord 是否已存在
        if (monthlyBonusRecordRepository.existsByUserIdAndBonusDate(userId, today)) {
            return 0;
        }

        // 查询有效月卡：PAID状态 + 月卡类型 + 未过期；小月卡与大月卡**叠加**计算
        int bonus = resolveActiveMonthlyCardBonus(userId);
        if (bonus <= 0) {
            return 0;
        }
        String combo = resolveActiveMonthlyCardCombo(userId);

        // 插入月度奖励记录（幂等：唯一约束防并发重复）
        MonthlyBonusRecord record = new MonthlyBonusRecord();
        record.setUserId(userId);
        record.setBonusDate(today);
        try {
            monthlyBonusRecordRepository.save(record);
        } catch (DataIntegrityViolationException e) {
            log.warn("用户{} 月卡每日奖励并发冲突，视为已发放直接返回", userId);
            return 0;
        }

        UserCredit account = ensureAccount(userId);
        grantPointsInternal(account, bonus, CreditTransactionType.MONTHLY_CARD_DAILY,
                "月卡每日签到奖励(" + combo + "): +" + bonus, relatedId, null);
        userCreditRepository.save(account);
        log.info("用户{} 月卡每日奖励发放: +{} ({})", userId, bonus, combo);
        return bonus;
    }

    /** 用户当前有效月卡档位集合（去重）；无有效月卡返回空集。 */
    public java.util.Set<SubscriptionTier> resolveActiveMonthlyCardTiers(Long userId) {
        List<SubscriptionOrder> activeCards = subscriptionOrderRepository.findActiveMonthlyCards(
                userId, OrderStatus.PAID, MONTHLY_CARD_TIERS, LocalDateTime.now());
        java.util.Set<SubscriptionTier> tiers = java.util.EnumSet.noneOf(SubscriptionTier.class);
        if (activeCards != null) {
            for (SubscriptionOrder o : activeCards) {
                if (o.getPlanTier() != null) tiers.add(o.getPlanTier());
            }
        }
        return tiers;
    }

    /**
     * 有效月卡组合的展示名：双持返回 ALL，仅大月卡 LARGE_MONTH_CARD，仅小月卡 SMALL_MONTH_CARD；无卡返回 null。
     * 用于界面/流水标注（避免双持时只显示"大月卡"，让人以为小月卡没生效）。
     */
    public String resolveActiveMonthlyCardCombo(Long userId) {
        java.util.Set<SubscriptionTier> tiers = resolveActiveMonthlyCardTiers(userId);
        if (tiers.isEmpty()) {
            return null;
        }
        if (tiers.size() > 1) {
            return SubscriptionTier.ALL.name();
        }
        return tiers.iterator().next().name();
    }

    /**
     * 月卡每日额外积分（小月卡 / 大月卡；双持 = 两者之和）。
     *
     * <p>数值来自「规则配置」表单（{@code CreditRule.planSmallMonthCardDailyBonus} 等），
     * 管理员改完立即生效，客户端订阅页的「每日签到额外 +N 积分」也读同一份配置。</p>
     */
    public int monthlyCardBonusFor(SubscriptionTier tier) {
        CreditRule rule = creditRuleService.getRule();
        if (tier == SubscriptionTier.LARGE_MONTH_CARD) return safeBonus(rule.getPlanLargeMonthCardDailyBonus());
        if (tier == SubscriptionTier.SMALL_MONTH_CARD) return safeBonus(rule.getPlanSmallMonthCardDailyBonus());
        if (tier == SubscriptionTier.ALL) {
            return safeBonus(rule.getPlanSmallMonthCardDailyBonus()) + safeBonus(rule.getPlanLargeMonthCardDailyBonus());
        }
        return 0;
    }

    private static int safeBonus(Integer v) {
        return v == null || v < 0 ? 0 : v;
    }

    /** 用户当前有效月卡的每日额外积分合计（小 100 / 大 300 / 双持 400）；无卡返回 0。 */
    public int resolveActiveMonthlyCardBonus(Long userId) {
        int sum = 0;
        for (SubscriptionTier t : resolveActiveMonthlyCardTiers(userId)) {
            sum += monthlyCardBonusFor(t);
        }
        return sum;
    }

    /** 今日签到还可获得的月卡额外积分（今日已发过则为 0，用于界面展示"本次可得"）。 */
    public int todayMonthlyCardBonus(Long userId) {
        LocalDate today = LocalDate.now();
        if (monthlyBonusRecordRepository.existsByUserIdAndBonusDate(userId, today)) {
            return 0;
        }
        return resolveActiveMonthlyCardBonus(userId);
    }

    /** 基础签到积分（credit_rule.sign_in_points，缺省 150）。 */
    public int getSafeSignInPoints() {
        return safeSignInPoints();
    }

    /** 签到结果：基础分 + 月卡额外分。 */
    public static class SignInResult {
        public final SignInRecord record;
        public final int basePoints;
        public final int monthlyCardBonus;

        public SignInResult(SignInRecord record, int basePoints, int monthlyCardBonus) {
            this.record = record;
            this.basePoints = basePoints;
            this.monthlyCardBonus = monthlyCardBonus;
        }

        public int getTotalPoints() {
            return basePoints + monthlyCardBonus;
        }
    }

    private int calculateStreakDays(Long userId, LocalDate today) {
        LocalDate yesterday = today.minusDays(1);
        if (signInRecordRepository.existsByUserIdAndSignInDate(userId, yesterday)) {
            List<SignInRecord> recent = signInRecordRepository.findRecentByUserIdAndDate(
                    userId, yesterday, PageRequest.of(0, 1));
            if (!recent.isEmpty()) {
                return recent.get(0).getStreakDays() + 1;
            }
        }
        return 1;
    }

    public int getCurrentStreakDays(Long userId) {
        LocalDate today = LocalDate.now();
        if (signInRecordRepository.existsByUserIdAndSignInDate(userId, today)) {
            Optional<SignInRecord> todayOpt = signInRecordRepository.findByUserIdAndSignInDate(userId, today);
            return todayOpt.map(SignInRecord::getStreakDays).orElse(1);
        }
        LocalDate yesterday = today.minusDays(1);
        if (signInRecordRepository.existsByUserIdAndSignInDate(userId, yesterday)) {
            List<SignInRecord> recent = signInRecordRepository.findRecentByUserIdAndDate(
                    userId, yesterday, PageRequest.of(0, 1));
            if (!recent.isEmpty()) {
                return recent.get(0).getStreakDays();
            }
        }
        return 0;
    }

    public Optional<UserCredit> getBalance(Long userId) {
        return userCreditRepository.findByUserId(userId);
    }

    /**
     * 纯读查询：基于 PAID 月卡订单在内存中推导用户当前订阅等级和到期时间，不落库。
     * 直购积分不改变 tier（始终保持 FREE）。订阅过期自动降级 FREE。
     * 不再执行任何补偿写操作（积分补发、月卡奖励补发等由 AuthController 的登录路径处理）。
     */
    public UserCredit getBalanceWithTier(Long userId) {
        UserCredit account = ensureAccount(userId);
        LocalDateTime now = LocalDateTime.now();

        // 计算：直购积分 tier 应该在内存中视为 FREE（直购积分不改变账号状态）
        SubscriptionTier cur = account.getSubscriptionTier();
        if (cur != null && cur != SubscriptionTier.FREE && !isMonthlyCard(cur)) {
            account.setSubscriptionTier(SubscriptionTier.FREE);
            account.setSubscriptionExpiresAt(null);
        }

        // 计算：订阅过期 → FREE（内存判断，不落库）
        if (account.getSubscriptionExpiresAt() != null
                && account.getSubscriptionExpiresAt().isBefore(now)
                && account.getSubscriptionTier() != SubscriptionTier.FREE) {
            account.setSubscriptionTier(SubscriptionTier.FREE);
            account.setSubscriptionExpiresAt(null);
        }

        // 纯读：基于 PAID 订单在内存中推导订阅等级
        try {
            List<SubscriptionOrder> validPaid = subscriptionOrderRepository
                    .findByUserIdAndStatusOrderByCreatedAtDesc(userId, OrderStatus.PAID,
                            org.springframework.data.domain.PageRequest.of(0, 200))
                    .getContent();

            boolean hasSmallCard = false;
            boolean hasLargeCard = false;
            LocalDateTime bestExpires = null;

            for (SubscriptionOrder o : validPaid) {
                // 内存推导 expiresAt（找不到时用 paidAt+durationDays，不落库）
                LocalDateTime derivedExpiresAt = o.getExpiresAt();
                if (derivedExpiresAt == null && o.getPaidAt() != null && o.getDurationDays() != null) {
                    derivedExpiresAt = o.getPaidAt().plusDays(o.getDurationDays());
                }
                if (derivedExpiresAt == null && o.getDurationDays() != null) {
                    derivedExpiresAt = (o.getCreatedAt() != null
                            ? o.getCreatedAt().plusDays(o.getDurationDays())
                            : now.plusDays(o.getDurationDays()));
                }

                if (derivedExpiresAt == null || derivedExpiresAt.isBefore(now)) continue;
                SubscriptionTier t = o.getPlanTier();
                if (t == SubscriptionTier.SMALL_MONTH_CARD) {
                    hasSmallCard = true;
                    if (bestExpires == null || derivedExpiresAt.isAfter(bestExpires)) {
                        bestExpires = derivedExpiresAt;
                    }
                } else if (t == SubscriptionTier.LARGE_MONTH_CARD) {
                    hasLargeCard = true;
                    if (bestExpires == null || derivedExpiresAt.isAfter(bestExpires)) {
                        bestExpires = derivedExpiresAt;
                    }
                }
            }

            SubscriptionTier bestTier;
            if (hasSmallCard && hasLargeCard) {
                bestTier = SubscriptionTier.ALL;
            } else if (hasLargeCard) {
                bestTier = SubscriptionTier.LARGE_MONTH_CARD;
            } else if (hasSmallCard) {
                bestTier = SubscriptionTier.SMALL_MONTH_CARD;
            } else {
                bestTier = SubscriptionTier.FREE;
                bestExpires = null;
            }

            // 只设置内存对象的值，不 save
            account.setSubscriptionTier(bestTier);
            account.setSubscriptionExpiresAt(bestExpires);
        } catch (Exception e) {
            log.warn("月卡tier纯读计算异常（不影响主流程）: {}", e.getMessage(), e);
        }

        return account;
    }

    private static boolean isMonthlyCard(SubscriptionTier tier) {
        return tier == SubscriptionTier.SMALL_MONTH_CARD || tier == SubscriptionTier.LARGE_MONTH_CARD
                || tier == SubscriptionTier.ALL;
    }

    private static int tierRank(SubscriptionTier tier) {
        if (tier == null) return 0;
        return switch (tier) {
            case FREE -> 0;
            case LITE -> 1;
            case PRO -> 2;
            case PROPLUS -> 3;
            case ULTRA -> 4;
            case MEGA -> 5;
            case SMALL_MONTH_CARD -> 6;
            case LARGE_MONTH_CARD -> 7;
            case ALL -> 8;
        };
    }

    public boolean hasSignedInToday(Long userId) {
        return signInRecordRepository.existsByUserIdAndSignInDate(userId, LocalDate.now());
    }

    public Map<String, Object> getSignInStatus(Long userId, String range) {
        LocalDate today = LocalDate.now();
        boolean todayDone = hasSignedInToday(userId);
        int streakDays = getCurrentStreakDays(userId);

        LocalDate start;
        if ("month".equalsIgnoreCase(range)) {
            start = today.withDayOfMonth(1);
        } else {
            start = today.minusDays(6);
        }

        List<SignInRecord> records = signInRecordRepository
                .findByUserIdAndSignInDateBetweenOrderBySignInDateAsc(userId, start, today);
        Map<LocalDate, SignInRecord> recordMap = new HashMap<>();
        for (SignInRecord r : records) {
            recordMap.put(r.getSignInDate(), r);
        }

        List<Map<String, Object>> calendar = new ArrayList<>();
        for (LocalDate d = start; !d.isAfter(today); d = d.plusDays(1)) {
            SignInRecord r = recordMap.get(d);
            Map<String, Object> item = new HashMap<>();
            item.put("date", d.toString());
            item.put("done", r != null);
            item.put("points", r != null ? r.getPoints() : 0);
            calendar.add(item);
        }

        Map<String, Object> result = new HashMap<>();
        result.put("todayDone", todayDone);
        result.put("streakDays", streakDays);
        result.put("calendar", calendar);
        return result;
    }

    public Map<String, Object> getTrend(Long userId, int days) {
        LocalDate today = LocalDate.now();
        LocalDate start = today.minusDays(Math.max(0, days - 1));
        LocalDateTime startDt = start.atStartOfDay();
        LocalDateTime endDt = today.atTime(LocalTime.MAX);

        List<Object[]> rows = creditTransactionRepository.sumEarnedSpentGroupByDate(userId, startDt, endDt);
        Map<LocalDate, Object[]> rowMap = new HashMap<>();
        for (Object[] row : rows) {
            // 聚合查询的日期列类型随数据库/方言变化（Date / LocalDate / String），统一归一化
            String key = com.qqai.util.DateKeys.toIsoDate(row[0]);
            if (key == null) {
                continue;
            }
            rowMap.put(LocalDate.parse(key), row);
        }

        List<Map<String, Object>> series = new ArrayList<>();
        for (LocalDate d = start; !d.isAfter(today); d = d.plusDays(1)) {
            Object[] row = rowMap.get(d);
            long earned = 0;
            long spent = 0;
            if (row != null) {
                earned = ((Number) row[1]).longValue();
                spent = ((Number) row[2]).longValue();
            }
            Map<String, Object> item = new HashMap<>();
            item.put("date", d.toString());
            item.put("earned", earned);
            item.put("spent", spent);
            item.put("net", earned - spent);
            series.add(item);
        }
        Map<String, Object> result = new HashMap<>();
        result.put("series", series);
        return result;
    }

    public Page<CreditTransaction> pageTransactions(Long userId, CreditTransactionType type, CreditDirection direction,
                                                     LocalDateTime start, LocalDateTime end, String relatedId,
                                                     Pageable pageable) {
        CriteriaBuilder cb = entityManager.getCriteriaBuilder();
        CriteriaQuery<CreditTransaction> cq = cb.createQuery(CreditTransaction.class);
        Root<CreditTransaction> root = cq.from(CreditTransaction.class);
        List<Predicate> predicates = new ArrayList<>();
        predicates.add(cb.equal(root.get("userId"), userId));
        if (type != null) predicates.add(cb.equal(root.get("type"), type));
        if (direction != null) predicates.add(cb.equal(root.get("direction"), direction));
        if (start != null) predicates.add(cb.greaterThanOrEqualTo(root.get("createdAt"), start));
        if (end != null) predicates.add(cb.lessThanOrEqualTo(root.get("createdAt"), end));
        if (relatedId != null && !relatedId.isBlank()) predicates.add(cb.equal(root.get("relatedId"), relatedId));
        cq.where(predicates.toArray(new Predicate[0]));
        cq.orderBy(cb.desc(root.get("createdAt")));
        TypedQuery<CreditTransaction> q = entityManager.createQuery(cq);
        long total = countTransactions(cb, userId, type, direction, start, end, relatedId);
        q.setFirstResult((int) pageable.getOffset());
        q.setMaxResults(pageable.getPageSize());
        List<CreditTransaction> content = q.getResultList();
        return new org.springframework.data.domain.PageImpl<>(content, PageRequest.of(
                pageable.getPageNumber(), pageable.getPageSize(), Sort.by(Sort.Direction.DESC, "createdAt")), total);
    }

    private long countTransactions(CriteriaBuilder cb, Long userId, CreditTransactionType type, CreditDirection direction,
                                    LocalDateTime start, LocalDateTime end, String relatedId) {
        CriteriaQuery<Long> countCq = cb.createQuery(Long.class);
        Root<CreditTransaction> root = countCq.from(CreditTransaction.class);
        List<Predicate> predicates = new ArrayList<>();
        predicates.add(cb.equal(root.get("userId"), userId));
        if (type != null) predicates.add(cb.equal(root.get("type"), type));
        if (direction != null) predicates.add(cb.equal(root.get("direction"), direction));
        if (start != null) predicates.add(cb.greaterThanOrEqualTo(root.get("createdAt"), start));
        if (end != null) predicates.add(cb.lessThanOrEqualTo(root.get("createdAt"), end));
        if (relatedId != null && !relatedId.isBlank()) predicates.add(cb.equal(root.get("relatedId"), relatedId));
        countCq.select(cb.count(root)).where(predicates.toArray(new Predicate[0]));
        return entityManager.createQuery(countCq).getSingleResult();
    }

    public Page<CreditTransaction> pageTransactionsAdmin(Long userId, CreditTransactionType type, CreditDirection direction,
                                                          LocalDateTime start, LocalDateTime end, Long minAmount, Long maxAmount,
                                                          String relatedId, Pageable pageable) {
        CriteriaBuilder cb = entityManager.getCriteriaBuilder();
        CriteriaQuery<CreditTransaction> cq = cb.createQuery(CreditTransaction.class);
        Root<CreditTransaction> root = cq.from(CreditTransaction.class);
        List<Predicate> predicates = new ArrayList<>();
        if (userId != null) predicates.add(cb.equal(root.get("userId"), userId));
        if (type != null) predicates.add(cb.equal(root.get("type"), type));
        if (direction != null) predicates.add(cb.equal(root.get("direction"), direction));
        if (start != null) predicates.add(cb.greaterThanOrEqualTo(root.get("createdAt"), start));
        if (end != null) predicates.add(cb.lessThanOrEqualTo(root.get("createdAt"), end));
        if (minAmount != null) predicates.add(cb.greaterThanOrEqualTo(root.get("amount"), minAmount.intValue()));
        if (maxAmount != null) predicates.add(cb.lessThanOrEqualTo(root.get("amount"), maxAmount.intValue()));
        if (relatedId != null && !relatedId.isBlank()) predicates.add(cb.equal(root.get("relatedId"), relatedId));
        cq.where(predicates.toArray(new Predicate[0]));
        cq.orderBy(cb.desc(root.get("createdAt")));
        TypedQuery<CreditTransaction> q = entityManager.createQuery(cq);
        long total = countTransactionsAdmin(cb, userId, type, direction, start, end, minAmount, maxAmount, relatedId);
        q.setFirstResult((int) pageable.getOffset());
        q.setMaxResults(pageable.getPageSize());
        List<CreditTransaction> content = q.getResultList();
        return new org.springframework.data.domain.PageImpl<>(content, PageRequest.of(
                pageable.getPageNumber(), pageable.getPageSize(), Sort.by(Sort.Direction.DESC, "createdAt")), total);
    }

    private long countTransactionsAdmin(CriteriaBuilder cb, Long userId, CreditTransactionType type, CreditDirection direction,
                                         LocalDateTime start, LocalDateTime end, Long minAmount, Long maxAmount, String relatedId) {
        CriteriaQuery<Long> countCq = cb.createQuery(Long.class);
        Root<CreditTransaction> root = countCq.from(CreditTransaction.class);
        List<Predicate> predicates = buildTxPredicates(cb, root, userId, type, direction, start, end, minAmount, maxAmount, relatedId);
        countCq.select(cb.count(root)).where(predicates.toArray(new Predicate[0]));
        return entityManager.createQuery(countCq).getSingleResult();
    }

    /** 全站流水汇总统计（按筛选条件） */
    public Map<String, Object> summaryTransactionsAdmin(Long userId, CreditTransactionType type, CreditDirection direction,
                                                          LocalDateTime start, LocalDateTime end, Long minAmount, Long maxAmount,
                                                          String relatedId) {
        CriteriaBuilder cb = entityManager.getCriteriaBuilder();
        CriteriaQuery<Object[]> cq = cb.createQuery(Object[].class);
        Root<CreditTransaction> root = cq.from(CreditTransaction.class);
        List<Predicate> predicates = buildTxPredicates(cb, root, userId, type, direction, start, end, minAmount, maxAmount, relatedId);
        cq.multiselect(
            cb.sumAsLong(cb.<Integer>selectCase()
                .when(cb.equal(root.get("direction"), "IN"), root.get("amount"))
                .otherwise(0)),
            cb.sumAsLong(cb.<Integer>selectCase()
                .when(cb.equal(root.get("direction"), "OUT"), cb.abs(root.get("amount")))
                .otherwise(0)),
            cb.count(root)
        ).where(predicates.toArray(new Predicate[0]));
        Object[] row = entityManager.createQuery(cq).getSingleResult();
        long earned = row[0] != null ? (long) row[0] : 0;
        long spent = row[1] != null ? (long) row[1] : 0;
        long count = row[2] != null ? (long) row[2] : 0;
        Map<String, Object> m = new HashMap<>();
        m.put("totalEarned", earned);
        m.put("totalSpent", spent);
        m.put("totalNet", earned - spent);
        m.put("totalCount", count);
        return m;
    }

    private List<Predicate> buildTxPredicates(CriteriaBuilder cb, Root<CreditTransaction> root,
                                               Long userId, CreditTransactionType type, CreditDirection direction,
                                               LocalDateTime start, LocalDateTime end, Long minAmount, Long maxAmount, String relatedId) {
        List<Predicate> predicates = new ArrayList<>();
        if (userId != null) predicates.add(cb.equal(root.get("userId"), userId));
        if (type != null) predicates.add(cb.equal(root.get("type"), type));
        if (direction != null) predicates.add(cb.equal(root.get("direction"), direction));
        if (start != null) predicates.add(cb.greaterThanOrEqualTo(root.get("createdAt"), start));
        if (end != null) predicates.add(cb.lessThanOrEqualTo(root.get("createdAt"), end));
        if (minAmount != null) predicates.add(cb.greaterThanOrEqualTo(root.get("amount"), minAmount.intValue()));
        if (maxAmount != null) predicates.add(cb.lessThanOrEqualTo(root.get("amount"), maxAmount.intValue()));
        if (relatedId != null && !relatedId.isBlank()) predicates.add(cb.equal(root.get("relatedId"), relatedId));
        return predicates;
    }

    public Map<String, Object> getRewards(Long userId) {
        CreditRule rule = creditRuleService.getSafeRule();
        Integer newUserBonus = rule.getNewUserBonus() != null ? rule.getNewUserBonus() : DEFAULT_NEW_USER_BONUS;
        Integer signInPoints = rule.getSignInPoints() != null ? rule.getSignInPoints() : DEFAULT_SIGN_IN_POINTS;

        List<Map<String, Object>> items = new ArrayList<>();
        Integer bonusSum = creditTransactionRepository.sumInByUserIdAndType(userId, CreditTransactionType.NEW_USER_BONUS);
        boolean newUserDone = bonusSum != null && bonusSum > 0;

        Map<String, Object> newUser = new HashMap<>();
        newUser.put("name", "新用户福利");
        newUser.put("type", "NEW_USER_BONUS");
        Map<String, Object> newUserProgress = new HashMap<>();
        newUserProgress.put("x", newUserDone ? 1 : 0);
        newUserProgress.put("y", 1);
        newUser.put("valueOrProgress", newUserProgress);
        newUser.put("points", newUserBonus);
        newUser.put("done", newUserDone);
        items.add(newUser);

        boolean todaySigned = hasSignedInToday(userId);
        // 月卡加成计入"每日签到"的展示值：这张卡要能体现出月卡权益（双持 = 小 100 + 大 300）
        String cardCombo = resolveActiveMonthlyCardCombo(userId);
        int cardBonus = resolveActiveMonthlyCardBonus(userId);

        Map<String, Object> signIn = new HashMap<>();
        signIn.put("name", "每日签到");
        signIn.put("type", "SIGN_IN");
        Map<String, Object> signInProgress = new HashMap<>();
        signInProgress.put("x", todaySigned ? 1 : 0);
        signInProgress.put("y", 1);
        signIn.put("valueOrProgress", signInProgress);
        signIn.put("points", signInPoints + cardBonus);
        signIn.put("basePoints", signInPoints);
        signIn.put("monthlyCardBonus", cardBonus);
        signIn.put("monthlyCardTier", cardCombo);
        signIn.put("done", todaySigned);
        items.add(signIn);

        Map<String, Object> oldUser = new HashMap<>();
        oldUser.put("name", "老用户福利");
        oldUser.put("type", "OLD_USER_LOYALTY");
        Map<String, Object> oldUserProgress = new HashMap<>();
        oldUserProgress.put("x", 0);
        oldUserProgress.put("y", 1);
        oldUser.put("valueOrProgress", oldUserProgress);
        oldUser.put("points", 0);
        oldUser.put("done", false);
        items.add(oldUser);

        Map<String, Object> monthly = new HashMap<>();
        monthly.put("name", "每月登录赠送");
        monthly.put("type", "MONTHLY_LOGIN");
        LocalDate today = LocalDate.now();
        LocalDate monthStart = today.withDayOfMonth(1);
        long monthSigned = signInRecordRepository.countByUserIdAndDateRange(userId, monthStart, today);
        Map<String, Object> monthlyProgress = new HashMap<>();
        monthlyProgress.put("x", monthSigned);
        monthlyProgress.put("y", 10);
        monthly.put("valueOrProgress", monthlyProgress);
        monthly.put("points", 0);
        monthly.put("done", monthSigned >= 10);
        items.add(monthly);

        Map<String, Object> result = new HashMap<>();
        result.put("items", items);
        return result;
    }

    public long sumIncome(Long userId, CreditTransactionType type, CreditDirection direction,
                           LocalDateTime start, LocalDateTime end, String relatedId) {
        return creditTransactionRepository.sumIncomeByFilters(userId, type, direction, start, end, relatedId);
    }

    public long sumSpend(Long userId, CreditTransactionType type, CreditDirection direction,
                          LocalDateTime start, LocalDateTime end, String relatedId) {
        return creditTransactionRepository.sumSpendByFilters(userId, type, direction, start, end, relatedId);
    }

    /**
     * 管理员调账：amount 为正走 ADMIN_GRANT 发放，为负走 ADMIN_DEDUCT 扣减（行锁防透支）。
     * 双向操作：正数调用 grantPoints，负数走悲观行锁扣减并在余额不足时抛 INSUFFICIENT_CREDITS。
     * 审计：adminUserId 写入流水 adminUserId 字段，便于追溯操作人。
     * 事务边界：余额变更与 OUT/IN 流水同事务落库。
     */
    @Transactional
    public CreditTransaction adminAdjust(Long userId, int amount, String remark, Long adminUserId) {
        CreditTransactionType type = amount >= 0 ? CreditTransactionType.ADMIN_GRANT : CreditTransactionType.ADMIN_DEDUCT;
        if (amount >= 0) {
            return grantPoints(userId, amount, type, remark, null, adminUserId);
        } else {
            UserCredit account = userCreditRepository.findByUserIdWithLock(userId)
                    .orElseThrow(() -> new BizException("积分账户不存在"));
            int absAmount = Math.abs(amount);
            if (account.getBalance() < absAmount) {
                throw new BizException(400, CreditErrorCode.INSUFFICIENT_CREDITS,
                        "积分不足，当前余额: " + account.getBalance());
            }
            int newBalance = account.getBalance() - absAmount;
            account.setBalance(newBalance);
            account.setTotalSpent(account.getTotalSpent() + absAmount);

            CreditTransaction tx = new CreditTransaction();
            tx.setUserId(userId);
            tx.setType(type);
            tx.setDirection(CreditDirection.OUT);
            tx.setAmount(absAmount);
            tx.setBalanceAfter(newBalance);
            tx.setRemark(remark);
            tx.setAdminUserId(adminUserId);
            creditTransactionRepository.save(tx);
            userCreditRepository.save(account);
            log.info("管理员{} 调整用户{} 积分: {}", adminUserId, userId, amount);
            return tx;
        }
    }

    public List<CreditTransaction> findRelatedTransactions(String relatedId) {
        List<CreditTransaction> list = creditTransactionRepository.findByRelatedIdOrderByCreatedAtAsc(relatedId);
        return list != null ? list : Collections.emptyList();
    }

    private int safeNewUserBonus() {
        CreditRule rule = creditRuleService.getSafeRule();
        Integer value = rule != null ? rule.getNewUserBonus() : null;
        return value != null && value > 0 ? value : DEFAULT_NEW_USER_BONUS;
    }

    private int safeSignInPoints() {
        CreditRule rule = creditRuleService.getSafeRule();
        Integer value = rule != null ? rule.getSignInPoints() : null;
        return value != null && value > 0 ? value : DEFAULT_SIGN_IN_POINTS;
    }
}

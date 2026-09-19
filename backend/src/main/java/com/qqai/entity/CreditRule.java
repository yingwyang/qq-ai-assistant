package com.qqai.entity;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
@Table(name = "credit_rule")
public class CreditRule {

    @Id
    private Long id = 1L;

    @Column(nullable = false)
    private Integer newUserBonus = 500;

    @Column(nullable = false)
    private Integer signInPoints = 150;

    @Column(nullable = false)
    private Integer tokenUnit = 1000;

    @Column(nullable = false)
    private Integer promptRate = 2;

    @Column(nullable = false)
    private Integer completionRate = 4;

    @Column(nullable = false)
    private Integer minCost = 5;

    @Column(nullable = false)
    private Integer defaultCostPerMsg = 10;

    @Column(nullable = false)
    private Boolean adminFree = true;

    @Column(nullable = false, precision = 10, scale = 2)
    private BigDecimal planLitePrice = new BigDecimal("9.9");

    @Column(nullable = false)
    private Integer planLiteCredit = 2000;

    @Column(nullable = false, precision = 10, scale = 2)
    private BigDecimal planProPrice = new BigDecimal("59");

    @Column(nullable = false)
    private Integer planProCredit = 4000;

    @Column(nullable = false, precision = 10, scale = 2)
    private BigDecimal planProPlusPrice = new BigDecimal("219");

    @Column(nullable = false)
    private Integer planProPlusCredit = 12000;

    @Column(nullable = false, precision = 10, scale = 2)
    private BigDecimal planUltraPrice = new BigDecimal("629");

    @Column(nullable = false)
    private Integer planUltraCredit = 40000;

    @Column(nullable = false)
    private Integer planDurationDays = 30;

    // ==================== 会员月卡 / MEGA ====================
    // 原先这几个数字硬编码在 SubscriptionsController / SubscriptionService / CreditService 里，
    // 2026-09-19 起进表单可调。命名与客户端订阅页一致：小月卡 / 大月卡 / 直购积分·100000（MEGA）。
    // 双持每日加成不单独配置：按「小月卡 + 大月卡」叠加算，避免出现"双持反而更少"的矛盾配置。

    /** 小月卡价格（元） */
    @Column(precision = 10, scale = 2)
    private BigDecimal planSmallMonthCardPrice = new BigDecimal("30");

    /** 小月卡赠送积分 */
    @Column(nullable = false)
    private Integer planSmallMonthCardCredit = 3000;

    /** 小月卡每日签到额外积分 */
    @Column(nullable = false)
    private Integer planSmallMonthCardDailyBonus = 100;

    /** 大月卡价格（元） */
    @Column(precision = 10, scale = 2)
    private BigDecimal planLargeMonthCardPrice = new BigDecimal("68");

    /** 大月卡赠送积分 */
    @Column(nullable = false)
    private Integer planLargeMonthCardCredit = 8000;

    /** 大月卡每日签到额外积分 */
    @Column(nullable = false)
    private Integer planLargeMonthCardDailyBonus = 300;

    /** 直购积分·100000（MEGA）价格（元） */
    @Column(precision = 10, scale = 2)
    private BigDecimal planMegaPrice = new BigDecimal("648");

    /** 直购积分·100000（MEGA）赠送积分 */
    @Column(nullable = false)
    private Integer planMegaCredit = 100000;

    // ====== 模型分级费率（JSON：{"default":1.0, "gpt-4":3.0, "qwen-7b":1.0}） ======
    @Column(length = 2000)
    private String modelRates = "{\"default\":1.0}";

    // ====== 多模态输入：每张图片额外消耗积分 ======
    @Column(nullable = false)
    private Integer imageExtraCost = 5;

    // ====== AI 分析精细化 ======
    // 分析基础费用（不含消息条数增量）
    @Column(nullable = false)
    private Integer analyzeBaseCost = 10;
    // 每条消息增量费用（分析的消息条数 × 此值）
    @Column(nullable = false)
    private Integer analyzeCostPerMsg = 1;
    // 分析类型倍率 JSON：{"default":1.0, "summary":1.0, "analysis":1.5}
    @Column(length = 2000)
    private String analyzeTypeRates = "{\"default\":1.0}";

    // ====== TTS 语音合成 ======
    // 每多少字符扣 1 积分
    @Column(nullable = false)
    private Integer ttsCharsPerCredit = 50;
    // 每次 TTS 最小消耗积分
    @Column(nullable = false)
    private Integer ttsMinCost = 2;

    // ====== 月度免费配额 + 阶梯定价 ======
    // 每月免费次数（0=不免费），仅对 AI_CHAT 场景生效
    @Column(nullable = false)
    private Integer monthlyFreeQuota = 0;
    // 超过免费配额后的费率倍数（1.0=不涨价，1.5=涨50%）
    @Column(nullable = false)
    private Double overtaxRate = 1.5;

    // ====== 月卡消费折扣（按账号当前 tier 应用） ======
    // 小月卡折扣（0.9=9折，1.0=无折扣）
    @Column(nullable = false)
    private Double smallMonthCardDiscount = 0.9;
    // 大月卡折扣（0.8=8折）
    @Column(nullable = false)
    private Double largeMonthCardDiscount = 0.8;
    // ALL 状态折扣（0.7=7折）
    @Column(nullable = false)
    private Double allTierDiscount = 0.7;

    // ====== 会话上下文长度增量计费 ======
    // 每条历史消息额外消耗积分（0=不增量）
    @Column(nullable = false)
    private Integer contextExtraCostPerMsg = 1;
    // 前N条上下文消息免费（0=不免费，10=前10条不增量）
    @Column(nullable = false)
    private Integer contextFreeMsgCount = 10;

    // ====== 每日封顶消费保护 ======
    // 单日累计消耗上限（0=不限），超过则本次免费
    @Column(nullable = false)
    private Integer dailyCapCost = 0;

    // ====== 月度阶梯累计折扣 ======
    // JSON 阶梯映射，按累计消耗匹配最低阈值，取其折扣
    // 例：{"1000":0.95,"5000":0.9,"20000":0.85} 表示累计消耗≥1000时95折，≥5000时9折，≥20000时85折
    @Column(length = 2000)
    private String tieredDiscountThresholds = "{\"1000\":0.95,\"5000\":0.9,\"20000\":0.85}";

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        updatedAt = LocalDateTime.now();
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public Integer getNewUserBonus() { return newUserBonus; }
    public void setNewUserBonus(Integer newUserBonus) { this.newUserBonus = newUserBonus; }

    public Integer getSignInPoints() { return signInPoints; }
    public void setSignInPoints(Integer signInPoints) { this.signInPoints = signInPoints; }

    public Integer getTokenUnit() { return tokenUnit; }
    public void setTokenUnit(Integer tokenUnit) { this.tokenUnit = tokenUnit; }

    public Integer getPromptRate() { return promptRate; }
    public void setPromptRate(Integer promptRate) { this.promptRate = promptRate; }

    public Integer getCompletionRate() { return completionRate; }
    public void setCompletionRate(Integer completionRate) { this.completionRate = completionRate; }

    public Integer getMinCost() { return minCost; }
    public void setMinCost(Integer minCost) { this.minCost = minCost; }

    public Integer getDefaultCostPerMsg() { return defaultCostPerMsg; }
    public void setDefaultCostPerMsg(Integer defaultCostPerMsg) { this.defaultCostPerMsg = defaultCostPerMsg; }

    public Boolean getAdminFree() { return adminFree; }
    public void setAdminFree(Boolean adminFree) { this.adminFree = adminFree; }

    public BigDecimal getPlanLitePrice() { return planLitePrice; }
    public void setPlanLitePrice(BigDecimal planLitePrice) { this.planLitePrice = planLitePrice; }

    public Integer getPlanLiteCredit() { return planLiteCredit; }
    public void setPlanLiteCredit(Integer planLiteCredit) { this.planLiteCredit = planLiteCredit; }

    public BigDecimal getPlanProPrice() { return planProPrice; }
    public void setPlanProPrice(BigDecimal planProPrice) { this.planProPrice = planProPrice; }

    public Integer getPlanProCredit() { return planProCredit; }
    public void setPlanProCredit(Integer planProCredit) { this.planProCredit = planProCredit; }

    public BigDecimal getPlanProPlusPrice() { return planProPlusPrice; }
    public void setPlanProPlusPrice(BigDecimal planProPlusPrice) { this.planProPlusPrice = planProPlusPrice; }

    public Integer getPlanProPlusCredit() { return planProPlusCredit; }
    public void setPlanProPlusCredit(Integer planProPlusCredit) { this.planProPlusCredit = planProPlusCredit; }

    public BigDecimal getPlanUltraPrice() { return planUltraPrice; }
    public void setPlanUltraPrice(BigDecimal planUltraPrice) { this.planUltraPrice = planUltraPrice; }

    public Integer getPlanUltraCredit() { return planUltraCredit; }
    public void setPlanUltraCredit(Integer planUltraCredit) { this.planUltraCredit = planUltraCredit; }

    public Integer getPlanDurationDays() { return planDurationDays; }
    public void setPlanDurationDays(Integer planDurationDays) { this.planDurationDays = planDurationDays; }

    // ====== 会员月卡 / MEGA ======
    public BigDecimal getPlanSmallMonthCardPrice() { return planSmallMonthCardPrice; }
    public void setPlanSmallMonthCardPrice(BigDecimal v) { this.planSmallMonthCardPrice = v; }

    public Integer getPlanSmallMonthCardCredit() { return planSmallMonthCardCredit; }
    public void setPlanSmallMonthCardCredit(Integer v) { this.planSmallMonthCardCredit = v; }

    public Integer getPlanSmallMonthCardDailyBonus() { return planSmallMonthCardDailyBonus; }
    public void setPlanSmallMonthCardDailyBonus(Integer v) { this.planSmallMonthCardDailyBonus = v; }

    public BigDecimal getPlanLargeMonthCardPrice() { return planLargeMonthCardPrice; }
    public void setPlanLargeMonthCardPrice(BigDecimal v) { this.planLargeMonthCardPrice = v; }

    public Integer getPlanLargeMonthCardCredit() { return planLargeMonthCardCredit; }
    public void setPlanLargeMonthCardCredit(Integer v) { this.planLargeMonthCardCredit = v; }

    public Integer getPlanLargeMonthCardDailyBonus() { return planLargeMonthCardDailyBonus; }
    public void setPlanLargeMonthCardDailyBonus(Integer v) { this.planLargeMonthCardDailyBonus = v; }

    public BigDecimal getPlanMegaPrice() { return planMegaPrice; }
    public void setPlanMegaPrice(BigDecimal v) { this.planMegaPrice = v; }

    public Integer getPlanMegaCredit() { return planMegaCredit; }
    public void setPlanMegaCredit(Integer v) { this.planMegaCredit = v; }

    public String getModelRates() { return modelRates; }
    public void setModelRates(String modelRates) { this.modelRates = modelRates; }

    public Integer getImageExtraCost() { return imageExtraCost; }
    public void setImageExtraCost(Integer imageExtraCost) { this.imageExtraCost = imageExtraCost; }

    public Integer getAnalyzeBaseCost() { return analyzeBaseCost; }
    public void setAnalyzeBaseCost(Integer analyzeBaseCost) { this.analyzeBaseCost = analyzeBaseCost; }

    public Integer getAnalyzeCostPerMsg() { return analyzeCostPerMsg; }
    public void setAnalyzeCostPerMsg(Integer analyzeCostPerMsg) { this.analyzeCostPerMsg = analyzeCostPerMsg; }

    public String getAnalyzeTypeRates() { return analyzeTypeRates; }
    public void setAnalyzeTypeRates(String analyzeTypeRates) { this.analyzeTypeRates = analyzeTypeRates; }

    public Integer getTtsCharsPerCredit() { return ttsCharsPerCredit; }
    public void setTtsCharsPerCredit(Integer ttsCharsPerCredit) { this.ttsCharsPerCredit = ttsCharsPerCredit; }

    public Integer getTtsMinCost() { return ttsMinCost; }
    public void setTtsMinCost(Integer ttsMinCost) { this.ttsMinCost = ttsMinCost; }

    public Integer getMonthlyFreeQuota() { return monthlyFreeQuota; }
    public void setMonthlyFreeQuota(Integer monthlyFreeQuota) { this.monthlyFreeQuota = monthlyFreeQuota; }

    public Double getOvertaxRate() { return overtaxRate; }
    public void setOvertaxRate(Double overtaxRate) { this.overtaxRate = overtaxRate; }

    public Double getSmallMonthCardDiscount() { return smallMonthCardDiscount; }
    public void setSmallMonthCardDiscount(Double smallMonthCardDiscount) { this.smallMonthCardDiscount = smallMonthCardDiscount; }

    public Double getLargeMonthCardDiscount() { return largeMonthCardDiscount; }
    public void setLargeMonthCardDiscount(Double largeMonthCardDiscount) { this.largeMonthCardDiscount = largeMonthCardDiscount; }

    public Double getAllTierDiscount() { return allTierDiscount; }
    public void setAllTierDiscount(Double allTierDiscount) { this.allTierDiscount = allTierDiscount; }

    public Integer getContextExtraCostPerMsg() { return contextExtraCostPerMsg; }
    public void setContextExtraCostPerMsg(Integer contextExtraCostPerMsg) { this.contextExtraCostPerMsg = contextExtraCostPerMsg; }

    public Integer getContextFreeMsgCount() { return contextFreeMsgCount; }
    public void setContextFreeMsgCount(Integer contextFreeMsgCount) { this.contextFreeMsgCount = contextFreeMsgCount; }

    public Integer getDailyCapCost() { return dailyCapCost; }
    public void setDailyCapCost(Integer dailyCapCost) { this.dailyCapCost = dailyCapCost; }

    public String getTieredDiscountThresholds() { return tieredDiscountThresholds; }
    public void setTieredDiscountThresholds(String tieredDiscountThresholds) { this.tieredDiscountThresholds = tieredDiscountThresholds; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }

    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}

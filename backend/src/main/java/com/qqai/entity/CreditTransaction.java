package com.qqai.entity;

import com.qqai.entity.enums.CreditDirection;
import com.qqai.entity.enums.CreditTransactionType;
import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "credit_transaction", indexes = {
    @Index(name = "idx_tx_user_id", columnList = "userId"),
    @Index(name = "idx_tx_user_type", columnList = "userId,type"),
    @Index(name = "idx_tx_created_at", columnList = "createdAt"),
    @Index(name = "idx_tx_related_id", columnList = "relatedId"),
    @Index(name = "idx_tx_user_created", columnList = "userId,createdAt")
})
public class CreditTransaction {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long userId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private CreditTransactionType type;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private CreditDirection direction;

    @Column(nullable = false)
    private Integer amount;

    @Column(nullable = false)
    private Integer balanceAfter;

    @Column(length = 500)
    private String remark;

    @Column(length = 100)
    private String relatedId;

    private Long adminUserId;

    /**
     * 现金变动（元），<b>带符号</b>：正=收入、负=支出、null=这笔没有现金影响。
     *
     * <p>本项目没有接真实支付，现金是<b>模拟</b>的：订阅购买按套餐价、退款按退款比例、
     * AI/TTS 消耗按积分成本折算（见 {@code CashLedgerService}）；管理员也可手工记一笔
     * （{@code cashCategory=MANUAL}）。除手工记账外都不落库，读取时推算，历史数据无需回填。</p>
     */
    @Column(precision = 12, scale = 2)
    private java.math.BigDecimal cashAmount;

    /** 现金类别：SUBSCRIPTION / REFUND / AI_COST / MANUAL（空=无现金影响） */
    @Column(length = 30)
    private String cashCategory;

    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public Long getUserId() { return userId; }
    public void setUserId(Long userId) { this.userId = userId; }

    public CreditTransactionType getType() { return type; }
    public void setType(CreditTransactionType type) { this.type = type; }

    public CreditDirection getDirection() { return direction; }
    public void setDirection(CreditDirection direction) { this.direction = direction; }

    public Integer getAmount() { return amount; }
    public void setAmount(Integer amount) { this.amount = amount; }

    public Integer getBalanceAfter() { return balanceAfter; }
    public void setBalanceAfter(Integer balanceAfter) { this.balanceAfter = balanceAfter; }

    public String getRemark() { return remark; }
    public void setRemark(String remark) { this.remark = remark; }

    public String getRelatedId() { return relatedId; }
    public void setRelatedId(String relatedId) { this.relatedId = relatedId; }

    public Long getAdminUserId() { return adminUserId; }
    public void setAdminUserId(Long adminUserId) { this.adminUserId = adminUserId; }

    public java.math.BigDecimal getCashAmount() { return cashAmount; }
    public void setCashAmount(java.math.BigDecimal cashAmount) { this.cashAmount = cashAmount; }

    public String getCashCategory() { return cashCategory; }
    public void setCashCategory(String cashCategory) { this.cashCategory = cashCategory; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}

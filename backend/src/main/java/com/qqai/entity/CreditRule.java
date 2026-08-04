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

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }

    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}

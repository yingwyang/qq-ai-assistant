package com.qqai.service;

import com.qqai.entity.SubscriptionOrder;
import com.qqai.entity.UserCredit;
import com.qqai.entity.enums.OrderStatus;
import com.qqai.entity.enums.SubscriptionTier;
import com.qqai.repository.SubscriptionOrderRepository;
import com.qqai.repository.UserCreditRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

@Service
public class SubscriptionExpireScheduler {

    private static final Logger log = LoggerFactory.getLogger(SubscriptionExpireScheduler.class);

    @Autowired
    private SubscriptionOrderRepository subscriptionOrderRepository;

    @Autowired
    private UserCreditRepository userCreditRepository;

    @Scheduled(cron = "0 0 2 * * ?")
    @Transactional
    public void runScheduled() {
        log.info("[SubscriptionExpireScheduler] 定时任务开始执行");
        int expiredCount = runOnce();
        log.info("[SubscriptionExpireScheduler] 定时任务完成，处理了{}个过期订单", expiredCount);
    }

    /**
     * 扫描过期订阅：将 PAID 且 expiresAt < now 的订单标记为 EXPIRED，并把对应用户 tier 回退为 FREE。
     * 事务边界：单事务内处理所有过期订单（订单状态 + 用户 tier 同时落库），单条失败仅记日志不中断整体。
     * tier 回退条件：仅当用户当前 tier 与订单 planTier 一致且 currentExpiresAt 已过期时才降级，避免误降其他套餐用户。
     * 触发方式：默认由 runScheduled() 通过 cron(每天 02:00) 调用，也可由管理员手动调用本方法做即时巡检。
     * 审计：通过 log.info 记录每次降级动作（用户、tier 变更、订单号）。
     */
    @Transactional
    public int runOnce() {
        LocalDateTime now = LocalDateTime.now();
        List<SubscriptionOrder> expiredOrders = subscriptionOrderRepository
                .findExpiredOrders(OrderStatus.PAID, now);

        int count = 0;
        for (SubscriptionOrder order : expiredOrders) {
            try {
                order.setStatus(OrderStatus.EXPIRED);
                subscriptionOrderRepository.save(order);

                UserCredit account = userCreditRepository.findByUserId(order.getUserId()).orElse(null);
                if (account != null) {
                    SubscriptionTier currentTier = account.getSubscriptionTier();
                    LocalDateTime currentExpiresAt = account.getSubscriptionExpiresAt();
                    if (currentTier == order.getPlanTier()
                            && currentExpiresAt != null
                            && !currentExpiresAt.isAfter(now)) {
                        account.setSubscriptionTier(SubscriptionTier.FREE);
                        account.setSubscriptionExpiresAt(null);
                        userCreditRepository.save(account);
                        log.info("用户{} 订阅过期 tier={}→FREE orderNo={}",
                                order.getUserId(), currentTier, order.getOrderNo());
                    }
                }
                count++;
            } catch (Exception e) {
                log.error("处理订单过期失败 orderNo={}", order.getOrderNo(), e);
            }
        }
        return count;
    }
}

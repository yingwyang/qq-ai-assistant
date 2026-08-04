package com.qqai.service;

import com.qqai.entity.CreditRule;
import com.qqai.entity.SubscriptionOrder;
import com.qqai.entity.User;
import com.qqai.entity.UserCredit;
import com.qqai.entity.enums.CreditTransactionType;
import com.qqai.entity.enums.OrderStatus;
import com.qqai.entity.enums.SubscriptionTier;
import com.qqai.exception.BizException;
import com.qqai.exception.CreditErrorCode;
import com.qqai.repository.CreditTransactionRepository;
import com.qqai.repository.SubscriptionOrderRepository;
import com.qqai.repository.UserRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.TypedQuery;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

@Service
public class SubscriptionService {

    private static final Logger log = LoggerFactory.getLogger(SubscriptionService.class);

    private static final long IDEMPOTENCY_WINDOW_SECONDS = 60L;

    @Autowired
    private SubscriptionOrderRepository subscriptionOrderRepository;

    @Autowired
    private CreditTransactionRepository creditTransactionRepository;

    @Autowired
    private CreditService creditService;

    @Autowired
    private CreditRuleService creditRuleService;

    @Autowired
    private OrderNoGenerator orderNoGenerator;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private EntityManager entityManager;

    /**
     * 创建订阅订单：60 秒幂等窗口防重复下单。
     * 幂等策略：在 (userId, planTier, IDEMPOTENCY_WINDOW_SECONDS) 窗口内若存在 PENDING/PAID 订单，
     * 直接返回既有订单而非新建，避免用户重复点击或网络重试产生多单。
     * 事务边界：订单写入单事务；幂等查询与写入不在同一锁范围，极端并发可能仍生成两单，由支付环节状态校验兜底。
     */
    @Transactional
    public SubscriptionOrder createOrder(Long userId, SubscriptionTier planTier, String clientIp, String userAgent) {
        validatePlanTier(planTier);

        LocalDateTime idempotencySince = LocalDateTime.now().minusSeconds(IDEMPOTENCY_WINDOW_SECONDS);
        List<SubscriptionOrder> recent = subscriptionOrderRepository.findRecentByUserAndPlan(userId, planTier, idempotencySince);
        if (!recent.isEmpty()) {
            SubscriptionOrder existing = recent.get(0);
            if (existing.getStatus() == OrderStatus.PENDING || existing.getStatus() == OrderStatus.PAID) {
                log.info("订单幂等命中 user={} plan={} orderNo={}", userId, planTier, existing.getOrderNo());
                return existing;
            }
        }

        CreditRule rule = creditRuleService.getRule();
        BigDecimal price = getPlanPrice(planTier, rule);
        Integer credit = getPlanCredit(planTier, rule);
        Integer durationDays = rule.getPlanDurationDays();

        SubscriptionOrder order = new SubscriptionOrder();
        order.setOrderNo(orderNoGenerator.generate());
        order.setUserId(userId);
        order.setPlanTier(planTier);
        order.setPrice(price);
        order.setCreditAmount(credit);
        order.setDurationDays(durationDays);
        order.setStatus(OrderStatus.PENDING);
        order.setClientIp(clientIp);
        order.setUserAgent(userAgent);
        SubscriptionOrder saved = subscriptionOrderRepository.save(order);
        log.info("订单已创建 user={} plan={} orderNo={}", userId, planTier, saved.getOrderNo());
        return saved;
    }

    /**
     * 订单支付成功标记：单事务内完成 PENDING→PAID + 发放 SUBSCRIPTION_PURCHASE 流水 + 更新 tier/expiresAt。
     * 一致性：任一步骤失败整体回滚（支付状态、积分发放、订阅等级三者原子）。
     * 等级叠加：若当前订阅未过期，新套餐到期时间在原 expiresAt 基础上叠加，避免用户损失剩余时长。
     * 幂等：仅 PENDING 订单可标记 PAID，重复回调抛 ORDER_STATUS_INVALID。
     */
    @Transactional
    public SubscriptionOrder markPaid(String orderNo, String paymentMethod, String paymentTransactionId) {
        SubscriptionOrder order = subscriptionOrderRepository.findByOrderNo(orderNo)
                .orElseThrow(() -> new BizException(404, CreditErrorCode.ORDER_NOT_FOUND, "订单不存在: " + orderNo));

        if (order.getStatus() != OrderStatus.PENDING) {
            throw new BizException(400, CreditErrorCode.ORDER_STATUS_INVALID,
                    "订单状态不正确，当前状态: " + order.getStatus());
        }

        order.setStatus(OrderStatus.PAID);
        order.setPaymentMethod(paymentMethod);
        order.setPaymentTransactionId(paymentTransactionId);
        LocalDateTime now = LocalDateTime.now();
        order.setPaidAt(now);
        order.setExpiresAt(now.plusDays(order.getDurationDays()));

        creditService.grantPoints(order.getUserId(), order.getCreditAmount(),
                CreditTransactionType.SUBSCRIPTION_PURCHASE,
                "订阅套餐: " + order.getPlanTier(),
                orderNo, null);

        UserCredit account = creditService.ensureAccount(order.getUserId());
        SubscriptionTier currentTier = account.getSubscriptionTier();
        LocalDateTime currentExpiresAt = account.getSubscriptionExpiresAt();
        LocalDateTime baseTime = (currentExpiresAt != null && currentExpiresAt.isAfter(now))
                ? currentExpiresAt : now;

        if (isHigherTier(order.getPlanTier(), currentTier)
                || (order.getPlanTier() == currentTier && currentTier != SubscriptionTier.FREE)) {
            account.setSubscriptionTier(order.getPlanTier());
            account.setSubscriptionExpiresAt(baseTime.plusDays(order.getDurationDays()));
        }

        subscriptionOrderRepository.save(order);
        log.info("订单已支付 orderNo={} plan={} credits={}", orderNo, order.getPlanTier(), order.getCreditAmount());

        return order;
    }

    @Transactional
    public SubscriptionOrder cancelOrder(String orderNo, Long adminUserId, String reason) {
        SubscriptionOrder order = subscriptionOrderRepository.findByOrderNo(orderNo)
                .orElseThrow(() -> new BizException(404, CreditErrorCode.ORDER_NOT_FOUND, "订单不存在: " + orderNo));
        if (order.getStatus() != OrderStatus.PENDING) {
            throw new BizException(400, CreditErrorCode.ORDER_STATUS_INVALID,
                    "仅待支付订单可取消，当前状态: " + order.getStatus());
        }
        order.setStatus(OrderStatus.CANCELLED);
        if (reason != null && !reason.isBlank()) {
            String metadata = order.getMetadata();
            String extra = "cancelReason=" + reason + (adminUserId != null ? ";cancelBy=" + adminUserId : "");
            order.setMetadata(metadata == null ? extra : metadata + ";" + extra);
        }
        SubscriptionOrder saved = subscriptionOrderRepository.save(order);
        log.info("订单已取消 orderNo={} admin={}", orderNo, adminUserId);
        return saved;
    }

    /**
     * 全额退款：等价于 refundOrderWithRatio(ratio=1.0)。
     */
    @Transactional
    public SubscriptionOrder refundOrder(String orderNo, String refundReason, Long adminUserId) {
        return refundOrderWithRatio(orderNo, refundReason, adminUserId, 1.0);
    }

    /**
     * 退款：PAID→REFUNDED 事务内回退积分（写 REFUND 类型 OUT 流水）+ 订单元数据更新 + tier 回退。
     * 幂等：订单已 REFUNDED 抛 ALREADY_REFUNDED；同时通过 REFUND-{orderNo}[-R{ratio}] 关联 ID 检查是否已存在退款流水，避免重复扣分。
     * 事务边界：积分扣减、订单状态变更、tier 回退三者同事务，任一失败整体回滚。
     * tier 回退：当前 tier 与订单 planTier 一致时按比例减少订阅时长，回退后过期则降为 FREE。
     */
    @Transactional
    public SubscriptionOrder refundOrderWithRatio(String orderNo, String refundReason, Long adminUserId, double ratio) {
        SubscriptionOrder order = subscriptionOrderRepository.findByOrderNo(orderNo)
                .orElseThrow(() -> new BizException(404, CreditErrorCode.ORDER_NOT_FOUND, "订单不存在: " + orderNo));

        if (order.getStatus() == OrderStatus.REFUNDED) {
            throw new BizException(400, CreditErrorCode.ALREADY_REFUNDED, "订单已退款");
        }
        if (order.getStatus() != OrderStatus.PAID) {
            throw new BizException(400, CreditErrorCode.ORDER_STATUS_INVALID,
                    "仅已支付订单可退款，当前状态: " + order.getStatus());
        }

        if (ratio <= 0 || ratio > 1.0) ratio = 1.0;
        String refundRelatedId = ratio >= 1.0
                ? ("REFUND-" + orderNo)
                : ("REFUND-" + orderNo + "-R" + new BigDecimal(ratio).setScale(2, RoundingMode.HALF_UP).toString().replace(".", ""));
        long refundTxCount = creditTransactionRepository.countByUserIdAndRelatedId(order.getUserId(), refundRelatedId);
        if (refundTxCount > 0) {
            throw new BizException(400, CreditErrorCode.ALREADY_REFUNDED, "订单已执行退款流水");
        }

        int totalCredit = order.getCreditAmount() == null ? 0 : order.getCreditAmount();
        int refundPoints = (int) Math.round(totalCredit * ratio);
        if (refundPoints > 0) {
            creditService.spendPoints(order.getUserId(), refundPoints,
                    CreditTransactionType.REFUND,
                    "订单退款(ratio=" + ratio + "): " + orderNo + " " + (refundReason == null ? "" : refundReason),
                    refundRelatedId);
        }

        order.setStatus(OrderStatus.REFUNDED);
        order.setRefundedAt(LocalDateTime.now());
        BigDecimal price = order.getPrice() == null ? BigDecimal.ZERO : order.getPrice();
        order.setRefundAmount(price.multiply(new BigDecimal(ratio)).setScale(2, RoundingMode.HALF_UP));
        order.setRefundReason(refundReason);
        order.setRefundAdminUserId(adminUserId);

        downgradeTierAfterRefund(order, ratio);

        subscriptionOrderRepository.save(order);
        log.info("订单已退款 orderNo={} refundAmount={} refundPoints={} ratio={} admin={}",
                orderNo, order.getRefundAmount(), refundPoints, ratio, adminUserId);
        return order;
    }

    public int refundedPointsLastRefund(SubscriptionOrder order, double ratio) {
        int totalCredit = order.getCreditAmount() == null ? 0 : order.getCreditAmount();
        return (int) Math.round(totalCredit * (ratio <= 0 || ratio > 1.0 ? 1.0 : ratio));
    }

    private void downgradeTierAfterRefund(SubscriptionOrder order, double ratio) {
        UserCredit account = creditService.ensureAccount(order.getUserId());
        SubscriptionTier currentTier = account.getSubscriptionTier();
        LocalDateTime currentExpiresAt = account.getSubscriptionExpiresAt();
        if (currentTier == order.getPlanTier() && currentExpiresAt != null) {
            int daysToReduce = (int) Math.round(order.getDurationDays() * ratio);
            LocalDateTime rolledBack = currentExpiresAt.minusDays(daysToReduce);
            if (rolledBack.isBefore(LocalDateTime.now())) {
                account.setSubscriptionTier(SubscriptionTier.FREE);
                account.setSubscriptionExpiresAt(null);
            } else {
                account.setSubscriptionExpiresAt(rolledBack);
            }
        }
    }

    public Optional<SubscriptionOrder> findByOrderNo(String orderNo) {
        return subscriptionOrderRepository.findByOrderNo(orderNo);
    }

    public Page<SubscriptionOrder> pageByUser(Long userId, Pageable pageable) {
        return subscriptionOrderRepository.findByUserIdOrderByCreatedAtDesc(userId, pageable);
    }

    public Page<SubscriptionOrder> pageByUserAndStatuses(Long userId, List<OrderStatus> statuses, Pageable pageable) {
        if (statuses == null || statuses.isEmpty()) {
            return pageByUser(userId, pageable);
        }
        if (statuses.size() == 1) {
            return subscriptionOrderRepository.findByUserIdAndStatusOrderByCreatedAtDesc(userId, statuses.get(0), pageable);
        }
        return subscriptionOrderRepository.findByUserIdAndStatusInOrderByCreatedAtDesc(userId, statuses, pageable);
    }

    public Page<SubscriptionOrder> pageAdmin(Pageable pageable) {
        return subscriptionOrderRepository.findAllByOrderByCreatedAtDesc(pageable);
    }

    public Page<SubscriptionOrder> pageAdminByStatus(OrderStatus status, Pageable pageable) {
        return subscriptionOrderRepository.findByStatusOrderByCreatedAtDesc(status, pageable);
    }

    public Page<SubscriptionOrder> pageAdminAdvanced(String orderNo, String keyword, List<OrderStatus> statuses,
                                                      LocalDateTime start, LocalDateTime end,
                                                      BigDecimal minPrice, BigDecimal maxPrice,
                                                      Pageable pageable) {
        List<Long> matchedUserIds = null;
        if (keyword != null && !keyword.isBlank()) {
            Page<User> users = userRepository.findByUsernameContainingOrNicknameContaining(
                    keyword, keyword, PageRequest.of(0, 500));
            matchedUserIds = users.getContent().stream().map(User::getId).collect(Collectors.toList());
            if (matchedUserIds.isEmpty()) {
                return new PageImpl<>(new ArrayList<>(), pageable, 0);
            }
        }

        CriteriaBuilder cb = entityManager.getCriteriaBuilder();
        CriteriaQuery<SubscriptionOrder> cq = cb.createQuery(SubscriptionOrder.class);
        Root<SubscriptionOrder> root = cq.from(SubscriptionOrder.class);
        List<Predicate> predicates = new ArrayList<>();
        if (orderNo != null && !orderNo.isBlank()) {
            predicates.add(cb.like(root.get("orderNo"), "%" + orderNo + "%"));
        }
        if (matchedUserIds != null) {
            predicates.add(root.get("userId").in(matchedUserIds));
        }
        if (statuses != null && !statuses.isEmpty()) {
            predicates.add(root.get("status").in(statuses));
        }
        if (start != null) predicates.add(cb.greaterThanOrEqualTo(root.get("createdAt"), start));
        if (end != null) predicates.add(cb.lessThanOrEqualTo(root.get("createdAt"), end));
        if (minPrice != null) predicates.add(cb.greaterThanOrEqualTo(root.get("price"), minPrice));
        if (maxPrice != null) predicates.add(cb.lessThanOrEqualTo(root.get("price"), maxPrice));
        cq.where(predicates.toArray(new Predicate[0]));
        cq.orderBy(cb.desc(root.get("createdAt")));

        TypedQuery<SubscriptionOrder> q = entityManager.createQuery(cq);
        long total = countAdminAdvanced(cb, orderNo, matchedUserIds, statuses, start, end, minPrice, maxPrice);
        q.setFirstResult((int) pageable.getOffset());
        q.setMaxResults(pageable.getPageSize());
        List<SubscriptionOrder> content = q.getResultList();
        return new PageImpl<>(content, PageRequest.of(pageable.getPageNumber(), pageable.getPageSize(),
                Sort.by(Sort.Direction.DESC, "createdAt")), total);
    }

    private long countAdminAdvanced(CriteriaBuilder cb, String orderNo, List<Long> matchedUserIds,
                                     List<OrderStatus> statuses, LocalDateTime start, LocalDateTime end,
                                     BigDecimal minPrice, BigDecimal maxPrice) {
        CriteriaQuery<Long> countCq = cb.createQuery(Long.class);
        Root<SubscriptionOrder> root = countCq.from(SubscriptionOrder.class);
        List<Predicate> predicates = new ArrayList<>();
        if (orderNo != null && !orderNo.isBlank()) {
            predicates.add(cb.like(root.get("orderNo"), "%" + orderNo + "%"));
        }
        if (matchedUserIds != null) {
            predicates.add(root.get("userId").in(matchedUserIds));
        }
        if (statuses != null && !statuses.isEmpty()) {
            predicates.add(root.get("status").in(statuses));
        }
        if (start != null) predicates.add(cb.greaterThanOrEqualTo(root.get("createdAt"), start));
        if (end != null) predicates.add(cb.lessThanOrEqualTo(root.get("createdAt"), end));
        if (minPrice != null) predicates.add(cb.greaterThanOrEqualTo(root.get("price"), minPrice));
        if (maxPrice != null) predicates.add(cb.lessThanOrEqualTo(root.get("price"), maxPrice));
        countCq.select(cb.count(root)).where(predicates.toArray(new Predicate[0]));
        return entityManager.createQuery(countCq).getSingleResult();
    }

    public List<SubscriptionOrder> listAllAdminAdvanced(String orderNo, String keyword, List<OrderStatus> statuses,
                                                         LocalDateTime start, LocalDateTime end,
                                                         BigDecimal minPrice, BigDecimal maxPrice) {
        Page<SubscriptionOrder> page = pageAdminAdvanced(orderNo, keyword, statuses, start, end, minPrice, maxPrice,
                PageRequest.of(0, 10000, Sort.by(Sort.Direction.DESC, "createdAt")));
        return page.getContent();
    }

    @Transactional
    public SubscriptionOrder manualCreateOrder(Long adminUserId, Long userId, SubscriptionTier planTier,
                                                BigDecimal priceOverride, Integer creditOverride,
                                                Integer durationOverride, String orderNoOverride, String remark) {
        CreditRule rule = creditRuleService.getRule();
        BigDecimal price = priceOverride != null ? priceOverride : getPlanPrice(planTier, rule);
        Integer credit = creditOverride != null ? creditOverride : getPlanCredit(planTier, rule);
        Integer durationDays = durationOverride != null ? durationOverride : rule.getPlanDurationDays();

        SubscriptionOrder order = new SubscriptionOrder();
        order.setOrderNo(orderNoOverride != null && !orderNoOverride.isBlank()
                ? orderNoOverride : orderNoGenerator.generate());
        order.setUserId(userId);
        order.setPlanTier(planTier);
        order.setPrice(price);
        order.setCreditAmount(credit);
        order.setDurationDays(durationDays);
        order.setStatus(OrderStatus.PAID);
        order.setPaymentMethod("MANUAL");
        order.setPaymentTransactionId("ADMIN-" + (adminUserId == null ? "system" : adminUserId));
        LocalDateTime now = LocalDateTime.now();
        order.setPaidAt(now);
        order.setExpiresAt(now.plusDays(durationDays));
        if (remark != null && !remark.isBlank()) {
            String extra = "manualCreateBy=" + adminUserId + ";remark=" + remark;
            order.setMetadata(extra);
        }

        creditService.grantPoints(userId, credit,
                CreditTransactionType.SUBSCRIPTION_PURCHASE,
                "管理员补单: " + planTier + " " + (remark == null ? "" : remark),
                order.getOrderNo(), adminUserId);

        UserCredit account = creditService.ensureAccount(userId);
        SubscriptionTier currentTier = account.getSubscriptionTier();
        LocalDateTime currentExpiresAt = account.getSubscriptionExpiresAt();
        LocalDateTime baseTime = (currentExpiresAt != null && currentExpiresAt.isAfter(now))
                ? currentExpiresAt : now;

        if (isHigherTier(planTier, currentTier)
                || (planTier == currentTier && currentTier != SubscriptionTier.FREE)) {
            account.setSubscriptionTier(planTier);
            account.setSubscriptionExpiresAt(baseTime.plusDays(durationDays));
        }

        SubscriptionOrder saved = subscriptionOrderRepository.save(order);
        log.info("管理员补单成功 admin={} user={} plan={} orderNo={}", adminUserId, userId, planTier, saved.getOrderNo());
        return saved;
    }

    public List<OrderStatus> parseStatuses(String statusCsv) {
        if (statusCsv == null || statusCsv.isBlank()) return null;
        return Arrays.stream(statusCsv.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .map(s -> {
                    try { return OrderStatus.valueOf(s); }
                    catch (Exception ignore) { return null; }
                })
                .filter(java.util.Objects::nonNull)
                .collect(Collectors.toList());
    }

    public SubscriptionTier planCodeToTier(String planCode) {
        if (planCode == null) {
            throw new BizException(400, CreditErrorCode.PLAN_NOT_FOUND, "套餐不能为空");
        }
        return switch (planCode.toUpperCase()) {
            case "LITE", "PLAN_LITE" -> SubscriptionTier.LITE;
            case "PRO", "PLAN_PRO" -> SubscriptionTier.PRO;
            case "PROPLUS", "PRO_PLUS", "PLAN_PROPLUS", "PLAN_PRO_PLUS" -> SubscriptionTier.PROPLUS;
            case "ULTRA", "PLAN_ULTRA" -> SubscriptionTier.ULTRA;
            default -> throw new BizException(400, CreditErrorCode.PLAN_NOT_FOUND, "套餐不存在: " + planCode);
        };
    }

    private void validatePlanTier(SubscriptionTier tier) {
        if (tier == null || tier == SubscriptionTier.FREE) {
            throw new BizException(400, CreditErrorCode.PLAN_NOT_FOUND, "无效的套餐等级");
        }
    }

    private boolean isHigherTier(SubscriptionTier newTier, SubscriptionTier currentTier) {
        return tierOrdinal(newTier) > tierOrdinal(currentTier);
    }

    private int tierOrdinal(SubscriptionTier tier) {
        return switch (tier) {
            case FREE -> 0;
            case LITE -> 1;
            case PRO -> 2;
            case PROPLUS -> 3;
            case ULTRA -> 4;
        };
    }

    public BigDecimal getPlanPrice(SubscriptionTier tier, CreditRule rule) {
        return switch (tier) {
            case LITE -> rule.getPlanLitePrice();
            case PRO -> rule.getPlanProPrice();
            case PROPLUS -> rule.getPlanProPlusPrice();
            case ULTRA -> rule.getPlanUltraPrice();
            default -> throw new BizException(400, CreditErrorCode.PLAN_NOT_FOUND, "无效套餐");
        };
    }

    public Integer getPlanCredit(SubscriptionTier tier, CreditRule rule) {
        return switch (tier) {
            case LITE -> rule.getPlanLiteCredit();
            case PRO -> rule.getPlanProCredit();
            case PROPLUS -> rule.getPlanProPlusCredit();
            case ULTRA -> rule.getPlanUltraCredit();
            default -> throw new BizException(400, CreditErrorCode.PLAN_NOT_FOUND, "无效套餐");
        };
    }
}

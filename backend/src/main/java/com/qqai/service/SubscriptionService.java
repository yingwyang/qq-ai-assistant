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
import com.qqai.repository.UserCreditRepository;
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
    private UserCreditRepository userCreditRepository;

    @Autowired
    private EntityManager entityManager;

    /**
     * 创建订阅订单：60 秒幂等窗口防重复下单。
     * 幂等策略：在 (userId, planTier, IDEMPOTENCY_WINDOW_SECONDS) 窗口内若存在 PENDING 订单，
     * 直接返回既有订单而非新建，避免用户重复点击或网络重试产生多单。
     * 并发安全：方法开始先对「该用户的积分账户行」加行锁，把幂等查询与下单写入收进同一把锁，
     * 同一用户的并发下单被串行化，不会再出现同一档位两单。
     */
    @Transactional
    public SubscriptionOrder createOrder(Long userId, SubscriptionTier planTier, String clientIp, String userAgent) {
        validatePlanTier(planTier);
        lockUserAccount(userId);

        LocalDateTime idempotencySince = LocalDateTime.now().minusSeconds(IDEMPOTENCY_WINDOW_SECONDS);
        List<SubscriptionOrder> recent = subscriptionOrderRepository.findRecentByUserAndPlan(userId, planTier, idempotencySince);
        if (!recent.isEmpty()) {
            SubscriptionOrder existing = recent.get(0);
            if (existing.getStatus() == OrderStatus.PENDING) {
                log.info("订单幂等命中 user={} plan={} orderNo={}", userId, planTier, existing.getOrderNo());
                return existing;
            }
        }

        // 同种月卡（同 tier）不允许重复购买（PENDING/PAID 且未过期），提示续费
        // 不同种月卡（大小月卡）可以同时买，但 tier 显示高等级那个
        if (isMonthlyCard(planTier)) {
            LocalDateTime now = LocalDateTime.now();

            // 检查用户是否已经是 ALL 状态（同时拥有大小月卡）
            UserCredit account = creditService.ensureAccount(userId);
            if (account.getSubscriptionTier() == SubscriptionTier.ALL
                    && account.getSubscriptionExpiresAt() != null
                    && account.getSubscriptionExpiresAt().isAfter(now)) {
                throw new BizException(400, CreditErrorCode.ORDER_STATUS_INVALID,
                        "您已同时拥有大小月卡（全功能版），无需再购买。");
            }

            List<SubscriptionOrder> sameActive = subscriptionOrderRepository.findActiveMonthlyCardsOfTier(
                    userId, OrderStatus.PAID, planTier, now);
            if (sameActive == null) sameActive = new ArrayList<>();
            // 包含 PENDING 也提示（避免用户等下又支付一个，叠加不进去）
            List<SubscriptionOrder> pendingSame = subscriptionOrderRepository
                    .findRecentByUserAndPlan(userId, planTier, now.minusDays(30));
            for (SubscriptionOrder o : pendingSame) {
                if (o.getStatus() == OrderStatus.PENDING) sameActive.add(o);
            }
            if (!sameActive.isEmpty()) {
                SubscriptionOrder activeOne = sameActive.get(0);
                log.warn("同种月卡重复购买拦截 user={} tier={} activeOrderNo={} expiresAt={}",
                        userId, planTier, activeOne.getOrderNo(), activeOne.getExpiresAt());
                throw new BizException(400, CreditErrorCode.ORDER_STATUS_INVALID,
                        "您已购买同档位月卡（有效期至 "
                                + (activeOne.getExpiresAt() != null ? activeOne.getExpiresAt().toLocalDate() : "")
                                + "），同种月卡不可重复购买。如需续期请联系管理员。");
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
     * 幂等：仅 PENDING 订单可标记 PAID，重复回调抛 ORDER_STATUS_INVALID；
     * 并发：先用行锁取出订单（findByOrderNoWithLock），保证「读状态 → 改状态 → 发积分」在同一把锁下，
     * 管理员重复点击确认收款或并发重试只会成功一次。
     */
    @Transactional
    public SubscriptionOrder markPaid(String orderNo, String paymentMethod, String paymentTransactionId) {
        SubscriptionOrder order = subscriptionOrderRepository.findByOrderNoWithLock(orderNo)
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

        if (isMonthlyCard(order.getPlanTier())) {
            SubscriptionTier currentTier = account.getSubscriptionTier() != null
                    ? account.getSubscriptionTier() : SubscriptionTier.FREE;
            LocalDateTime currentExpiresAt = account.getSubscriptionExpiresAt();

            // 先排除当前订单，检查其他历史有效月卡
            boolean hasSmallCard = hasActiveMonthlyCard(order.getUserId(), SubscriptionTier.SMALL_MONTH_CARD, now, order.getOrderNo());
            boolean hasLargeCard = hasActiveMonthlyCard(order.getUserId(), SubscriptionTier.LARGE_MONTH_CARD, now, order.getOrderNo());

            // 当前正在支付的订单本身也算一张有效月卡，不能被排除
            if (order.getPlanTier() == SubscriptionTier.SMALL_MONTH_CARD) {
                hasSmallCard = true;
            } else if (order.getPlanTier() == SubscriptionTier.LARGE_MONTH_CARD) {
                hasLargeCard = true;
            }

            if (hasSmallCard && hasLargeCard) {
                // 同时拥有大小月卡，设置为 ALL
                LocalDateTime baseTime = (currentExpiresAt != null && currentExpiresAt.isAfter(now))
                        ? currentExpiresAt : now;
                account.setSubscriptionTier(SubscriptionTier.ALL);
                account.setSubscriptionExpiresAt(baseTime.plusDays(order.getDurationDays()));
                userCreditRepository.save(account);
            } else if (tierRank(order.getPlanTier()) > tierRank(currentTier) && currentTier != SubscriptionTier.ALL) {
                // 高等级月卡（升级）：在当前有效期基础上叠加 + 提升 tier
                LocalDateTime baseTime = (currentExpiresAt != null && currentExpiresAt.isAfter(now))
                        ? currentExpiresAt : now;
                account.setSubscriptionTier(order.getPlanTier());
                account.setSubscriptionExpiresAt(baseTime.plusDays(order.getDurationDays()));
                userCreditRepository.save(account);
            }
            // 同档位月卡：不允许叠加（createOrder 已拦截重复购买，这里兜底不变更）
            // 低等级月卡：不覆盖 tier（例如已有大月卡，又买小月卡，保持大月卡 tier 和有效期）
            // ALL 状态：不再改变

            // 月卡每日额外积分自 2026-09-16 起只在「每日签到」时发放（CreditService.signInToday），
            // 购买不再"即发"，保证权益口径统一、且用户能在签到卡上看见。
        }
        // 直购积分：仅加积分，不改变账号 tier/expiresAt

        subscriptionOrderRepository.save(order);
        log.info("订单已支付 orderNo={} plan={} credits={} tier={} expiresAt={}",
                orderNo, order.getPlanTier(), order.getCreditAmount(),
                account.getSubscriptionTier(), account.getSubscriptionExpiresAt());

        return order;
    }

    @Transactional
    public SubscriptionOrder cancelOrder(String orderNo, Long adminUserId, String reason) {
        return cancelOrderInternal(orderNo, adminUserId, reason, null);
    }

    /**
     * 用户自助取消订单：行锁取单 + **严格归属校验**。
     * orderNo 是外部输入，接口层不再各写一遍「查找 + 比对 userId」，
     * 归属判定统一收口到本服务，避免新增端点时漏判导致越权取消他人订单。
     */
    @Transactional
    public SubscriptionOrder cancelOrderAsUser(String orderNo, Long userId, String reason) {
        if (userId == null) {
            throw new BizException(403, CreditErrorCode.FORBIDDEN_ORDER, "未登录，无法取消订单");
        }
        return cancelOrderInternal(orderNo, null, reason, userId);
    }

    private SubscriptionOrder cancelOrderInternal(String orderNo, Long adminUserId, String reason, Long requiredOwnerId) {
        // 行锁取单：与「确认收款」互斥，避免管理员确认到账的同时用户把订单取消掉
        SubscriptionOrder order = subscriptionOrderRepository.findByOrderNoWithLock(orderNo)
                .orElseThrow(() -> new BizException(404, CreditErrorCode.ORDER_NOT_FOUND, "订单不存在: " + orderNo));
        if (requiredOwnerId != null) {
            requireOrderOwner(order, requiredOwnerId);
        }
        if (order.getStatus() != OrderStatus.PENDING) {
            throw new BizException(400, CreditErrorCode.ORDER_STATUS_INVALID,
                    "仅待确认收款订单可取消，当前状态: " + order.getStatus());
        }
        order.setStatus(OrderStatus.CANCELLED);
        if (reason != null && !reason.isBlank()) {
            String metadata = order.getMetadata();
            String extra = "cancelReason=" + reason + (adminUserId != null ? ";cancelBy=" + adminUserId : "");
            order.setMetadata(metadata == null ? extra : metadata + ";" + extra);
        }
        SubscriptionOrder saved = subscriptionOrderRepository.save(order);
        log.info("订单已取消 orderNo={} admin={} owner={}", orderNo, adminUserId, requiredOwnerId);
        return saved;
    }

    /**
     * 按订单号取订单并校验归属（用户端接口专用）。
     * 非本人订单一律 403 FORBIDDEN_ORDER，不区分「不存在」与「不是你的」，避免探测他人订单号。
     */
    public SubscriptionOrder getOrderForUser(String orderNo, Long userId) {
        if (userId == null) {
            throw new BizException(403, CreditErrorCode.FORBIDDEN_ORDER, "未登录，无法访问订单");
        }
        SubscriptionOrder order = findByOrderNo(orderNo)
                .orElseThrow(() -> new BizException(404, CreditErrorCode.ORDER_NOT_FOUND, "订单不存在: " + orderNo));
        requireOrderOwner(order, userId);
        return order;
    }

    /** 归属校验：userId 为空表示系统/管理员路径（跳过校验），否则必须是订单本人 */
    private void requireOrderOwner(SubscriptionOrder order, Long userId) {
        if (userId != null && !order.getUserId().equals(userId)) {
            throw new BizException(403, CreditErrorCode.FORBIDDEN_ORDER, "无权访问该订单");
        }
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
        // 退款要动钱：行锁取单，避免并发退款/重复审批造成两次扣回
        SubscriptionOrder order = subscriptionOrderRepository.findByOrderNoWithLock(orderNo)
                .orElseThrow(() -> new BizException(404, CreditErrorCode.ORDER_NOT_FOUND, "订单不存在: " + orderNo));

        if (order.getStatus() == OrderStatus.REFUNDED) {
            throw new BizException(400, CreditErrorCode.ALREADY_REFUNDED, "订单已退款 (ALREADY_REFUNDED)");
        }
        if (order.getStatus() != OrderStatus.PAID && order.getStatus() != OrderStatus.PENDING_REFUND) {
            throw new BizException(400, CreditErrorCode.ORDER_STATUS_INVALID,
                    "仅已支付或退款待审批订单可退款，当前状态: " + order.getStatus());
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
        int refundPoints = BigDecimal.valueOf(totalCredit).multiply(BigDecimal.valueOf(ratio)).setScale(0, RoundingMode.HALF_UP).intValue();
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

    /**
     * 计算退款应扣减的积分：取订单积分 x 退款比例和用户当前余额的最小值，防止扣减后余额为负。
     * 安全策略：若用户已消费超过退款比例对应的积分，则只扣减剩余余额（可能为0）。
     */
    public int refundedPointsLastRefund(SubscriptionOrder order, double ratio) {
        int totalCredit = order.getCreditAmount() == null ? 0 : order.getCreditAmount();
        double effectiveRatio = (ratio <= 0 || ratio > 1.0) ? 1.0 : ratio;
        int refundPoints = BigDecimal.valueOf(totalCredit).multiply(BigDecimal.valueOf(effectiveRatio)).setScale(0, RoundingMode.HALF_UP).intValue();
        // 防止余额为负：取 min(应退积分, 用户当前余额)
        UserCredit account = creditService.ensureAccount(order.getUserId());
        int userBalance = account.getBalance() != null ? account.getBalance() : 0;
        return Math.min(refundPoints, userBalance);
    }

    private void downgradeTierAfterRefund(SubscriptionOrder order, double ratio) {
        UserCredit account = creditService.ensureAccount(order.getUserId());
        SubscriptionTier currentTier = account.getSubscriptionTier();
        LocalDateTime currentExpiresAt = account.getSubscriptionExpiresAt();
        LocalDateTime now = LocalDateTime.now();
        boolean dirty = false;

        if (currentTier == order.getPlanTier() && currentExpiresAt != null) {
            int daysToReduce = (int) Math.round(order.getDurationDays() * ratio);
            LocalDateTime rolledBack = currentExpiresAt.minusDays(daysToReduce);
            if (rolledBack.isBefore(now)) {
                account.setSubscriptionTier(SubscriptionTier.FREE);
                account.setSubscriptionExpiresAt(null);
            } else {
                account.setSubscriptionExpiresAt(rolledBack);
            }
            dirty = true;
        }

        // 不论上面是否扣减了 tier/expiresAt，都要扫描所有有效月卡订单，恢复最高级（多买多退都正确）
        try {
            List<SubscriptionOrder> validPaid = subscriptionOrderRepository
                    .findByUserIdAndStatusOrderByCreatedAtDesc(order.getUserId(), OrderStatus.PAID,
                            PageRequest.of(0, 200))
                    .getContent();
            SubscriptionTier bestTier = account.getSubscriptionTier() != null
                    ? account.getSubscriptionTier() : SubscriptionTier.FREE;
            LocalDateTime bestExpires = account.getSubscriptionExpiresAt();
            int bestRank = tierRank(bestTier);
            if (bestExpires != null && bestExpires.isBefore(now)) {
                bestTier = SubscriptionTier.FREE;
                bestExpires = null;
                bestRank = 0;
            }

            for (SubscriptionOrder o : validPaid) {
                if (o.getExpiresAt() == null || o.getExpiresAt().isBefore(now)) continue;
                if (!isMonthlyCard(o.getPlanTier())) continue;
                SubscriptionTier t = o.getPlanTier();
                int r = tierRank(t);
                if (r > bestRank) {
                    bestRank = r;
                    bestTier = t;
                    bestExpires = o.getExpiresAt();
                } else if (r == bestRank && bestExpires != null && o.getExpiresAt().isAfter(bestExpires)) {
                    bestExpires = o.getExpiresAt();
                }
            }

            if (bestTier != account.getSubscriptionTier()
                    || (bestExpires != null && !bestExpires.equals(account.getSubscriptionExpiresAt()))
                    || (bestExpires == null && account.getSubscriptionExpiresAt() != null)) {
                account.setSubscriptionTier(bestTier);
                account.setSubscriptionExpiresAt(bestExpires);
                dirty = true;
            }
        } catch (Exception e) {
            log.warn("退款后重建用户月卡 tier 失败（忽略） orderNo={}: {}", order.getOrderNo(), e.getMessage());
        }

        if (dirty) {
            userCreditRepository.save(account);
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

        if (isMonthlyCard(planTier)) {
            SubscriptionTier currentTier = account.getSubscriptionTier() != null
                    ? account.getSubscriptionTier() : SubscriptionTier.FREE;
            LocalDateTime currentExpiresAt = account.getSubscriptionExpiresAt();

            // 检查现有有效月卡（不包含当前补单的这张）
            boolean hasSmallCard = hasActiveMonthlyCard(userId, SubscriptionTier.SMALL_MONTH_CARD, now, order.getOrderNo());
            boolean hasLargeCard = hasActiveMonthlyCard(userId, SubscriptionTier.LARGE_MONTH_CARD, now, order.getOrderNo());

            // 当前正在创建的补单本身也算一张月卡
            if (planTier == SubscriptionTier.SMALL_MONTH_CARD) {
                hasSmallCard = true;
            } else if (planTier == SubscriptionTier.LARGE_MONTH_CARD) {
                hasLargeCard = true;
            }

            if (hasSmallCard && hasLargeCard) {
                // 同时拥有大小月卡 → ALL
                LocalDateTime baseTime = (currentExpiresAt != null && currentExpiresAt.isAfter(now))
                        ? currentExpiresAt : now;
                account.setSubscriptionTier(SubscriptionTier.ALL);
                account.setSubscriptionExpiresAt(baseTime.plusDays(durationDays));
                userCreditRepository.save(account);
            } else if (tierRank(planTier) > tierRank(currentTier)) {
                // 高等级月卡（升级）：在当前有效期基础上叠加 + 提升 tier
                LocalDateTime baseTime = (currentExpiresAt != null && currentExpiresAt.isAfter(now))
                        ? currentExpiresAt : now;
                account.setSubscriptionTier(planTier);
                account.setSubscriptionExpiresAt(baseTime.plusDays(durationDays));
                userCreditRepository.save(account);
            }
            // 同档位月卡：不允许叠加（createOrder 已拦截重复购买，这里兜底不变更）
            // 低等级月卡：不覆盖 tier（例如已有大月卡，又买小月卡，保持大月卡 tier 和有效期）

            // 月卡每日额外积分自 2026-09-16 起只在「每日签到」时发放，管理员补单不再"即发"。
        }
        // 直购积分：仅加积分，不改变账号 tier/expiresAt

        SubscriptionOrder saved = subscriptionOrderRepository.save(order);
        log.info("管理员补单成功 admin={} user={} plan={} orderNo={} tier={} expiresAt={}",
                adminUserId, userId, planTier, saved.getOrderNo(),
                account.getSubscriptionTier(), account.getSubscriptionExpiresAt());
        return saved;
    }

    /**
     * 用户申请退款：将 PAID 订单状态改为 PENDING_REFUND，记录退款原因到 metadata。
     * 不扣积分、不退款，等待管理员审批。
     * 限制：只有 PAID 状态的订单可以申请退款；PENDING_REFUND 状态不可重复申请。
     */
    @Transactional
    public SubscriptionOrder requestRefund(String orderNo, String reason, Long userId) {
        SubscriptionOrder order = subscriptionOrderRepository.findByOrderNo(orderNo)
                .orElseThrow(() -> new BizException(404, CreditErrorCode.ORDER_NOT_FOUND, "订单不存在: " + orderNo));
        // 归属校验统一收口（userId 为空表示系统/管理员路径）
        requireOrderOwner(order, userId);
        if (order.getStatus() != OrderStatus.PAID) {
            throw new BizException(400, CreditErrorCode.ORDER_STATUS_INVALID,
                    "订单状态" + order.getStatus() + "不可申请退款，仅已支付订单可申请退款");
        }        order.setStatus(OrderStatus.PENDING_REFUND);
        order.setRefundReason(reason);
        String meta = order.getMetadata() != null ? order.getMetadata() : "";
        order.setMetadata(meta + ";refundRequestReason=" + reason +
                ";refundRequestedAt=" + LocalDateTime.now() +
                ";refundRequestedBy=" + userId);
        subscriptionOrderRepository.save(order);
        log.info("订单退款申请已提交 orderNo={} reason={} userId={}", orderNo, reason, userId);
        return order;
    }

    /**
     * 管理员驳回退款申请：将 PENDING_REFUND 订单恢复为 PAID，记录驳回原因。
     */
    @Transactional
    public SubscriptionOrder rejectRefund(String orderNo, String reason, Long adminUserId) {
        SubscriptionOrder order = subscriptionOrderRepository.findByOrderNo(orderNo)
                .orElseThrow(() -> new BizException(404, CreditErrorCode.ORDER_NOT_FOUND, "订单不存在: " + orderNo));
        if (order.getStatus() != OrderStatus.PENDING_REFUND) {
            throw new BizException(400, CreditErrorCode.ORDER_STATUS_INVALID,
                    "订单状态" + order.getStatus() + "不是退款待审批，无法驳回");
        }
        order.setStatus(OrderStatus.PAID);
        String meta = order.getMetadata() != null ? order.getMetadata() : "";
        order.setMetadata(meta + ";refundRejected=REJECTED;refundRejectReason=" + reason +
                ";refundRejectedBy=" + adminUserId + ";refundRejectedAt=" + LocalDateTime.now());
        subscriptionOrderRepository.save(order);
        log.info("退款申请已驳回 orderNo={} reason={} admin={}", orderNo, reason, adminUserId);
        return order;
    }

    /**
     * 用户申诉/纠纷：将 PAID 订单标记为 DISPUTED，记录纠纷原因。
     * 限制：只有 PAID 状态的订单可以被申诉；已退款/已取消/已过期的订单不可申诉。
     */
    @Transactional
    public SubscriptionOrder disputeOrder(String orderNo, String reason, Long userId) {
        SubscriptionOrder order = subscriptionOrderRepository.findByOrderNo(orderNo)
                .orElseThrow(() -> new BizException(404, CreditErrorCode.ORDER_NOT_FOUND, "订单不存在: " + orderNo));
        // 归属校验统一收口（userId 为空表示系统/管理员路径）
        requireOrderOwner(order, userId);
        if (order.getStatus() != OrderStatus.PAID) {
            throw new BizException(400, CreditErrorCode.ORDER_STATUS_INVALID,
                    "订单状态" + order.getStatus() + "不可申诉，仅已支付订单可发起纠纷");
        }
        order.setStatus(OrderStatus.DISPUTED);
        order.setDisputeReason(reason);
        String meta = order.getMetadata() != null ? order.getMetadata() : "";
        order.setMetadata(meta + ";disputeReason=" + reason + ";disputedAt=" + LocalDateTime.now());
        subscriptionOrderRepository.save(order);
        log.info("订单已申诉 orderNo={} reason={} userId={}", orderNo, reason, userId);
        return order;
    }

    /**
     * 管理员解决纠纷：同意退款 或 驳回纠纷恢复 PAID 状态。
     * agree=true：走退款流程（PAID->REFUNDED）；agree=false：恢复 PAID 状态。
     */
    @Transactional
    public SubscriptionOrder resolveDispute(String orderNo, boolean agree, String reason, Long adminUserId) {
        SubscriptionOrder order = subscriptionOrderRepository.findByOrderNo(orderNo)
                .orElseThrow(() -> new BizException(404, CreditErrorCode.ORDER_NOT_FOUND, "订单不存在: " + orderNo));
        if (order.getStatus() != OrderStatus.DISPUTED) {
            throw new BizException(400, CreditErrorCode.ORDER_STATUS_INVALID,
                    "订单状态" + order.getStatus() + "不是纠纷中，无法处理");
        }
        if (agree) {
            // 同意退款：全额退款
            return refundOrderWithRatio(orderNo, "纠纷处理-同意退款: " + reason, adminUserId, 1.0);
        } else {
            // 驳回纠纷：恢复 PAID 状态
            order.setStatus(OrderStatus.PAID);
            String meta = order.getMetadata() != null ? order.getMetadata() : "";
            order.setMetadata(meta + ";disputeResolved=REJECTED;disputeResolveReason=" + reason +
                    ";resolvedBy=" + adminUserId + ";resolvedAt=" + LocalDateTime.now());
            subscriptionOrderRepository.save(order);
            log.info("纠纷已驳回 orderNo={} reason={} admin={}", orderNo, reason, adminUserId);
            return order;
        }
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
            case "MEGA", "PLAN_MEGA" -> SubscriptionTier.MEGA;
            case "SMALL_MONTH_CARD", "SMALL_CARD", "MONTH_SMALL" -> SubscriptionTier.SMALL_MONTH_CARD;
            case "LARGE_MONTH_CARD", "LARGE_CARD", "MONTH_LARGE" -> SubscriptionTier.LARGE_MONTH_CARD;
            default -> throw new BizException(400, CreditErrorCode.PLAN_NOT_FOUND, "套餐不存在: " + planCode);
        };
    }

    private void validatePlanTier(SubscriptionTier tier) {
        if (tier == null || tier == SubscriptionTier.FREE) {
            throw new BizException(400, CreditErrorCode.PLAN_NOT_FOUND, "无效的套餐等级");
        }
    }

    /**
     * 以「用户积分账户行锁」作为该用户资金操作的互斥锁。
     * 账户不存在时先初始化（ensureAccount 幂等），再重新加锁读取。
     * 用途：下单幂等窗口、确认收款时的订单状态判定等需要"同一用户的资金动作串行化"的场景。
     */
    private void lockUserAccount(Long userId) {
        if (userCreditRepository.findByUserIdWithLock(userId).isPresent()) {
            return;
        }
        creditService.ensureAccount(userId);
        userCreditRepository.findByUserIdWithLock(userId);
    }

    private boolean isMonthlyCard(SubscriptionTier tier) {
        return tier == SubscriptionTier.SMALL_MONTH_CARD || tier == SubscriptionTier.LARGE_MONTH_CARD
                || tier == SubscriptionTier.ALL;
    }

    /**
     * 检查用户是否有指定类型的有效月卡订单（已支付且未过期）
     */
    private boolean hasActiveMonthlyCard(Long userId, SubscriptionTier tier, LocalDateTime now, String excludeOrderNo) {
        try {
            List<SubscriptionOrder> orders = subscriptionOrderRepository
                    .findByUserIdAndStatusOrderByCreatedAtDesc(userId, OrderStatus.PAID,
                            org.springframework.data.domain.PageRequest.of(0, 50))
                    .getContent();
            for (SubscriptionOrder o : orders) {
                // 排除当前正在支付的订单
                if (excludeOrderNo != null && excludeOrderNo.equals(o.getOrderNo())) continue;
                if (o.getPlanTier() == tier && o.getExpiresAt() != null && o.getExpiresAt().isAfter(now)) {
                    return true;
                }
            }
        } catch (Exception e) {
            log.warn("查询有效月卡订单失败: {}", e.getMessage());
        }
        return false;
    }

    /**
     * tier 排序值（用于比较升级/降级）。月卡排序值高于直购同级别。
     * FREE:0, LITE:1, PRO:2, PROPLUS:3, ULTRA:4, MEGA:5, SMALL_MONTH_CARD:6, LARGE_MONTH_CARD:7, ALL:8
     */
    private int tierRank(SubscriptionTier tier) {
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

    /**
     * 套餐价格唯一来源：全部档位都读 {@code credit_rule} 数据库配置（2026-09-19 起月卡与 MEGA 也进了表单，
     * 不再是硬编码）。管理员在后台「规则配置」改完立即生效，客户端订阅页与「资金流水」的模拟现金账同步跟着变。
     */
    public BigDecimal getPlanPrice(SubscriptionTier tier, CreditRule rule) {
        return switch (tier) {
            case LITE -> rule.getPlanLitePrice();
            case PRO -> rule.getPlanProPrice();
            case PROPLUS -> rule.getPlanProPlusPrice();
            case ULTRA -> rule.getPlanUltraPrice();
            case MEGA -> rule.getPlanMegaPrice();
            case SMALL_MONTH_CARD -> rule.getPlanSmallMonthCardPrice();
            case LARGE_MONTH_CARD -> rule.getPlanLargeMonthCardPrice();
            default -> throw new BizException(400, CreditErrorCode.PLAN_NOT_FOUND, "无效套餐");
        };
    }

    public Integer getPlanCredit(SubscriptionTier tier, CreditRule rule) {
        return switch (tier) {
            case LITE -> rule.getPlanLiteCredit();
            case PRO -> rule.getPlanProCredit();
            case PROPLUS -> rule.getPlanProPlusCredit();
            case ULTRA -> rule.getPlanUltraCredit();
            case MEGA -> rule.getPlanMegaCredit();
            case SMALL_MONTH_CARD -> rule.getPlanSmallMonthCardCredit();
            case LARGE_MONTH_CARD -> rule.getPlanLargeMonthCardCredit();
            default -> throw new BizException(400, CreditErrorCode.PLAN_NOT_FOUND, "无效套餐");
        };
    }

    /** 月卡每日签到额外积分（小 / 大；双持 = 两者之和）——读表单配置 */
    public int getMonthlyCardDailyBonus(SubscriptionTier tier, CreditRule rule) {
        if (tier == SubscriptionTier.SMALL_MONTH_CARD) return safeBonus(rule.getPlanSmallMonthCardDailyBonus());
        if (tier == SubscriptionTier.LARGE_MONTH_CARD) return safeBonus(rule.getPlanLargeMonthCardDailyBonus());
        if (tier == SubscriptionTier.ALL) {
            return safeBonus(rule.getPlanSmallMonthCardDailyBonus()) + safeBonus(rule.getPlanLargeMonthCardDailyBonus());
        }
        return 0;
    }

    private static int safeBonus(Integer v) {
        return v == null || v < 0 ? 0 : v;
    }
}

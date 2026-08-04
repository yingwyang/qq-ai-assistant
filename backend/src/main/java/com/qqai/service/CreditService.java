package com.qqai.service;

import com.qqai.entity.CreditTransaction;
import com.qqai.entity.CreditRule;
import com.qqai.entity.SignInRecord;
import com.qqai.entity.UserCredit;
import com.qqai.entity.enums.CreditDirection;
import com.qqai.entity.enums.CreditTransactionType;
import com.qqai.entity.enums.SubscriptionTier;
import com.qqai.exception.BizException;
import com.qqai.exception.CreditErrorCode;
import com.qqai.repository.CreditTransactionRepository;
import com.qqai.repository.SignInRecordRepository;
import com.qqai.repository.UserCreditRepository;
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

    @Autowired
    private UserCreditRepository userCreditRepository;

    @Autowired
    private CreditTransactionRepository creditTransactionRepository;

    @Autowired
    private SignInRecordRepository signInRecordRepository;

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
     * 事务边界：余额更新与流水写入同事务，保证一致；不含行锁，调用方需避免对同一用户并发发放的丢更新问题。
     * 设计理由：发放场景无透支风险，使用乐观更新即可，避免与 spendPoints 共用悲观行锁。
     */
    @Transactional
    public CreditTransaction grantPoints(Long userId, int amount, CreditTransactionType type, String remark, String relatedId, Long adminUserId) {
        if (amount <= 0) {
            throw new BizException("发放积分必须大于0");
        }
        UserCredit account = ensureAccount(userId);
        CreditTransaction tx = grantPointsInternal(account, amount, type, remark, relatedId, adminUserId);
        userCreditRepository.save(account);
        return tx;
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

    /**
     * 每日签到：写入签到记录并发放签到积分，连续签到天数基于昨日记录累加。
     * 并发控制：依赖 SignInRecord 表的 UNIQUE(userId, signIn_date) 约束防重复签到，
     * 并发重复插入会被数据库约束拦截，此时优雅返回已存在的今日记录。
     * 事务边界：签到记录与积分流水在同一事务内落库。
     */
    @Transactional
    public SignInRecord signInToday(Long userId) {
        LocalDate today = LocalDate.now();
        if (signInRecordRepository.existsByUserIdAndSignInDate(userId, today)) {
            throw new BizException(400, CreditErrorCode.ALREADY_SIGNED_IN, "今日已签到");
        }
        UserCredit account = ensureAccount(userId);

        int streakDays = calculateStreakDays(userId, today);
        int points = safeSignInPoints();

        SignInRecord record = new SignInRecord();
        record.setUserId(userId);
        record.setSignInDate(today);
        record.setPoints(points);
        record.setStreakDays(streakDays);
        try {
            signInRecordRepository.save(record);
        } catch (DataIntegrityViolationException e) {
            log.warn("用户{} 签到并发唯一约束冲突，返回已有记录", userId);
            return signInRecordRepository.findByUserIdAndSignInDate(userId, today)
                    .orElseThrow(() -> new BizException(400, CreditErrorCode.ALREADY_SIGNED_IN, "今日已签到"));
        }

        grantPointsInternal(account, points, CreditTransactionType.SIGN_IN,
                "连续签到" + streakDays + "天", today.toString(), null);
        userCreditRepository.save(account);

        log.info("用户{} 签到成功，获得{}积分，连续{}天", userId, points, streakDays);
        return record;
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

    public UserCredit getBalanceWithTier(Long userId) {
        UserCredit account = ensureAccount(userId);
        if (account.getSubscriptionExpiresAt() != null
                && account.getSubscriptionExpiresAt().isBefore(LocalDateTime.now())
                && account.getSubscriptionTier() != SubscriptionTier.FREE) {
            account.setSubscriptionTier(SubscriptionTier.FREE);
            account.setSubscriptionExpiresAt(null);
            userCreditRepository.save(account);
        }
        return account;
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
            java.sql.Date d = (java.sql.Date) row[0];
            rowMap.put(d.toLocalDate(), row);
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
        List<Predicate> predicates = new ArrayList<>();
        if (userId != null) predicates.add(cb.equal(root.get("userId"), userId));
        if (type != null) predicates.add(cb.equal(root.get("type"), type));
        if (direction != null) predicates.add(cb.equal(root.get("direction"), direction));
        if (start != null) predicates.add(cb.greaterThanOrEqualTo(root.get("createdAt"), start));
        if (end != null) predicates.add(cb.lessThanOrEqualTo(root.get("createdAt"), end));
        if (minAmount != null) predicates.add(cb.greaterThanOrEqualTo(root.get("amount"), minAmount.intValue()));
        if (maxAmount != null) predicates.add(cb.lessThanOrEqualTo(root.get("amount"), maxAmount.intValue()));
        if (relatedId != null && !relatedId.isBlank()) predicates.add(cb.equal(root.get("relatedId"), relatedId));
        countCq.select(cb.count(root)).where(predicates.toArray(new Predicate[0]));
        return entityManager.createQuery(countCq).getSingleResult();
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
        Map<String, Object> signIn = new HashMap<>();
        signIn.put("name", "每日签到");
        signIn.put("type", "SIGN_IN");
        Map<String, Object> signInProgress = new HashMap<>();
        signInProgress.put("x", todaySigned ? 1 : 0);
        signInProgress.put("y", 1);
        signIn.put("valueOrProgress", signInProgress);
        signIn.put("points", signInPoints);
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

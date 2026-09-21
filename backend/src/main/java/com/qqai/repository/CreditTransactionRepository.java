package com.qqai.repository;

import com.qqai.entity.CreditTransaction;
import com.qqai.entity.enums.CreditDirection;
import com.qqai.entity.enums.CreditTransactionType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

@Repository
public interface CreditTransactionRepository extends JpaRepository<CreditTransaction, Long>, JpaSpecificationExecutor<CreditTransaction> {

    Page<CreditTransaction> findByUserIdOrderByCreatedAtDesc(Long userId, Pageable pageable);

    Page<CreditTransaction> findByUserIdAndTypeOrderByCreatedAtDesc(Long userId, CreditTransactionType type, Pageable pageable);

    List<CreditTransaction> findByUserIdAndCreatedAtBetween(Long userId, LocalDateTime start, LocalDateTime end);

    List<CreditTransaction> findByRelatedIdOrderByCreatedAtAsc(String relatedId);

    /** 批量按关联 ID 回查（现金账：退款找原单，避免 N+1） */
    List<CreditTransaction> findByRelatedIdIn(List<String> relatedIds);

    /** 时间范围内的全部流水（现金汇总与图表用；调用方负责限制范围与条数） */
    List<CreditTransaction> findByCreatedAtBetweenOrderByCreatedAtAsc(LocalDateTime start, LocalDateTime end);

    @Query("SELECT DATE(ct.createdAt) as txDate, " +
           "SUM(CASE WHEN ct.direction = 'IN' THEN ct.amount ELSE 0 END) as earned, " +
           "SUM(CASE WHEN ct.direction = 'OUT' THEN ct.amount ELSE 0 END) as spent " +
           "FROM CreditTransaction ct WHERE ct.userId = :userId AND ct.createdAt BETWEEN :start AND :end " +
           "GROUP BY DATE(ct.createdAt) ORDER BY txDate")
    List<Object[]> sumEarnedSpentGroupByDate(@Param("userId") Long userId,
                                              @Param("start") LocalDateTime start,
                                              @Param("end") LocalDateTime end);

    @Query("SELECT COALESCE(SUM(CASE WHEN ct.direction = 'IN' THEN ct.amount ELSE 0 END), 0) " +
           "FROM CreditTransaction ct WHERE ct.userId = :userId AND ct.type = :type")
    Integer sumInByUserIdAndType(@Param("userId") Long userId, @Param("type") CreditTransactionType type);

    long countByUserIdAndRelatedId(Long userId, String relatedId);

    long countByUserIdAndTypeAndCreatedAtAfter(Long userId, CreditTransactionType type, LocalDateTime after);

    @Query("SELECT COALESCE(SUM(CASE WHEN ct.direction = 'IN' THEN ct.amount ELSE 0 END), 0) " +
           "FROM CreditTransaction ct WHERE ct.userId = :userId " +
           "AND (:type IS NULL OR ct.type = :type) " +
           "AND (:direction IS NULL OR ct.direction = :direction) " +
           "AND (:start IS NULL OR ct.createdAt >= :start) " +
           "AND (:end IS NULL OR ct.createdAt <= :end) " +
           "AND (:relatedId IS NULL OR ct.relatedId = :relatedId)")
    long sumIncomeByFilters(@Param("userId") Long userId,
                            @Param("type") CreditTransactionType type,
                            @Param("direction") CreditDirection direction,
                            @Param("start") LocalDateTime start,
                            @Param("end") LocalDateTime end,
                            @Param("relatedId") String relatedId);

    @Query("SELECT COALESCE(SUM(CASE WHEN ct.direction = 'OUT' THEN ct.amount ELSE 0 END), 0) " +
           "FROM CreditTransaction ct WHERE ct.userId = :userId " +
           "AND (:type IS NULL OR ct.type = :type) " +
           "AND (:direction IS NULL OR ct.direction = :direction) " +
           "AND (:start IS NULL OR ct.createdAt >= :start) " +
           "AND (:end IS NULL OR ct.createdAt <= :end) " +
           "AND (:relatedId IS NULL OR ct.relatedId = :relatedId)")
    long sumSpendByFilters(@Param("userId") Long userId,
                           @Param("type") CreditTransactionType type,
                           @Param("direction") CreditDirection direction,
                           @Param("start") LocalDateTime start,
                           @Param("end") LocalDateTime end,
                           @Param("relatedId") String relatedId);

    /**
     * 一次聚合同时得到「收入合计」与「支出合计」（单行两列）。
     * 钱包页原先分别调用 sumIncomeByFilters + sumSpendByFilters（同条件扫两遍），
     * 这里合并为一条 SQL；过滤条件与上面两个方法保持一致。
     *
     * 返回 List&lt;Object[]&gt; 而非 Object[]：多列聚合在 Spring Data 下用 List 承接更稳
     * （直接声明 Object[] 时单行结果会被再包一层，取到的不是数字）。
     */
    @Query("SELECT COALESCE(SUM(CASE WHEN ct.direction = com.qqai.entity.enums.CreditDirection.IN THEN ct.amount ELSE 0 END), 0), "
            + "COALESCE(SUM(CASE WHEN ct.direction = com.qqai.entity.enums.CreditDirection.OUT THEN ct.amount ELSE 0 END), 0) "
            + "FROM CreditTransaction ct WHERE ct.userId = :userId "
            + "AND (:type IS NULL OR ct.type = :type) "
            + "AND (:direction IS NULL OR ct.direction = :direction) "
            + "AND (:start IS NULL OR ct.createdAt >= :start) "
            + "AND (:end IS NULL OR ct.createdAt <= :end) "
            + "AND (:relatedId IS NULL OR ct.relatedId = :relatedId)")
    List<Object[]> sumIncomeAndSpendByFilters(@Param("userId") Long userId,
                                              @Param("type") CreditTransactionType type,
                                              @Param("direction") CreditDirection direction,
                                              @Param("start") LocalDateTime start,
                                              @Param("end") LocalDateTime end,
                                              @Param("relatedId") String relatedId);

    void deleteByUserId(Long userId);
}

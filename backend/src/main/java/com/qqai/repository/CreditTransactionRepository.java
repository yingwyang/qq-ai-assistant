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
}

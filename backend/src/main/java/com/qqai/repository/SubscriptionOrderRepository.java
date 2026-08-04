package com.qqai.repository;

import com.qqai.entity.SubscriptionOrder;
import com.qqai.entity.enums.OrderStatus;
import com.qqai.entity.enums.SubscriptionTier;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface SubscriptionOrderRepository extends JpaRepository<SubscriptionOrder, Long>, JpaSpecificationExecutor<SubscriptionOrder> {

    Optional<SubscriptionOrder> findByOrderNo(String orderNo);

    boolean existsByOrderNo(String orderNo);

    Page<SubscriptionOrder> findByUserIdOrderByCreatedAtDesc(Long userId, Pageable pageable);

    Page<SubscriptionOrder> findByUserIdAndStatusOrderByCreatedAtDesc(Long userId, OrderStatus status, Pageable pageable);

    Page<SubscriptionOrder> findAllByOrderByCreatedAtDesc(Pageable pageable);

    Page<SubscriptionOrder> findByStatusOrderByCreatedAtDesc(OrderStatus status, Pageable pageable);

    @Query("SELECT o FROM SubscriptionOrder o WHERE o.userId = :userId AND o.planTier = :planTier " +
           "AND o.createdAt >= :since ORDER BY o.createdAt DESC")
    List<SubscriptionOrder> findRecentByUserAndPlan(@Param("userId") Long userId,
                                                     @Param("planTier") SubscriptionTier planTier,
                                                     @Param("since") LocalDateTime since);

    @Query("SELECT o FROM SubscriptionOrder o WHERE o.status = :status AND o.expiresAt < :now")
    List<SubscriptionOrder> findExpiredOrders(@Param("status") OrderStatus status, @Param("now") LocalDateTime now);

    @Query("SELECT o FROM SubscriptionOrder o WHERE o.userId = :userId AND o.status IN :statuses ORDER BY o.createdAt DESC")
    Page<SubscriptionOrder> findByUserIdAndStatusInOrderByCreatedAtDesc(@Param("userId") Long userId,
                                                                        @Param("statuses") List<OrderStatus> statuses,
                                                                        Pageable pageable);

    @Query("SELECT o FROM SubscriptionOrder o WHERE o.status IN :statuses ORDER BY o.createdAt DESC")
    Page<SubscriptionOrder> findByStatusInOrderByCreatedAtDesc(@Param("statuses") List<OrderStatus> statuses,
                                                               Pageable pageable);
}

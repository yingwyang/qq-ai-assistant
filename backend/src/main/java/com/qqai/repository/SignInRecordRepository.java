package com.qqai.repository;

import com.qqai.entity.SignInRecord;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

@Repository
public interface SignInRecordRepository extends JpaRepository<SignInRecord, Long>, JpaSpecificationExecutor<SignInRecord> {

    Optional<SignInRecord> findByUserIdAndSignInDate(Long userId, LocalDate signInDate);

    boolean existsByUserIdAndSignInDate(Long userId, LocalDate signInDate);

    Page<SignInRecord> findByUserIdOrderBySignInDateDesc(Long userId, Pageable pageable);

    @Query("SELECT s FROM SignInRecord s WHERE s.userId = :userId AND s.signInDate <= :date ORDER BY s.signInDate DESC")
    List<SignInRecord> findRecentByUserIdAndDate(@Param("userId") Long userId, @Param("date") LocalDate date, Pageable pageable);

    @Query("SELECT COUNT(s) FROM SignInRecord s WHERE s.userId = :userId AND s.signInDate BETWEEN :start AND :end")
    long countByUserIdAndDateRange(@Param("userId") Long userId, @Param("start") LocalDate start, @Param("end") LocalDate end);

    List<SignInRecord> findByUserIdAndSignInDateBetweenOrderBySignInDateAsc(Long userId, LocalDate start, LocalDate end);
}

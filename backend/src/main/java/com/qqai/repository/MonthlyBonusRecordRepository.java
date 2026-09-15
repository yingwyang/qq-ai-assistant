package com.qqai.repository;

import com.qqai.entity.MonthlyBonusRecord;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;

@Repository
public interface MonthlyBonusRecordRepository extends JpaRepository<MonthlyBonusRecord, Long> {

    boolean existsByUserIdAndBonusDate(Long userId, LocalDate date);

    void deleteByUserId(Long userId);
}
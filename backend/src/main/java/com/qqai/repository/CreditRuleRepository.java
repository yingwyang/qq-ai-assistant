package com.qqai.repository;

import com.qqai.entity.CreditRule;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface CreditRuleRepository extends JpaRepository<CreditRule, Long> {
}

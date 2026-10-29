package com.qqai.repository;

import com.qqai.entity.Bot;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface BotRepository extends JpaRepository<Bot, Long> {
    List<Bot> findByOwnerUserIdOrderByIdAsc(Long ownerUserId);
    long countByOwnerUserId(Long ownerUserId);
    Optional<Bot> findByQqNumber(String qqNumber);
    Optional<Bot> findByAgentToken(String agentToken);
}

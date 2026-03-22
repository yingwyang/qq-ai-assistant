package com.qqai.repository;

import com.qqai.entity.UserSettings;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface UserSettingsRepository extends JpaRepository<UserSettings, Long> {
    
    Optional<UserSettings> findByUserQq(String userQq);
    
    boolean existsByUserQq(String userQq);
}

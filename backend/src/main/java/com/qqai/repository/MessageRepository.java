package com.qqai.repository;

import com.qqai.entity.Message;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface MessageRepository extends JpaRepository<Message, Long> {
    List<Message> findByGroupId(String groupId);
    List<Message> findByProcessedFalse();
}
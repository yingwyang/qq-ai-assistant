package com.qqai.repository;

import com.qqai.entity.FileRecord;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * 文件记录 Repository
 */
@Repository
public interface FileRecordRepository extends JpaRepository<FileRecord, Long> {
    
    Optional<FileRecord> findByFileId(String fileId);
    
    List<FileRecord> findByFileType(FileRecord.FileType fileType);
    
    List<FileRecord> findByCreatedAtBefore(LocalDateTime date);
}

package com.qqai.repository;

import com.qqai.entity.FileRecord;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * 文件记录 Repository
 */
@Repository
public interface FileRecordRepository extends JpaRepository<FileRecord, Long> {

    Optional<FileRecord> findByFileId(String fileId);

    Optional<FileRecord> findByUploaderId(Long uploaderId);

    List<FileRecord> findByFileType(FileRecord.FileType fileType);

    List<FileRecord> findByCreatedAtBefore(LocalDateTime date);

    Page<FileRecord> findByFileTypeAndActive(FileRecord.FileType fileType, boolean active, Pageable pageable);

    Page<FileRecord> findByActive(boolean active, Pageable pageable);

    /**
     * 统计指定上传者的文件数量
     */
    long countByUploaderId(Long uploaderId);

    /**
     * 统计指定上传者的有效文件总大小（字节）
     */
    @Query("SELECT COALESCE(SUM(f.fileSize), 0) FROM FileRecord f WHERE f.uploaderId = :uploaderId AND f.active = true")
    Long sumFileSizeByUploaderId(@Param("uploaderId") Long uploaderId);

    /**
     * 防御性包装
     */
    default Long safeSumFileSizeByUploaderId(Long uploaderId) {
        if (uploaderId == null) return 0L;
        Long result = sumFileSizeByUploaderId(uploaderId);
        return result != null ? result : 0L;
    }

    /**
     * 防御性包装 countByUploaderId
     */
    default long safeCountByUploaderId(Long uploaderId) {
        if (uploaderId == null) return 0L;
        return countByUploaderId(uploaderId);
    }

    /**
     * 按 fileId 集合批量查询，保持输入顺序，缺失的跳过
     */
    default java.util.List<FileRecord> findAllByFileIdIn(java.util.Collection<String> fileIds) {
        if (fileIds == null || fileIds.isEmpty()) return java.util.Collections.emptyList();
        java.util.List<FileRecord> result = new java.util.ArrayList<>(fileIds.size());
        for (String fid : fileIds) {
            if (fid == null) continue;
            findByFileId(fid).ifPresent(result::add);
        }
        return result;
    }

    /**
     * 根据 fileId 列表批量删除
     */
    void deleteByFileIdIn(java.util.Collection<String> fileIds);
}

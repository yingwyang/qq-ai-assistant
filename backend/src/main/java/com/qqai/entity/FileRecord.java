package com.qqai.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;

/**
 * 文件记录实体 - 存储文件元数据
 */
@Entity
@Table(name = "file_records", indexes = {
    @Index(name = "idx_file_id", columnList = "fileId"),
    @Index(name = "idx_type_time", columnList = "fileType, createdAt")
})
public class FileRecord {
    
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    
    @Column(unique = true, nullable = false, length = 100)
    private String fileId;  // 文件唯一ID
    
    @Column(length = 500)
    private String fileName;  // 原始文件名
    
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private FileType fileType;  // 文件类型
    
    private Long fileSize;  // 文件大小(字节)
    
    @Column(length = 100)
    private String mimeType;  // MIME类型
    
    @Enumerated(EnumType.STRING)
    private StorageType storageType = StorageType.MINIO;  // 存储类型
    
    @Column(length = 100)
    private String bucketName;  // 存储桶名
    
    @Column(length = 1000)
    private String objectKey;  // 对象存储路径
    
    @Column(length = 1000)
    private String url;  // 访问URL
    
    @Column(length = 1000)
    private String thumbnailUrl;  // 缩略图URL(图片/视频)
    
    private Integer width;  // 图片宽度
    
    private Integer height;  // 图片高度
    
    private Integer duration;  // 音视频时长(秒)
    
    private LocalDateTime createdAt;
    
    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
    }
    
    // 文件类型枚举
    public enum FileType {
        IMAGE, VIDEO, AUDIO, FILE
    }
    
    // 存储类型枚举
    public enum StorageType {
        LOCAL, MINIO, OSS, COS
    }
    
    // Getters and Setters
    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    
    public String getFileId() { return fileId; }
    public void setFileId(String fileId) { this.fileId = fileId; }
    
    public String getFileName() { return fileName; }
    public void setFileName(String fileName) { this.fileName = fileName; }
    
    public FileType getFileType() { return fileType; }
    public void setFileType(FileType fileType) { this.fileType = fileType; }
    
    public Long getFileSize() { return fileSize; }
    public void setFileSize(Long fileSize) { this.fileSize = fileSize; }
    
    public String getMimeType() { return mimeType; }
    public void setMimeType(String mimeType) { this.mimeType = mimeType; }
    
    public StorageType getStorageType() { return storageType; }
    public void setStorageType(StorageType storageType) { this.storageType = storageType; }
    
    public String getBucketName() { return bucketName; }
    public void setBucketName(String bucketName) { this.bucketName = bucketName; }
    
    public String getObjectKey() { return objectKey; }
    public void setObjectKey(String objectKey) { this.objectKey = objectKey; }
    
    public String getUrl() { return url; }
    public void setUrl(String url) { this.url = url; }
    
    public String getThumbnailUrl() { return thumbnailUrl; }
    public void setThumbnailUrl(String thumbnailUrl) { this.thumbnailUrl = thumbnailUrl; }
    
    public Integer getWidth() { return width; }
    public void setWidth(Integer width) { this.width = width; }
    
    public Integer getHeight() { return height; }
    public void setHeight(Integer height) { this.height = height; }
    
    public Integer getDuration() { return duration; }
    public void setDuration(Integer duration) { this.duration = duration; }
    
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}

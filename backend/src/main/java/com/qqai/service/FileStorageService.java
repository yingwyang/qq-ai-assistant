package com.qqai.service;

import com.qqai.entity.FileRecord;
import com.qqai.repository.FileRecordRepository;
import io.minio.*;
import io.minio.http.Method;
import net.coobird.thumbnailator.Thumbnails;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * 文件存储服务 - 使用 MinIO
 */
@Service
public class FileStorageService {

    private static final Logger log = LoggerFactory.getLogger(FileStorageService.class);

    @Autowired
    private MinioClient minioClient;

    @Autowired
    private FileRecordRepository fileRecordRepository;

    @Value("${minio.bucket-name}")
    private String bucketName;

    // 缩略图最大尺寸
    private static final int THUMBNAIL_WIDTH = 300;
    private static final int THUMBNAIL_HEIGHT = 300;

    /**
     * 上传文件
     */
    public FileRecord uploadFile(MultipartFile file, FileRecord.FileType fileType, Long uploaderId) throws Exception {
        // 生成文件ID
        String fileId = UUID.randomUUID().toString().replace("-", "");
        
        // 确定存储路径
        String folder = getFolderByType(fileType);
        String originalFilename = file.getOriginalFilename();
        String extension = getExtension(originalFilename);
        String objectName = folder + "/" + fileId + extension;

        // 确保 bucket 存在
        ensureBucketExists();

        // 上传到 MinIO
        minioClient.putObject(
            PutObjectArgs.builder()
                .bucket(bucketName)
                .object(objectName)
                .stream(file.getInputStream(), file.getSize(), -1)
                .contentType(file.getContentType())
                .build()
        );

        // 生成缩略图（图片）
        String thumbnailUrl = null;
        Integer width = null;
        Integer height = null;
        
        if (fileType == FileRecord.FileType.IMAGE) {
            BufferedImage originalImage = ImageIO.read(file.getInputStream());
            if (originalImage != null) {
                width = originalImage.getWidth();
                height = originalImage.getHeight();
                
                // 生成缩略图
                thumbnailUrl = generateThumbnail(file, folder, fileId, extension);
            }
        }

        // 获取文件访问URL
        String fileUrl = getFileUrl(objectName);

        // 保存文件记录到数据库
        FileRecord fileRecord = new FileRecord();
        fileRecord.setFileId(fileId);
        fileRecord.setFileName(originalFilename);
        fileRecord.setFileType(fileType);
        fileRecord.setFileSize(file.getSize());
        fileRecord.setMimeType(file.getContentType());
        fileRecord.setStorageType(FileRecord.StorageType.MINIO);
        fileRecord.setBucketName(bucketName);
        fileRecord.setObjectKey(objectName);
        fileRecord.setUrl(fileUrl);
        fileRecord.setThumbnailUrl(thumbnailUrl);
        fileRecord.setWidth(width);
        fileRecord.setHeight(height);
        if (uploaderId != null) fileRecord.setUploaderId(uploaderId);

        return fileRecordRepository.save(fileRecord);
    }

    /**
     * 上传字节数组文件
     */
    public FileRecord uploadBytes(byte[] data, String fileName, FileRecord.FileType fileType, String mimeType, Long uploaderId) throws Exception {
        String fileId = UUID.randomUUID().toString().replace("-", "");
        String folder = getFolderByType(fileType);
        String extension = getExtension(fileName);
        String objectName = folder + "/" + fileId + extension;

        ensureBucketExists();

        minioClient.putObject(
            PutObjectArgs.builder()
                .bucket(bucketName)
                .object(objectName)
                .stream(new ByteArrayInputStream(data), data.length, -1)
                .contentType(mimeType)
                .build()
        );

        String fileUrl = getFileUrl(objectName);

        FileRecord fileRecord = new FileRecord();
        fileRecord.setFileId(fileId);
        fileRecord.setFileName(fileName);
        fileRecord.setFileType(fileType);
        fileRecord.setFileSize((long) data.length);
        fileRecord.setMimeType(mimeType);
        fileRecord.setStorageType(FileRecord.StorageType.MINIO);
        fileRecord.setBucketName(bucketName);
        fileRecord.setObjectKey(objectName);
        fileRecord.setUrl(fileUrl);
        if (uploaderId != null) fileRecord.setUploaderId(uploaderId);

        return fileRecordRepository.save(fileRecord);
    }

    /**
     * 获取文件URL
     */
    public String getFileUrl(String objectName) throws Exception {
        return minioClient.getPresignedObjectUrl(
            GetPresignedObjectUrlArgs.builder()
                .method(Method.GET)
                .bucket(bucketName)
                .object(objectName)
                .expiry(7, TimeUnit.DAYS)
                .build()
        );
    }

    /**
     * 删除文件
     */
    public void deleteFile(String fileId) throws Exception {
        FileRecord fileRecord = fileRecordRepository.findByFileId(fileId)
            .orElseThrow(() -> new RuntimeException("文件不存在"));

        // 从 MinIO 删除
        minioClient.removeObject(
            RemoveObjectArgs.builder()
                .bucket(bucketName)
                .object(fileRecord.getObjectKey())
                .build()
        );

        // 删除缩略图
        if (fileRecord.getThumbnailUrl() != null) {
            String thumbnailObjectName = fileRecord.getObjectKey().replace("/", "/thumbs/");
            try {
                minioClient.removeObject(
                    RemoveObjectArgs.builder()
                        .bucket(bucketName)
                        .object(thumbnailObjectName)
                        .build()
                );
            } catch (Exception e) {
                // 缩略图可能不存在，忽略错误
            }
        }

        // 从数据库删除
        fileRecordRepository.delete(fileRecord);
    }

    /**
     * 根据文件ID获取文件记录
     */
    public FileRecord getFileRecord(String fileId) {
        return fileRecordRepository.findByFileId(fileId)
            .orElseThrow(() -> new RuntimeException("文件不存在"));
    }

    /**
     * 生成缩略图
     */
    private String generateThumbnail(MultipartFile file, String folder, String fileId, String extension) {
        try {
            ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
            
            Thumbnails.of(file.getInputStream())
                .size(THUMBNAIL_WIDTH, THUMBNAIL_HEIGHT)
                .outputFormat(extension.replace(".", ""))
                .toOutputStream(outputStream);

            byte[] thumbnailBytes = outputStream.toByteArray();
            String thumbnailObjectName = folder + "/thumbs/" + fileId + "_thumb" + extension;

            minioClient.putObject(
                PutObjectArgs.builder()
                    .bucket(bucketName)
                    .object(thumbnailObjectName)
                    .stream(new ByteArrayInputStream(thumbnailBytes), thumbnailBytes.length, -1)
                    .contentType(file.getContentType())
                    .build()
            );

            return getFileUrl(thumbnailObjectName);
        } catch (Exception e) {
            log.error("生成缩略图失败: {}", e.getMessage());
            return null;
        }
    }

    /**
     * 确保 bucket 存在
     */
    private void ensureBucketExists() throws Exception {
        boolean exists = minioClient.bucketExists(
            BucketExistsArgs.builder().bucket(bucketName).build()
        );
        
        if (!exists) {
            minioClient.makeBucket(
                MakeBucketArgs.builder().bucket(bucketName).build()
            );
        }
    }

    /**
     * 根据文件类型获取文件夹
     */
    private String getFolderByType(FileRecord.FileType fileType) {
        switch (fileType) {
            case IMAGE:
                return "images";
            case VIDEO:
                return "videos";
            case AUDIO:
                return "audios";
            case FILE:
                return "files";
            default:
                return "others";
        }
    }

    /**
     * 获取文件扩展名
     */
    private String getExtension(String filename) {
        if (filename == null || filename.lastIndexOf(".") == -1) {
            return "";
        }
        return filename.substring(filename.lastIndexOf("."));
    }
}

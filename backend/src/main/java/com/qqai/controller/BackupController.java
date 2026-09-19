package com.qqai.controller;

import com.qqai.common.SecurityHelper;
import com.qqai.service.BackupService;
import com.qqai.service.MessageArchiveService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.Resource;
import org.springframework.core.io.UrlResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/admin")
public class BackupController {

    @Autowired
    private BackupService backupService;

    @Autowired
    private MessageArchiveService messageArchiveService;

    @Autowired
    private com.qqai.service.AuditLogService auditLogService;

    @Autowired
    private SecurityHelper securityHelper;

    @PostMapping("/backup")
    public ResponseEntity<?> triggerBackup() {
        try {
            String fileName = backupService.triggerBackup();
            return ResponseEntity.ok(Map.of(
                    "message", "数据库备份成功",
                    "fileName", fileName
            ));
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(Map.of("error", "备份失败: " + e.getMessage()));
        }
    }

    @GetMapping("/backup/list")
    public ResponseEntity<?> getBackupList() {
        List<BackupService.BackupFileInfo> list = backupService.getBackupList();
        return ResponseEntity.ok(list);
    }

    @GetMapping("/backup/{fileName}/download")
    public ResponseEntity<Resource> downloadBackup(@PathVariable String fileName) {
        try {
            Path filePath = backupService.getBackupFilePath(fileName);
            if (filePath == null) {
                return ResponseEntity.notFound().build();
            }

            Resource resource = new UrlResource(filePath.toUri());
            if (!resource.exists()) {
                return ResponseEntity.notFound().build();
            }

            String encodedName = URLEncoder.encode(fileName, StandardCharsets.UTF_8).replace("+", "%20");

            return ResponseEntity.ok()
                    .contentType(MediaType.APPLICATION_OCTET_STREAM)
                    .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + encodedName + "\"")
                    .body(resource);
        } catch (Exception e) {
            return ResponseEntity.internalServerError().build();
        }
    }

    /**
     * 删除一个备份文件。文件名沿用下载接口的白名单校验（拒绝 .. 与路径分隔符）。
     */
    @DeleteMapping("/backup/{fileName}")
    public ResponseEntity<?> deleteBackup(@PathVariable String fileName) {
        try {
            String deleted = backupService.deleteBackup(fileName);
            auditLogService.log(securityHelper.getCurrentUsername(), "BACKUP_DELETE",
                    deleted, "SUCCESS", "删除备份文件");
            return ResponseEntity.ok(Map.of("message", "备份已删除", "fileName", deleted));
        } catch (java.io.FileNotFoundException e) {
            return ResponseEntity.status(404).body(Map.of("error", e.getMessage()));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(Map.of("error", "删除备份失败: " + e.getMessage()));
        }
    }

    /**
     * 归档预检：只统计影响面，不执行归档。
     */
    @GetMapping("/archive/preview")
    public ResponseEntity<?> previewArchive(@RequestParam(defaultValue = "90") int days) {
        try {
            return ResponseEntity.ok(messageArchiveService.previewArchive(days));
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(Map.of("error", "归档预检失败: " + e.getMessage()));
        }
    }

    @PostMapping("/archive")
    public ResponseEntity<?> triggerArchive(@RequestParam(defaultValue = "90") int days) {
        try {
            messageArchiveService.manualArchive(days);
            return ResponseEntity.ok(Map.of("message", "消息归档已触发，归档 " + days + " 天前的消息"));
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(Map.of("error", "归档失败: " + e.getMessage()));
        }
    }
}

package com.qqai;

import com.qqai.service.BackupService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 备份删除的路径校验测试。
 *
 * 删除与下载共用同一套文件名白名单：拒绝 `..`、路径分隔符，并且只允许删除备份目录下
 * 真实存在的 .sql 文件 —— 否则删除接口就成了任意文件删除漏洞。
 */
@SpringBootTest
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
class AdminBackupDeleteTest {

    @Autowired
    private BackupService backupService;

    private Path dir;

    @BeforeEach
    void setUp() throws IOException {
        dir = Files.createTempDirectory("admin-backup-test");
        ReflectionTestUtils.setField(backupService, "backupDirPath", dir.toString());
    }

    @AfterEach
    void tearDown() throws IOException {
        ReflectionTestUtils.setField(backupService, "backupDirPath", "data/backups");
        if (dir != null && Files.exists(dir)) {
            try (Stream<Path> walk = Files.walk(dir)) {
                walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                    try {
                        Files.deleteIfExists(p);
                    } catch (IOException ignored) {
                        // 忽略清理失败
                    }
                });
            }
        }
    }

    private void createBackup(String name) throws IOException {
        Files.writeString(dir.resolve(name), "-- backup");
    }

    @Test
    @DisplayName("正常删除 .sql 备份")
    void deletesValidBackup() throws IOException {
        createBackup("backup_20260919_100000.sql");
        assertEquals("backup_20260919_100000.sql", backupService.deleteBackup("backup_20260919_100000.sql"));
        assertFalse(Files.exists(dir.resolve("backup_20260919_100000.sql")));
    }

    @Test
    @DisplayName("拒绝路径穿越与绝对路径")
    void rejectsPathTraversal() {
        assertThrows(IllegalArgumentException.class, () -> backupService.deleteBackup("../evil.sql"));
        assertThrows(IllegalArgumentException.class, () -> backupService.deleteBackup("sub/evil.sql"));
        assertThrows(IllegalArgumentException.class, () -> backupService.deleteBackup("sub\\evil.sql"));
        assertThrows(IllegalArgumentException.class, () -> backupService.deleteBackup("..\\..\\windows\\system32\\x.sql"));
    }

    @Test
    @DisplayName("不存在的文件返回未找到而不是删除成功")
    void missingFileIsNotFound() {
        assertThrows(FileNotFoundException.class, () -> backupService.deleteBackup("backup_does_not_exist.sql"));
    }

    @Test
    @DisplayName("只允许删除 .sql 文件，其它扩展名即便存在也拒绝")
    void onlySqlFilesAllowed() throws IOException {
        createBackup("notes.txt");
        assertThrows(IllegalArgumentException.class, () -> backupService.deleteBackup("notes.txt"));
        assertTrue(Files.exists(dir.resolve("notes.txt")), "被拒绝的文件必须原样保留");
    }

    @Test
    @DisplayName("备份列表按修改时间倒序且只列 .sql")
    void listOnlySqlSortedByTime() throws IOException {
        createBackup("backup_old.sql");
        Thread.yield();
        createBackup("backup_new.sql");
        createBackup("ignore.txt");
        Files.setLastModifiedTime(dir.resolve("backup_old.sql"),
                java.nio.file.attribute.FileTime.fromMillis(System.currentTimeMillis() - 60_000));

        List<BackupService.BackupFileInfo> list = backupService.getBackupList();
        assertEquals(2, list.size());
        assertEquals("backup_new.sql", list.get(0).getFileName());
    }
}

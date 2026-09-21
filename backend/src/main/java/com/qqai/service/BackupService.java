package com.qqai.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;

import javax.sql.DataSource;
import java.io.*;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.sql.*;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Service
public class BackupService {

    private static final Logger log = LoggerFactory.getLogger(BackupService.class);

    @Autowired
    private DataSource dataSource;

    @Autowired
    private Environment env;

    @Value("${file.storage.archive-path:./uploads/archive}")
    private String archivePath;

    /** 备份目录（可配置，便于测试指向临时目录） */
    @Value("${backup.dir:data/backups}")
    private String backupDirPath;

    private Path backupDir() {
        return Path.of(backupDirPath);
    }

    public String triggerBackup() throws Exception {
        Path backupDir = backupDir();
        if (!Files.exists(backupDir)) {
            Files.createDirectories(backupDir);
        }

        String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));
        String fileName = "backup_" + timestamp + ".sql";
        Path backupFile = backupDir.resolve(fileName);

        exportDatabase(backupFile);

        log.info("数据库备份完成: {}", backupFile.toAbsolutePath());
        return fileName;
    }

    public List<BackupFileInfo> getBackupList() {
        Path backupDir = backupDir();
        if (!Files.exists(backupDir)) {
            return Collections.emptyList();
        }

        try (Stream<Path> paths = Files.list(backupDir)) {
            return paths
                    .filter(p -> p.toString().endsWith(".sql"))
                    .map(p -> {
                        try {
                            BasicFileAttributes attrs = Files.readAttributes(p, BasicFileAttributes.class);
                            return new BackupFileInfo(
                                    p.getFileName().toString(),
                                    Files.size(p),
                                    attrs.lastModifiedTime().toMillis()
                            );
                        } catch (IOException e) {
                            // 单个备份文件读属性失败（被占用/权限）时跳过该文件，但要留下线索
                            log.warn("读取备份文件属性失败，已跳过: file={}, err={}", p.getFileName(), e.toString());
                            return null;
                        }
                    })
                    .filter(Objects::nonNull)
                    .sorted(Comparator.comparingLong(BackupFileInfo::getLastModified).reversed())
                    .collect(Collectors.toList());
        } catch (IOException e) {
            log.error("读取备份列表失败: {}", e.getMessage());
            return Collections.emptyList();
        }
    }

    public Path getBackupFilePath(String fileName) {
        if (fileName.contains("..") || fileName.contains("/") || fileName.contains("\\")) {
            throw new IllegalArgumentException("非法文件名");
        }
        Path path = Path.of(backupDirPath, fileName);
        if (!Files.exists(path)) {
            return null;
        }
        return path;
    }

    /**
     * 删除一个备份文件。
     *
     * <p>文件名校验与下载共用 {@link #getBackupFilePath(String)}：拒绝 .. / 路径分隔符，
     * 且只允许删除 {@link #backupDirPath} 下真实存在的 .sql 备份。</p>
     *
     * @return 被删除的文件名
     * @throws IllegalArgumentException 文件名非法或不是 .sql 备份
     * @throws java.io.FileNotFoundException 文件不存在
     */
    public String deleteBackup(String fileName) throws IOException {
        Path path = getBackupFilePath(fileName);
        if (path == null) {
            throw new java.io.FileNotFoundException("备份文件不存在: " + fileName);
        }
        if (!fileName.toLowerCase().endsWith(".sql")) {
            throw new IllegalArgumentException("只能删除 .sql 备份文件");
        }
        Path normalized = path.toAbsolutePath().normalize();
        Path dir = backupDir().toAbsolutePath().normalize();
        if (!normalized.startsWith(dir)) {
            throw new IllegalArgumentException("不允许删除备份目录之外的文件");
        }
        Files.delete(normalized);
        log.info("备份文件已删除: {}", normalized);
        return fileName;
    }

    private void exportDatabase(Path backupFile) throws Exception {
        try (Connection conn = dataSource.getConnection();
             PrintWriter writer = new PrintWriter(Files.newBufferedWriter(backupFile, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING))) {

            String dbProductName = conn.getMetaData().getDatabaseProductName().toLowerCase();

            writer.println("-- QQ AI Assistant Database Backup");
            writer.println("-- Generated at: " + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")));
            writer.println("-- Database: " + dbProductName);
            writer.println();

            if (dbProductName.contains("h2")) {
                exportH2(conn, writer);
            } else {
                exportViaJdbc(conn, writer);
            }
        }
    }

    private void exportH2(Connection conn, PrintWriter writer) throws SQLException, IOException {
        try (Statement stmt = conn.createStatement()) {
            stmt.execute("SCRIPT TO '" + backupDir().toAbsolutePath() + "/temp_script.sql'");
        }
        // H2 SCRIPT TO writes to file system, read and copy
        Path tempScript = backupDir().resolve("temp_script.sql");
        try {
            if (Files.exists(tempScript)) {
                Files.copy(tempScript, backupDir().resolve("backup_latest.sql"), StandardCopyOption.REPLACE_EXISTING);
                writer.println("-- H2 Script export completed");
            }
        } finally {
            Files.deleteIfExists(tempScript);
        }
    }

    private void exportViaJdbc(Connection conn, PrintWriter writer) throws SQLException {
        DatabaseMetaData meta = conn.getMetaData();

        // Get all tables
        List<String> tables = new ArrayList<>();
        try (ResultSet rs = meta.getTables(null, null, "%", new String[]{"TABLE"})) {
            while (rs.next()) {
                String tableName = rs.getString("TABLE_NAME");
                tables.add(tableName);
            }
        }

        writer.println("SET FOREIGN_KEY_CHECKS=0;");
        writer.println();

        for (String table : tables) {
            exportTable(conn, writer, table);
        }

        writer.println("SET FOREIGN_KEY_CHECKS=1;");
    }

    private void exportTable(Connection conn, PrintWriter writer, String tableName) throws SQLException {
        writer.println("-- Table: " + tableName);
        writer.println("DROP TABLE IF EXISTS `" + tableName + "`;");

        // Get CREATE TABLE statement
        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SHOW CREATE TABLE `" + tableName + "`")) {
            if (rs.next()) {
                String createDdl = rs.getString(2);
                writer.println(createDdl + ";");
                writer.println();
            }
        }

        // Export data
        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT * FROM `" + tableName + "`")) {

            ResultSetMetaData rsMeta = rs.getMetaData();
            int columnCount = rsMeta.getColumnCount();

            int rowCount = 0;
            StringBuilder insertBuilder = new StringBuilder();

            while (rs.next()) {
                if (insertBuilder.length() == 0) {
                    insertBuilder.append("INSERT INTO `").append(tableName).append("` VALUES ");
                } else {
                    insertBuilder.append(",\n");
                }

                insertBuilder.append("(");
                for (int i = 1; i <= columnCount; i++) {
                    if (i > 1) insertBuilder.append(", ");
                    String value = rs.getString(i);
                    if (rs.wasNull()) {
                        insertBuilder.append("NULL");
                    } else {
                        insertBuilder.append("'").append(escapeSql(value)).append("'");
                    }
                }
                insertBuilder.append(")");

                rowCount++;

                // Flush every 100 rows
                if (rowCount % 100 == 0) {
                    writer.println(insertBuilder + ";");
                    insertBuilder.setLength(0);
                }
            }

            if (insertBuilder.length() > 0) {
                writer.println(insertBuilder + ";");
            }

            writer.println("-- " + rowCount + " rows exported for " + tableName);
            writer.println();
        }
    }

    private String escapeSql(String value) {
        if (value == null) return null;
        return value.replace("\\", "\\\\").replace("'", "\\'").replace("\n", "\\n").replace("\r", "\\r");
    }

    public static class BackupFileInfo {
        private final String fileName;
        private final long size;
        private final long lastModified;

        public BackupFileInfo(String fileName, long size, long lastModified) {
            this.fileName = fileName;
            this.size = size;
            this.lastModified = lastModified;
        }

        public String getFileName() { return fileName; }
        public long getSize() { return size; }
        public long getLastModified() { return lastModified; }
    }
}

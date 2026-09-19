package com.qqai;

import com.qqai.service.FilePurgeService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 媒体文件筛选 / 排序 / 统计 / 缓存失效测试。
 *
 * 用临时目录替代 uploads，覆盖：类型过滤、文件名关键字、修改时间范围、排序白名单、
 * 各类型统计与体积、以及清理/删除后缓存必须立即失效（否则「清理预览」数字不降）。
 */
@SpringBootTest
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
class AdminMediaQueryTest {

    @Autowired
    private FilePurgeService filePurgeService;

    private Path root;

    @BeforeEach
    void setUp() throws IOException {
        root = Files.createTempDirectory("admin-media-test");
        ReflectionTestUtils.setField(filePurgeService, "baseDir", root.toString());
        write("images/a.png", 100);
        write("images/b.jpg", 300);
        write("videos/c.mp4", 500);
        write("audios/d.mp3", 50);
        write("avatars/should-be-ignored.png", 999);
    }

    @AfterEach
    void tearDown() throws IOException {
        ReflectionTestUtils.setField(filePurgeService, "baseDir", "./uploads");
        if (root != null && Files.exists(root)) {
            try (Stream<Path> walk = Files.walk(root)) {
                walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                    try {
                        Files.deleteIfExists(p);
                    } catch (IOException ignored) {
                        // 临时目录清理失败不影响断言
                    }
                });
            }
        }
    }

    private void write(String relative, int size) throws IOException {
        Path file = root.resolve(relative);
        Files.createDirectories(file.getParent());
        Files.write(file, new byte[size]);
    }

    private Page<FilePurgeService.MediaFileDto> list(String type, String kw, LocalDate from, LocalDate to, String sort) {
        return filePurgeService.listMediaFiles(type, PageRequest.of(0, 50), kw, from, to, sort);
    }

    @Test
    @DisplayName("按类型过滤且忽略 avatars 目录")
    void filtersByTypeAndSkipsAvatars() {
        assertEquals(2, list("IMAGE", null, null, null, null).getTotalElements());
        assertEquals(1, list("VIDEO", null, null, null, null).getTotalElements());
        assertEquals(1, list("AUDIO", null, null, null, null).getTotalElements());
        assertEquals(4, list("ALL", null, null, null, null).getTotalElements());
        assertEquals(4, list(null, null, null, null, null).getTotalElements());
        assertTrue(list("ALL", null, null, null, null).getContent().stream()
                .noneMatch(f -> f.getPath().contains("avatars")));
    }

    @Test
    @DisplayName("文件名关键字忽略大小写")
    void filtersByKeyword() {
        assertEquals(1, list("ALL", "A.PNG", null, null, null).getTotalElements());
        assertEquals(0, list("ALL", "not-exist", null, null, null).getTotalElements());
    }

    @Test
    @DisplayName("按修改日期范围过滤")
    void filtersByDateRange() {
        LocalDate today = LocalDate.now();
        assertEquals(4, list("ALL", null, today, today, null).getTotalElements());
        assertEquals(0, list("ALL", null, today.plusDays(1), today.plusDays(2), null).getTotalElements());
    }

    @Test
    @DisplayName("排序：体积 / 文件名，方向可切换，非法值回落时间倒序")
    void sortsByWhitelistedFields() {
        List<FilePurgeService.MediaFileDto> bySizeAsc = list("ALL", null, null, null, "size,asc").getContent();
        assertEquals(50L, bySizeAsc.get(0).getFileSize());
        assertEquals(500L, bySizeAsc.get(bySizeAsc.size() - 1).getFileSize());

        List<FilePurgeService.MediaFileDto> bySizeDesc = list("ALL", null, null, null, "size,desc").getContent();
        assertEquals(500L, bySizeDesc.get(0).getFileSize());

        List<FilePurgeService.MediaFileDto> byName = list("ALL", null, null, null, "name,asc").getContent();
        assertTrue(byName.get(0).getFileName().compareToIgnoreCase(byName.get(1).getFileName()) <= 0);

        // 非法排序字段不报错，按默认时间倒序处理
        assertEquals(4, list("ALL", null, null, null, "DROP TABLE,desc").getTotalElements());
    }

    @Test
    @DisplayName("分页正确切片")
    void paginates() {
        Page<FilePurgeService.MediaFileDto> firstPage = filePurgeService.listMediaFiles("ALL", PageRequest.of(0, 2), null, null, null, "size,desc");
        Page<FilePurgeService.MediaFileDto> secondPage = filePurgeService.listMediaFiles("ALL", PageRequest.of(1, 2), null, null, null, "size,desc");
        assertEquals(4, firstPage.getTotalElements());
        assertEquals(2, firstPage.getContent().size());
        assertEquals(2, secondPage.getContent().size());
        assertNotEquals(firstPage.getContent().get(0).getFileName(), secondPage.getContent().get(0).getFileName());
    }

    @Test
    @DisplayName("统计各类型数量与体积")
    void summarizesByType() {
        Map<String, Map<String, Object>> byType = filePurgeService.summarizeMediaFiles().getByType();
        assertEquals(2L, ((Number) byType.get("IMAGE").get("count")).longValue());
        assertEquals(400L, ((Number) byType.get("IMAGE").get("bytes")).longValue());
        assertEquals(1L, ((Number) byType.get("VIDEO").get("count")).longValue());
        assertEquals(4L, ((Number) byType.get("TOTAL").get("count")).longValue());
        assertEquals(950L, ((Number) byType.get("TOTAL").get("bytes")).longValue());
    }

    @Test
    @DisplayName("删除文件后统计缓存立即失效，数字必须变小")
    void summaryCacheIsInvalidatedAfterDelete() {
        assertEquals(4L, ((Number) filePurgeService.summarizeMediaFiles().getByType().get("TOTAL").get("count")).longValue());

        String id = filePurgeService.listMediaFiles("VIDEO", PageRequest.of(0, 10), null, null, null, null)
                .getContent().get(0).getId();
        filePurgeService.deleteFilesByIds(List.of(id), "tester");

        assertEquals(3L, ((Number) filePurgeService.summarizeMediaFiles().getByType().get("TOTAL").get("count")).longValue());
        assertEquals(0, list("VIDEO", null, null, null, null).getTotalElements());
    }

    @Test
    @DisplayName("目录不存在时返回空结果而不是抛异常")
    void missingDirectoryIsSafe() throws IOException {
        Path empty = Files.createTempDirectory("admin-media-empty");
        ReflectionTestUtils.setField(filePurgeService, "baseDir", empty.toString());
        assertEquals(0, list("ALL", null, null, null, null).getTotalElements());
        assertEquals(0L, ((Number) filePurgeService.summarizeMediaFiles().getByType().get("TOTAL").get("count")).longValue());
        Files.deleteIfExists(empty);
    }

    @Test
    @DisplayName("文件名含中文与空格也能按关键字命中")
    void handlesChineseFileNames() throws IOException {
        write("files/测试 文档.txt", 10);
        assertEquals(1, list("ALL", "测试", null, null, null).getTotalElements());
        assertNotNull(list("ALL", "测试", null, null, null).getContent().get(0).getFileName());
        assertTrue(Files.exists(root.resolve("files/测试 文档.txt")), StandardCharsets.UTF_8.name());
    }
}

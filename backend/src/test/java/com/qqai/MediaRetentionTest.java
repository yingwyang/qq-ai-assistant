package com.qqai;

import com.qqai.service.FilePurgeService;
import com.qqai.service.MediaRetentionSettingsService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 媒体保留策略（超过 N 天自动删除）测试。
 *
 * <p>刻意<b>不起 Spring 上下文</b>：本功能只用到 {@link FilePurgeService} 的磁盘逻辑，
 * 而 {@code @SpringBootTest + @DirtiesContext} 每个方法要重建上下文（约 60s）。
 * 这里直接 new 出服务，用 {@code ReflectionTestUtils} 把 {@code baseDir} 指到临时目录，
 * 与 {@code AdminMediaQueryTest} 的造数方式一致，但跑完是毫秒级。</p>
 *
 * <p>注入的 repository / auditLogService 为 null，正是要顺带验证这些路径都能空转不炸：
 * {@code deactivateFileRecordIfExists} 自带 try/catch、{@code softDeleteMessagesReferencingFile}
 * 自带 null 判断、审计写入在新代码里显式 null 判断。</p>
 */
class MediaRetentionTest {

    private FilePurgeService service;
    private Path root;

    @BeforeEach
    void setUp() throws IOException {
        service = new FilePurgeService();
        root = Files.createTempDirectory("media-retention-test");
        ReflectionTestUtils.setField(service, "baseDir", root.toString());
    }

    @AfterEach
    void tearDown() throws IOException {
        if (root != null && Files.exists(root)) {
            try (Stream<Path> walk = Files.walk(root)) {
                walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                    try {
                        Files.deleteIfExists(p);
                    } catch (IOException ignored) {
                        // 临时目录清理失败不影响断言结果
                    }
                });
            }
        }
    }

    /** 造一个文件，并把修改时间设成 daysAgo 天前（保留策略按修改时间算年龄） */
    private void write(String relative, int bytes, long daysAgo) throws IOException {
        Path p = root.resolve(relative);
        Files.createDirectories(p.getParent());
        Files.write(p, new byte[bytes]);
        Files.setLastModifiedTime(p, FileTime.from(Instant.now().minus(daysAgo, ChronoUnit.DAYS)));
    }

    private boolean exists(String relative) {
        return Files.exists(root.resolve(relative));
    }

    @SuppressWarnings("unchecked")
    private long countOf(Map<String, Object> view, String type) {
        Map<String, Object> byType = (Map<String, Object>) view.get("byType");
        Map<String, Object> one = (Map<String, Object>) byType.get(type);
        return ((Number) one.get("count")).longValue();
    }

    @SuppressWarnings("unchecked")
    private long bytesOf(Map<String, Object> view, String type) {
        Map<String, Object> byType = (Map<String, Object>) view.get("byType");
        Map<String, Object> one = (Map<String, Object>) byType.get(type);
        return ((Number) one.get("bytes")).longValue();
    }

    private static final List<String> AV = List.of("IMAGE", "VIDEO", "AUDIO");

    @Test
    @DisplayName("预览只统计超期文件，头像不计入，且按类型分组")
    void previewCountsOnlyExpiredAndSkipsAvatars() throws IOException {
        write("images/old.png", 100, 100);
        write("images/new.png", 200, 1);
        write("video/old.mp4", 500, 100);
        write("voice/old.mp3", 50, 100);
        write("avatars/old.png", 999, 100);

        Map<String, Object> view = service.previewRetention(90, AV).getView();

        assertEquals(3, ((Number) view.get("totalFiles")).longValue(), "3 个超期文件（头像不算）");
        assertEquals(650L, ((Number) view.get("totalBytes")).longValue(), "100+500+50");
        assertEquals(1, countOf(view, "IMAGE"));
        assertEquals(1, countOf(view, "VIDEO"));
        assertEquals(1, countOf(view, "AUDIO"));
        assertEquals(0, countOf(view, "FILE"));
        assertEquals(100L, bytesOf(view, "IMAGE"));
        assertEquals(90, ((Number) view.get("days")).intValue());
    }

    @Test
    @DisplayName("预览是 dry-run：一个文件都不删")
    void previewDoesNotDeleteAnything() throws IOException {
        write("images/old.png", 100, 100);
        service.previewRetention(90, AV);
        assertTrue(exists("images/old.png"), "预览不得删除文件");
    }

    @Test
    @DisplayName("执行清理：只删超期文件，保留未超期的与头像")
    void purgeDeletesOnlyExpired() throws IOException {
        write("images/old.png", 100, 100);
        write("images/new.png", 200, 1);
        write("video/old.mp4", 500, 100);
        write("avatars/old.png", 999, 100);

        FilePurgeService.PurgeResult result = service.purgeMediaOlderThan(90, AV, "system:media-retention");

        assertEquals(2, result.getTotalDeleted(), "只删 2 个超期媒体");
        assertEquals(600L, result.getFreedBytes(), "100+500");
        assertFalse(exists("images/old.png"), "超期图片应被删除");
        assertFalse(exists("video/old.mp4"), "超期视频应被删除");
        assertTrue(exists("images/new.png"), "未超期文件必须保留");
        assertTrue(exists("avatars/old.png"), "头像必须保留（既有 isAvatarPath 守卫）");
    }

    @Test
    @DisplayName("类型过滤：默认三类不含 FILE，pdf 不会被自动删掉")
    void typeFilterKeepsFileTypeUntouched() throws IOException {
        write("docs/old.pdf", 100, 100);

        service.purgeMediaOlderThan(90, AV, "system:media-retention");
        assertTrue(exists("docs/old.pdf"), "FILE 类型不在自动清理范围内");

        // 显式要求 FILE 时才删——证明过滤是按类型生效，而不是文件压根没被扫到
        FilePurgeService.PurgeResult r = service.purgeMediaOlderThan(90, List.of("FILE"), "system:media-retention");
        assertEquals(1, r.getTotalDeleted());
        assertFalse(exists("docs/old.pdf"));
    }

    @Test
    @DisplayName("天数下界收敛到 1：传 0 天不会把刚收到的媒体也删掉")
    void daysAreClampedToAtLeastOne() throws IOException {
        write("images/just-now.png", 100, 0);
        write("images/two-days.png", 100, 2);

        FilePurgeService.PurgeResult result = service.purgeMediaOlderThan(0, AV, "system:media-retention");

        assertEquals(1, result.getTotalDeleted(), "只剩 2 天前那个被删");
        assertTrue(exists("images/just-now.png"), "刚收到的媒体必须保住");
        assertFalse(exists("images/two-days.png"));
    }

    @Test
    @DisplayName("媒体目录不存在时安全返回，不抛异常")
    void missingBaseDirIsSafe() {
        ReflectionTestUtils.setField(service, "baseDir", root.resolve("does-not-exist").toString());
        FilePurgeService.PurgeResult result = service.purgeMediaOlderThan(90, AV, "system:media-retention");
        assertEquals(0, result.getTotalDeleted());
        assertNotNull(result.getNote());
        assertTrue(result.getNote().contains("不存在"), "note 应说明目录不存在: " + result.getNote());
    }

    // ==================== 保留策略设置（运行时开关） ====================

    @Test
    @DisplayName("设置默认关闭，天数为 90，类型默认三类")
    void settingsDefaultToDisabled() {
        MediaRetentionSettingsService settings = new MediaRetentionSettingsService();
        assertFalse(settings.isEnabled(), "默认必须是关闭的（不能默认开一个删数据的定时任务）");
        assertEquals(90, settings.getDays());
        assertEquals(List.of("IMAGE", "VIDEO", "AUDIO"), settings.typeList());
    }

    @Test
    @DisplayName("设置更新：开关与天数生效，天数收敛到合法区间")
    void settingsUpdateClampsDays() {
        MediaRetentionSettingsService settings = new MediaRetentionSettingsService();

        settings.update(Map.of("enabled", true, "days", 30));
        assertTrue(settings.isEnabled());
        assertEquals(30, settings.getDays());

        settings.update(Map.of("days", 0));
        assertEquals(1, settings.getDays(), "下限 1 天");

        settings.update(Map.of("days", 999999));
        assertEquals(3650, settings.getDays(), "上限 10 年");

        settings.update(Map.of("enabled", false));
        assertFalse(settings.isEnabled());
        assertEquals(3650, settings.getDays(), "只更新 enabled 时 days 不变");
    }

    @Test
    @DisplayName("设置的类型白名单：非法类型被忽略，空结果退化为默认三类")
    void settingsTypeWhitelist() {
        MediaRetentionSettingsService settings = new MediaRetentionSettingsService();
        assertTrue(settings.typeList().contains("IMAGE"));
        assertFalse(settings.typeList().contains("FILE"), "FILE 不在自动清理白名单里");
    }
}

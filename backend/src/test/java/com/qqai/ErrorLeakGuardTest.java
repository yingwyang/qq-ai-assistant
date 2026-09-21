package com.qqai;

import com.qqai.common.PageLimits;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 代码卫生守卫（对应企业级化批次 C）。
 *
 * 用源码扫描把「已经修过的坑」变成会失败的构建，避免后续提交再写回去：
 *  1. 控制器不得把 {@code e.getMessage()} 直接回显给客户端（内部 SQL/路径/堆栈片段泄漏）；
 *  2. 控制器不得对用户可控的 size 直接 {@code PageRequest.of(page, size)}（size=100000 全表回表）；
 *  3. 分页上限工具本身的边界行为。
 */
class ErrorLeakGuardTest {

    /** Maven 运行测试时的工作目录是模块根（backend/） */
    private static final Path CONTROLLER_DIR = Paths.get("src", "main", "java", "com", "qqai", "controller");

    /** 400 + 内部异常文本（两种历史形态：ApiResponse.error(...) 与 Map.of("error", ...)） */
    private static final Pattern ECHO_API_RESPONSE =
            Pattern.compile("ApiResponse\\.error\\(\\s*400\\s*,[^;]{0,200}?e\\.getMessage\\(\\)", Pattern.DOTALL);
    private static final Pattern ECHO_RAW_MAP =
            Pattern.compile("Map\\.of\\(\\s*\"error\"\\s*,\\s*e\\.getMessage\\(\\)\\s*\\)");

    /** 用户可控 size 直接进分页（未过 PageLimits） */
    private static final Pattern RAW_PAGE_REQUEST =
            Pattern.compile("PageRequest\\.of\\(\\s*page\\s*,\\s*size\\s*\\)");

    /** 静默吞异常：catch 体里直接 return null/false/0 或完全空，没有任何日志/注释/上抛 */
    private static final Pattern SILENT_CATCH = Pattern.compile(
            "catch\\s*\\([^)]*\\)\\s*\\{\\s*(?:return\\s+(?:null|false|true|0|0L|Collections\\.emptyList\\(\\));)?\\s*\\}");

    private List<Path> sourcesIn(String packageDir) throws IOException {
        Path dir = Paths.get("src", "main", "java", "com", "qqai", packageDir);
        assertTrue(Files.isDirectory(dir),
                "找不到源码目录：" + dir.toAbsolutePath() + "（测试工作目录应为 backend/）");
        try (Stream<Path> stream = Files.list(dir)) {
            return stream.filter(p -> p.toString().endsWith(".java")).toList();
        }
    }

    private List<Path> controllerSources() throws IOException {
        assertTrue(Files.isDirectory(CONTROLLER_DIR),
                "找不到控制器源码目录：" + CONTROLLER_DIR.toAbsolutePath() + "（测试工作目录应为 backend/）");
        try (Stream<Path> stream = Files.list(CONTROLLER_DIR)) {
            return stream.filter(p -> p.toString().endsWith(".java")).toList();
        }
    }

    @Test
    @DisplayName("控制器不得把内部异常文本回显给客户端")
    void controllersDoNotEchoRawExceptionMessages() throws IOException {
        List<String> offenders = new ArrayList<>();
        for (Path file : controllerSources()) {
            String src = Files.readString(file);
            if (ECHO_API_RESPONSE.matcher(src).find() || ECHO_RAW_MAP.matcher(src).find()) {
                offenders.add(file.getFileName().toString());
            }
        }
        assertTrue(offenders.isEmpty(),
                "以下控制器仍在回显内部异常文本，应改为 log.error(...) + 安全的 BizException："
                        + offenders + "（参考 MessageController / PersonaController 的写法）");
    }

    @Test
    @DisplayName("控制器分页必须经过 PageLimits，不允许用户可控 size 直接进 PageRequest")
    void controllersClampUserControlledPageSize() throws IOException {
        List<String> offenders = new ArrayList<>();
        for (Path file : controllerSources()) {
            String src = Files.readString(file);
            if (RAW_PAGE_REQUEST.matcher(src).find()) {
                offenders.add(file.getFileName().toString());
            }
        }
        assertTrue(offenders.isEmpty(),
                "以下控制器仍把用户传入的 size 直接交给 PageRequest，应改用 common/PageLimits.of(page, size)："
                        + offenders);
    }

    @Test
    @DisplayName("控制器/服务不得静默吞异常（catch 体必须记日志或有注释说明）")
    void noSilentCatchInControllerAndService() throws IOException {
        List<String> offenders = new ArrayList<>();
        for (String pkg : new String[]{"controller", "service"}) {
            for (Path file : sourcesIn(pkg)) {
                String src = Files.readString(file);
                if (SILENT_CATCH.matcher(src).find()) {
                    offenders.add(pkg + "/" + file.getFileName());
                }
            }
        }
        assertTrue(offenders.isEmpty(),
                "以下文件存在静默吞异常（catch 体里直接 return，没有任何日志/注释），"
                        + "请至少 log.warn/debug 带上上下文： " + offenders);
    }

    @Test
    @DisplayName("PageLimits：size 下界 1、上界 200，page 不为负")
    void pageLimitsClampBoundaries() {
        assertEquals(1, PageLimits.clampSize(0));
        assertEquals(1, PageLimits.clampSize(-5));
        assertEquals(20, PageLimits.clampSize(20));
        assertEquals(PageLimits.MAX_SIZE, PageLimits.clampSize(100000));
        assertEquals(0, PageLimits.clampPage(-3));
        assertEquals(0, PageLimits.of(-1, 100000).getPageNumber());
        assertEquals(PageLimits.MAX_SIZE, PageLimits.of(0, 100000).getPageSize());
    }
}

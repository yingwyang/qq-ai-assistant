package com.qqai;

import com.qqai.service.MediaDownloadService;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * SSRF 重定向绕过回归测试。
 *
 * <p>背景：{@code HttpURLConnection} 默认自动跟随 302，而 SSRF 校验只做在<b>首跳</b>。
 * 于是「一个会 302 到 http://127.0.0.2:port/secret 的外部地址」就能把内网内容拉进本地媒体目录
 * （该目录随后由 /images/** 对外提供）。修复方式是自己跟随重定向、逐跳重新校验。</p>
 *
 * <p><b>差分设计</b>：被重定向到的「内网」地址是<b>真的在监听并返回内容</b>的，
 * 所以「若代码跟随了重定向」就会真的下载成功、测试失败；只有真正拒绝该跳才会得到 null。
 * 这样测试不会因为目标不可达而假通过（{@link #blockedTargetIsActuallyReachable()} 作为前置校验）。</p>
 */
@SpringBootTest
class MediaDownloadRedirectTest {

    private static final String GROUP_ID = "900700800";
    /** 内容需 > 1024 字节：下载逻辑会对小文件做「错误响应」识别 */
    private static final String PAYLOAD = "SECRET-INTERNAL-CONTENT-".repeat(120);
    private static final String MEDIA = "OK-MEDIA-CONTENT-".repeat(120);

    /** 起始服务器（走白名单路径 127.0.0.1，模拟「允许访问的外部地址」） */
    private static HttpServer entryServer;
    /** 被指向的「内网」服务器（127.0.0.2 属回环但不在白名单 → 应被拦截） */
    private static HttpServer blockedTargetServer;
    private static ExecutorService pool;

    private static String entryBase;
    private static String blockedBase;
    private static String allowedBase;

    @Autowired
    private MediaDownloadService mediaDownloadService;

    @BeforeAll
    static void startServers() throws IOException {
        pool = Executors.newCachedThreadPool();

        // 允许访问的目标：绑 127.0.0.1（测试环境下等于 NapCat 白名单主机）
        entryServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        entryServer.setExecutor(pool);
        String allowedHost = "http://127.0.0.1:" + entryServer.getAddress().getPort();
        allowedBase = allowedHost;
        entryBase = allowedHost;

        entryServer.createContext("/redir-to-blocked", exchange -> redirect(exchange, blockedTargetBase() + "/secret"));
        entryServer.createContext("/redir-to-allowed", exchange -> redirect(exchange, allowedHost + "/final"));
        entryServer.createContext("/redir-loop", exchange -> redirect(exchange, allowedHost + "/redir-loop"));
        entryServer.createContext("/final", exchange -> respond(exchange, MEDIA));
        entryServer.start();

        // 被重定向到的「内网」目标：绑 127.0.0.2（回环但不在白名单 → 必须被拦）
        blockedTargetServer = HttpServer.create(new InetSocketAddress("127.0.0.2", 0), 0);
        blockedTargetServer.setExecutor(pool);
        blockedTargetServer.createContext("/secret", exchange -> respond(exchange, PAYLOAD));
        blockedTargetServer.start();
        blockedBase = "http://127.0.0.2:" + blockedTargetServer.getAddress().getPort();
    }

    /** 端口在启动时才确定，这里只用于构造 redirect 的 Location */
    private static String blockedTargetBase() {
        return blockedBase;
    }

    @AfterAll
    static void stopServers() {
        if (entryServer != null) entryServer.stop(0);
        if (blockedTargetServer != null) blockedTargetServer.stop(0);
        if (pool != null) pool.shutdownNow();
    }

    @AfterEach
    void cleanDownloaded() throws IOException {
        Path dir = Path.of("target", "test-uploads", "images", "image", GROUP_ID);
        if (Files.exists(dir)) {
            try (var walk = Files.walk(dir)) {
                walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                    try {
                        Files.deleteIfExists(p);
                    } catch (IOException ignored) {
                        // 清理失败不影响断言
                    }
                });
            }
        }
    }

    @Test
    @DisplayName("前置校验：被重定向到的目标真的可访问（否则本测试会假通过）")
    void blockedTargetIsActuallyReachable() throws Exception {
        try (InputStream in = new java.net.URL(blockedBase + "/secret").openStream()) {
            String body = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            assertTrue(body.startsWith("SECRET-INTERNAL"), "被拦截的目标必须真实可访问，测试才有区分度");
        }
    }

    @Test
    @DisplayName("302 跳向内网地址 → 拒绝下载（修复前会被跟随并落盘）")
    void redirectToInternalAddressIsRejected() {
        String result = download(entryBase + "/redir-to-blocked");

        assertNull(result, "重定向到不在白名单的回环地址必须被拒绝");
        assertNoMediaDownloaded();
    }

    @Test
    @DisplayName("302 跳向白名单地址 → 正常跟随下载（不误伤合法 CDN 重定向）")
    void redirectToAllowedAddressIsFollowed() {
        String result = download(entryBase + "/redir-to-allowed");

        assertNotNull(result, "合法重定向应能正常下载，否则会误伤 CDN");
        assertTrue(result.startsWith("/images/"), "应返回可访问的相对路径，实际=" + result);
    }

    @Test
    @DisplayName("重定向成环 → 跳数超限后放弃，不会无限跟下去")
    void redirectLoopGivesUp() {
        assertNull(download(entryBase + "/redir-loop"), "成环重定向必须被跳数上限截断");
    }

    // ===================== 辅助方法 =====================

    private String download(String url) {
        String cq = "[CQ:image,file=test.jpg,url=" + url + "]";
        return mediaDownloadService.downloadMediaToLocal(cq, GROUP_ID, "image", ".jpg");
    }

    /** 断言目标群目录下没有落任何文件（被拒绝的下载不应留下半成品；日期子目录不算文件） */
    private void assertNoMediaDownloaded() {
        Path dir = Path.of("target", "test-uploads", "images", "image", GROUP_ID);
        if (!Files.exists(dir)) {
            return;
        }
        try (var walk = Files.walk(dir)) {
            long fileCount = walk.filter(Files::isRegularFile).count();
            assertEquals(0, fileCount, "被 SSRF 拦截的下载不应落盘任何文件");
        } catch (IOException e) {
            fail("检查落盘文件失败: " + e.getMessage());
        }
    }

    private static void redirect(com.sun.net.httpserver.HttpExchange exchange, String location) throws IOException {
        exchange.getResponseHeaders().add("Location", location);
        exchange.sendResponseHeaders(302, -1);
        exchange.close();
    }

    private static void respond(com.sun.net.httpserver.HttpExchange exchange, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(200, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }
}

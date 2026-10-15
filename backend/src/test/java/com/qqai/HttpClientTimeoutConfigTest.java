package com.qqai;

import com.sun.net.httpserver.HttpServer;
import org.apache.hc.client5.http.classic.methods.HttpGet;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.core5.http.io.entity.EntityUtils;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.web.client.RestTemplate;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 出站 HTTP 超时回归测试（对应审查问题：{@code new RestTemplate()} / {@code HttpClients.createDefault()} 无超时）。
 *
 * <p>没有超时的客户端遇到「下游卡住不返回」会无限期占用调用线程，请求一多就把 Tomcat 线程池占满。
 * 这里不看配置字段，而是<b>真的打一个会挂住的本地服务</b>，断言调用会在超时内失败——
 * 这样即使将来有人把超时改回「不设」，测试也会红。</p>
 */
@SpringBootTest(properties = {
        "app.http.connect-timeout-ms=400",
        "app.http.response-timeout-ms=400",
        "app.http.long-timeout-ms=400"
})
class HttpClientTimeoutConfigTest {

    /** 慢接口的挂起时长，远大于上面配置的 400ms 超时 */
    private static final long SLOW_HANDLER_SLEEP_MS = 3_000L;

    private static HttpServer server;
    private static String baseUrl;
    private static ExecutorService serverPool;

    @Autowired
    private RestTemplate restTemplate;

    @Autowired
    @Qualifier("longRunningHttpClient")
    private CloseableHttpClient longRunningHttpClient;

    @Autowired
    private CloseableHttpClient httpClient;

    @BeforeAll
    static void startStubServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        // 必须用弹性线程池：/slow 的处理线程会挂住 3s，固定线程池会把后续 /fast 请求排队，
        // 让「快接口应成功」的用例因排队而超时（那是测试自身的问题，不是被测配置的问题）
        serverPool = Executors.newCachedThreadPool();
        server.setExecutor(serverPool);

        server.createContext("/slow", exchange -> {
            try {
                Thread.sleep(SLOW_HANDLER_SLEEP_MS);
                byte[] body = "slow".getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200, body.length);
                exchange.getResponseBody().write(body);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                exchange.close();
            }
        });

        server.createContext("/fast", exchange -> {
            byte[] body = "fast".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });

        server.start();
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterAll
    static void stopStubServer() {
        if (server != null) {
            server.stop(0);
        }
        if (serverPool != null) {
            serverPool.shutdownNow();
        }
    }

    @Test
    @DisplayName("RestTemplate 会按超时失败，而不是无限等待下游（AstrBot 调用路径）")
    void restTemplateFailsFastOnSlowDownstream() {
        long start = System.currentTimeMillis();
        Exception error = assertThrows(Exception.class,
                () -> restTemplate.getForObject(baseUrl + "/slow", String.class));
        long cost = System.currentTimeMillis() - start;

        assertTrue(cost < SLOW_HANDLER_SLEEP_MS - 1_000,
                "应在配置的 400ms 超时附近失败，实际耗时 " + cost + "ms（说明超时未生效）");
        assertNotNull(error.getMessage());
    }

    @Test
    @DisplayName("长耗时客户端（GPT-SoVITS）同样有上界")
    void longRunningClientAlsoTimesOut() {
        long start = System.currentTimeMillis();
        assertThrows(Exception.class, () -> {
            try (var response = longRunningHttpClient.execute(new HttpGet(baseUrl + "/slow"))) {
                EntityUtils.consume(response.getEntity());
            }
        });
        long cost = System.currentTimeMillis() - start;

        assertTrue(cost < SLOW_HANDLER_SLEEP_MS - 1_000,
                "长耗时客户端也必须有超时上界，实际耗时 " + cost + "ms");
    }

    @Test
    @DisplayName("轻量客户端（NapCat 等）同样有上界")
    void lightClientAlsoTimesOut() {
        long start = System.currentTimeMillis();
        assertThrows(Exception.class, () -> {
            try (var response = httpClient.execute(new HttpGet(baseUrl + "/slow"))) {
                EntityUtils.consume(response.getEntity());
            }
        });
        long cost = System.currentTimeMillis() - start;

        assertTrue(cost < SLOW_HANDLER_SLEEP_MS - 1_000,
                "轻量客户端也必须配超时，实际耗时 " + cost + "ms");
    }

    @Test
    @DisplayName("超时配置不影响正常调用（快接口仍能成功）")
    void fastEndpointStillWorks() throws Exception {
        String body = restTemplate.getForObject(baseUrl + "/fast", String.class);
        assertEquals("fast", body);
    }
}

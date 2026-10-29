package com.qqai.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.qqai.capability.im.ImGateway;
import com.qqai.capability.im.ImSendResult;
import com.qqai.entity.Bot;
import com.qqai.exception.BizException;
import com.qqai.repository.BotRepository;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/** P3b：出站按 bot 路由（LOCAL 委托、REMOTE 走 OneBot HTTP）。 */
class BotImRouterTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private BotRepository botRepository;
    private ImGateway localGateway;
    private BotImRouter router;
    private HttpServer server;
    private final AtomicReference<String> lastBody = new AtomicReference<>();
    private final AtomicReference<String> lastAuth = new AtomicReference<>();

    @BeforeEach
    void setUp() throws Exception {
        botRepository = mock(BotRepository.class);
        localGateway = mock(ImGateway.class);
        router = new BotImRouter(localGateway, botRepository, mapper);
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/send_group_msg", exchange -> {
            lastBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            lastAuth.set(exchange.getRequestHeaders().getFirst("Authorization"));
            byte[] body = "{\"status\":\"ok\",\"data\":{\"message_id\":\"mock-1\"}}"
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
        server.start();
    }

    @AfterEach
    void tearDown() {
        if (server != null) server.stop(0);
    }

    private Bot bot(long id, String runtimeType, String url) {
        Bot bot = new Bot();
        bot.setId(id);
        bot.setRuntimeType(runtimeType);
        bot.setOnebotUrl(url);
        bot.setAgentToken("agent-token");
        return bot;
    }

    @Test
    @DisplayName("REMOTE：POST 到 bot 的 onebotUrl，带 Bearer token，返回 message_id")
    void remoteRoutesToBotOnebot() {
        Bot bot = bot(6L, "REMOTE", "http://127.0.0.1:" + server.getAddress().getPort());
        given(botRepository.findById(6L)).willReturn(Optional.of(bot));
        ArrayNode segments = mapper.createArrayNode().add(
                mapper.createObjectNode().put("type", "text").set("data", mapper.createObjectNode().put("text", "hi")));

        ImSendResult result = router.sendGroupSegments(6L, "123", segments);

        assertTrue(result.success());
        assertEquals("mock-1", result.messageId());
        assertTrue(lastBody.get().contains("\"group_id\":\"123\""));
        assertEquals("Bearer agent-token", lastAuth.get());
    }

    @Test
    @DisplayName("LOCAL/默认 bot：委托给本地 ImGateway")
    void localDelegates() {
        Bot bot = bot(1L, "LOCAL", null);
        given(botRepository.findById(1L)).willReturn(Optional.of(bot));
        given(localGateway.sendGroupSegments(anyString(), any())).willReturn(new ImSendResult(true, "local-1", null));

        ImSendResult result = router.sendGroupSegments(1L, "123", mapper.createArrayNode());

        assertEquals("local-1", result.messageId());
        verify(localGateway).sendGroupSegments(anyString(), any());
    }

    @Test
    @DisplayName("REMOTE 缺 onebotUrl：409，不发起请求")
    void remoteWithoutUrlRejected() {
        Bot bot = bot(6L, "REMOTE", null);
        given(botRepository.findById(6L)).willReturn(Optional.of(bot));

        BizException ex = assertThrows(BizException.class,
                () -> router.sendGroupMessage(6L, "123", "hi"));
        assertEquals(409, ex.getCode());
    }
}

package com.qqai.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.qqai.capability.im.ImGateway;
import com.qqai.capability.im.ImSendResult;
import com.qqai.entity.Bot;
import com.qqai.exception.BizException;
import com.qqai.repository.BotRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * 出站按 bot 路由：
 * - 默认/本机 bot（id=1 或 LOCAL）走现有 NapCat ImGateway；
 * - REMOTE 走该 bot 的 OneBot HTTP（onebotUrl），Bearer 用 agentToken（MVP 兼作 OneBot token）。
 */
@Service
public class BotImRouter {

    private static final Logger log = LoggerFactory.getLogger(BotImRouter.class);

    private final ImGateway localGateway;
    private final BotRepository botRepository;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(3))
            .build();

    public BotImRouter(ImGateway localGateway, BotRepository botRepository, ObjectMapper objectMapper) {
        this.localGateway = localGateway;
        this.botRepository = botRepository;
        this.objectMapper = objectMapper;
    }

    public ImSendResult sendGroupMessage(Long botId, String groupId, String text) {
        ObjectNode segment = objectMapper.createObjectNode();
        segment.put("type", "text");
        segment.putObject("data").put("text", text);
        ArrayNode segments = objectMapper.createArrayNode().add(segment);
        return sendGroupSegments(botId, groupId, segments);
    }

    public ImSendResult sendGroupSegments(Long botId, String groupId, ArrayNode segments) {
        Bot bot = resolve(botId);
        if (isLocal(bot)) {
            return localGateway.sendGroupSegments(groupId, segments);
        }
        ObjectNode body = objectMapper.createObjectNode();
        body.put("group_id", groupId);
        body.set("message", segments);
        JsonNode response = postAction(bot, "/send_group_msg", body);
        String messageId = response.path("data").path("message_id").asText(null);
        return new ImSendResult(true, messageId, null);
    }

    public boolean sendPrivateMessage(Long botId, String qq, String message) {
        Bot bot = resolve(botId);
        if (isLocal(bot)) {
            return localGateway.sendPrivateMessage(qq, message);
        }
        ObjectNode body = objectMapper.createObjectNode();
        body.put("user_id", qq);
        body.put("message", message);
        postAction(bot, "/send_private_msg", body);
        return true;
    }

    private Bot resolve(Long botId) {
        if (botId == null || botId == 1L) {
            return botRepository.findById(1L).orElse(null);
        }
        return botRepository.findById(botId)
                .orElseThrow(() -> new BizException(404, "机器人不存在: " + botId));
    }

    private boolean isLocal(Bot bot) {
        if (bot == null) return true;
        if ("REMOTE".equals(bot.getRuntimeType())) {
            if (bot.getOnebotUrl() == null || bot.getOnebotUrl().isBlank()) {
                throw new BizException(409, "远程机器人未配置 OneBot URL");
            }
            return false;
        }
        if (!Long.valueOf(1L).equals(bot.getId())) {
            throw new BizException(409, "本机多实例尚未支持，请使用 REMOTE 机器人");
        }
        return true;
    }

    private JsonNode postAction(Bot bot, String path, ObjectNode body) {
        String base = bot.getOnebotUrl().endsWith("/")
                ? bot.getOnebotUrl().substring(0, bot.getOnebotUrl().length() - 1)
                : bot.getOnebotUrl();
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(base + path))
                    .timeout(Duration.ofSeconds(10))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + (bot.getAgentToken() == null ? "" : bot.getAgentToken()))
                    .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(body)))
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            JsonNode json = response.body() == null || response.body().isBlank()
                    ? objectMapper.createObjectNode()
                    : objectMapper.readTree(response.body());
            if (response.statusCode() != 200 || !"ok".equals(json.path("status").asText())) {
                String detail = json.path("message").asText(json.path("wording").asText(""));
                log.warn("远程机器人发送失败 bot={} status={} detail={}", bot.getId(), response.statusCode(), detail);
                throw new BizException(502, "远程机器人发送失败: " + (detail.isBlank() ? "HTTP " + response.statusCode() : detail));
            }
            return json;
        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            throw new BizException(502, "无法连接远程机器人: " + e.getMessage());
        }
    }
}

package com.qqai.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.qqai.entity.Bot;
import com.qqai.exception.BizException;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/** 调远程 bot agent 的 HTTP 客户端（/qrcode、/status）。 */
@Service
public class BotAgentClient {

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(3))
            .build();
    private final ObjectMapper objectMapper;

    public BotAgentClient(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public byte[] fetchQrCode(Bot bot) {
        HttpResponse<byte[]> response = send(bot, "/qrcode");
        if (response.statusCode() != 200 || response.body() == null || response.body().length == 0) {
            throw new BizException(502, "远程 agent 未返回二维码（HTTP " + response.statusCode() + "）");
        }
        return response.body();
    }

    public JsonNode fetchStatus(Bot bot) {
        HttpResponse<String> response = sendText(bot, "/status");
        if (response.statusCode() != 200) {
            throw new BizException(502, "远程 agent 状态查询失败（HTTP " + response.statusCode() + "）");
        }
        try {
            return objectMapper.readTree(response.body());
        } catch (Exception e) {
            throw new BizException(502, "远程 agent 状态响应不是合法 JSON");
        }
    }

    private HttpResponse<byte[]> send(Bot bot, String path) {
        try {
            return httpClient.send(request(bot, path).build(), HttpResponse.BodyHandlers.ofByteArray());
        } catch (Exception e) {
            throw new BizException(502, "无法连接远程 agent: " + e.getMessage());
        }
    }

    private HttpResponse<String> sendText(Bot bot, String path) {
        try {
            return httpClient.send(request(bot, path).build(), HttpResponse.BodyHandlers.ofString());
        } catch (Exception e) {
            throw new BizException(502, "无法连接远程 agent: " + e.getMessage());
        }
    }

    private HttpRequest.Builder request(Bot bot, String path) {
        String base = bot.getAgentUrl();
        if (base == null || base.isBlank()) {
            throw new BizException(409, "该机器人尚未配置 agentUrl");
        }
        base = base.endsWith("/") ? base.substring(0, base.length() - 1) : base;
        return HttpRequest.newBuilder(URI.create(base + path))
                .timeout(Duration.ofSeconds(5))
                .header("X-Agent-Token", bot.getAgentToken() == null ? "" : bot.getAgentToken())
                .GET();
    }
}

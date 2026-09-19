package com.qqai.service;

import com.qqai.common.ProcessManager;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.apache.hc.client5.http.classic.methods.HttpPost;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.CloseableHttpResponse;
import org.apache.hc.core5.http.io.entity.StringEntity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;

@Service
public class AstrBotService {

    private static final Logger log = LoggerFactory.getLogger(AstrBotService.class);

    @Value("${astrbot.api-url}")
    private String astrBotApiUrl;

    @Value("${astrbot.token}")
    private String astrBotToken;

    @Autowired
    private CloseableHttpClient httpClient;

    @Autowired
    private ProcessManager processManager;

    @Autowired
    private PromptTemplateService promptTemplateService;

    @Autowired(required = false)
    private com.qqai.repository.FileRecordRepository fileRecordRepository;

    @Value("${server.port:8081}")
    private int serverPort;

    @Value("${server.address:localhost}")
    private String serverAddress;

    /**
     * 摘要使用的模型名。留空表示不指定,交给 AstrBot 使用其默认模型
     * (此前硬编码 "gpt-3.5-turbo",在只挂了 Kimi/DeepSeek 等模型的实例上会被拒或返回空)。
     */
    @Value("${astrbot.summary-model:}")
    private String summaryModel;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private Process astrbotProcess;

    public String summarizeMessage(String content) throws Exception {
        return summarizeMessage(content, null, null);
    }

    public String summarizeMessage(String content, String apiKey) throws Exception {
        return summarizeMessage(content, apiKey, null);
    }

    /**
     * 单条消息摘要（从 prompts.yml 渲染模板，注入群类型上下文）
     * @param content  消息内容
     * @param apiKey   用户 API Key（可选）
     * @param groupType 群类型（可选，用于按群类型定制摘要策略）
     */
    public String summarizeMessage(String content, String apiKey, String groupType) throws Exception {
        return doSummarize(content, apiKey, groupType, "summary.single");
    }

    /**
     * 结构化单条消息摘要（输出 JSON：tags + summary + sentiment，便于前端渲染）
     */
    public String summarizeMessageStructured(String content, String apiKey, String groupType) throws Exception {
        return doSummarize(content, apiKey, groupType, "summary.structured");
    }

    public String renderMessageForSummary(com.qqai.entity.Message message) {
        if (message == null) return "";
        try {
            java.util.Map<String, com.qqai.entity.FileRecord> cache = new java.util.HashMap<>();
            if (fileRecordRepository != null && message.getFileId() != null && !message.getFileId().isBlank()) {
                fileRecordRepository.findByFileId(message.getFileId()).ifPresent(fr -> cache.put(fr.getFileId(), fr));
            }
            String base = "http://" + ("0.0.0.0".equals(serverAddress) ? "localhost" : serverAddress) + ":" + serverPort;
            com.qqai.util.RichMessageRenderer.RenderedMessage rm =
                    com.qqai.util.RichMessageRenderer.renderSingle(message, cache, base);
            StringBuilder sb = new StringBuilder(rm.getText() != null ? rm.getText() : "");
            if (rm.getImageUrls() != null && !rm.getImageUrls().isEmpty()) {
                sb.append("\n附图：");
                int i = 1;
                for (String u : rm.getImageUrls()) sb.append("\n附图 ").append(i++).append(": ").append(u);
            }
            String result = sb.toString();
            if (result.trim().isEmpty() && message.getContent() != null) result = message.getContent();
            return result;
        } catch (Exception e) {
            return (message.getContent() != null) ? message.getContent() : "";
        }
    }

    private String doSummarize(String content, String apiKey, String groupType, String templateKey) throws Exception {
        if (content == null || content.trim().isEmpty()) {
            return "";
        }
        // 从 prompts.yml 渲染摘要模板
        java.util.Map<String, Object> vars = new java.util.HashMap<>();
        vars.put("content", content);
        String message = promptTemplateService.render(templateKey, groupType, vars);
        return chat(message, apiKey, templateKey);
    }

    /**
     * 调用 AstrBot /api/v1/chat 的通用入口（摘要、群类型识别等共用同一套已验证逻辑）。
     *
     * 统一处理三件容易踩的事：
     * 1. username 是必填字段，缺失时 AstrBot 直接返回 {"status":"error","message":"Missing key: username"}；
     * 2. 响应是 SSE 文本流，不能按整段 JSON 去取 response 字段；
     * 3. 响应为空时打出状态码与响应片段，便于直接定位。
     *
     * @param message 已渲染好的提示词
     * @param apiKey  用户 API Key（可选，为空则用配置中的 ASTRBOT_TOKEN）
     * @param tag     日志标记，用于区分调用来源
     * @return 纯文本回复；调用失败或响应为空返回 null
     */
    public String chat(String message, String apiKey, String tag) throws Exception {
        if (message == null || message.isBlank()) {
            return null;
        }
        String token = (apiKey != null && !apiKey.isEmpty()) ? apiKey : astrBotToken;
        HttpPost httpPost = new HttpPost(astrBotApiUrl + "/api/v1/chat");
        httpPost.setHeader("Content-Type", "application/json");
        httpPost.setHeader("X-API-Key", token);

        ObjectNode requestBody = objectMapper.createObjectNode();
        requestBody.put("message", message);
        // AstrBot /api/v1/chat 必填字段:缺失会直接返回 {"status":"error","message":"Missing key: username"}
        requestBody.put("username", "summarizer");
        requestBody.put("enable_streaming", false);   // 关流式,响应格式稳定(与控制台链路一致)
        // AstrBot 4.28.x 新增 flags.enable_reasoning（默认 true）：开启时响应里会带上「🤔 思考:…」
        // 推理过程，会被我们当成摘要正文。这里显式关闭，保证拿到的是最终回答。
        ObjectNode flags = requestBody.putObject("flags");
        flags.put("enable_streaming", false);
        flags.put("enable_reasoning", false);
        requestBody.put("temperature", 0.7);
        if (summaryModel != null && !summaryModel.isBlank()) {
            requestBody.put("model", summaryModel.trim());
        }

        httpPost.setEntity(new StringEntity(requestBody.toString(), java.nio.charset.StandardCharsets.UTF_8));

        try (CloseableHttpResponse response = httpClient.execute(httpPost)) {
            BufferedReader reader = new BufferedReader(
                new InputStreamReader(response.getEntity().getContent(), java.nio.charset.StandardCharsets.UTF_8)
            );
            StringBuilder responseContent = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                responseContent.append(line).append('\n');
            }

            String rawBody = responseContent.toString();
            String reply = extractChatReply(rawBody);
            if (reply == null || reply.isBlank()) {
                // 打出发送内容与响应片段,便于下次直接定位(此前只看到"空摘要",无从下手)
                log.warn("AstrBot 响应为空: tag={}, status={}, body前300字符={}",
                        tag, response.getCode(),
                        rawBody.length() > 300 ? rawBody.substring(0, 300) : rawBody);
                return null;
            }
            return reply;
        }
    }

    /**
     * 解析 AstrBot /api/v1/chat 的响应,返回纯文本回复。
     *
     * AstrBot 返回的是 SSE 文本流(每行一条 `data: {json}`),
     * 内容位于 type=plain 的 data 字段中;老版本/兼容场景也可能是整段 JSON。
     * 两种格式都兼容,解析不到时返回 null。
     */
    public String extractChatReply(String rawBody) {
        if (rawBody == null || rawBody.isBlank()) {
            return null;
        }
        // 1) SSE 文本流:逐行取 `data: {...}` 中 type=plain 的 data
        StringBuilder reply = new StringBuilder();
        for (String line : rawBody.split("\n")) {
            String trimmed = line.trim();
            if (!trimmed.startsWith("data:")) {
                continue;
            }
            String jsonData = trimmed.substring(5).trim();
            if (jsonData.isEmpty() || "[DONE]".equals(jsonData)) {
                continue;
            }
            try {
                JsonNode json = objectMapper.readTree(jsonData);
                String type = json.has("type") ? json.get("type").asText() : null;
                if ("plain".equals(type)) {
                    JsonNode data = json.get("data");
                    if (data != null && !data.isNull()) {
                        reply.append(data.asText());
                    }
                }
            } catch (Exception ignore) {
                // 忽略单行解析失败,继续处理后续行
            }
        }
        String text = reply.toString().trim();
        if (!text.isEmpty()) {
            return text;
        }

        // 2) 兼容整段 JSON:response / data / text 字段
        //    注意:必须排除错误响应,否则会把 {"status":"error","message":"Missing key: username"}
        //    这类报错当成摘要写进数据库(曾发生过)。
        try {
            JsonNode json = objectMapper.readTree(rawBody.trim());
            String status = json.has("status") ? json.get("status").asText() : null;
            if ("error".equalsIgnoreCase(status) || "failed".equalsIgnoreCase(status)) {
                log.warn("AstrBot 返回错误响应: {}", json.has("message") ? json.get("message").asText() : rawBody.trim());
                return null;
            }
            if (json.has("retcode") && json.get("retcode").asInt(0) != 0) {
                log.warn("AstrBot 返回非 0 retcode: {}", json.get("retcode").asInt());
                return null;
            }
            for (String field : new String[]{"response", "data", "text"}) {
                JsonNode node = json.get(field);
                if (node != null && !node.isNull() && !node.asText().isBlank()) {
                    return node.asText().trim();
                }
            }
        } catch (Exception ignore) {
            // 非 JSON,交给调用方按"空响应"处理
        }
        return null;
    }

    public String getApiUrl() {
        return astrBotApiUrl;
    }

    public void startAstrBot() throws Exception {
        // 幂等性检查 1：进程引用仍存活
        if (astrbotProcess != null && astrbotProcess.isAlive()) {
            log.info("AstrBot 进程已存在（PID={}），跳过启动", astrbotProcess.pid());
            return;
        }
        // 幂等性检查 2：后端重启后进程引用丢失，但插件可能仍在运行（端口检测）
        if (processManager.isPortOpen("localhost", 6185)) {
            log.info("AstrBot 端口 6185 已监听，跳过启动（后端重启场景）");
            return;
        }

        String projectRoot = System.getProperty("user.dir");
        String astrbotPath = projectRoot + File.separator + ".." + File.separator + ".." + File.separator + "astrbot";
        File astrbotDir = new File(astrbotPath).getCanonicalFile();

        if (!astrbotDir.exists()) {
            throw new IOException("AstrBot directory not found at: " + astrbotDir.getAbsolutePath());
        }

        ProcessBuilder processBuilder = new ProcessBuilder("cmd.exe", "/c", "start", "cmd.exe", "/k", "astrbot", "run");
        processBuilder.directory(astrbotDir);
        processBuilder.inheritIO();

        astrbotProcess = processBuilder.start();
        Thread.sleep(3000);
        log.info("AstrBot started from: {}", astrbotDir.getAbsolutePath());
    }

    public void stopAstrBot() throws Exception {
        // 方式 1：杀存储的进程引用
        if (astrbotProcess != null && astrbotProcess.isAlive()) {
            processManager.destroyProcess(astrbotProcess);
            astrbotProcess = null;
            log.info("AstrBot 进程引用已销毁");
        }
        // 方式 2：通过端口找残留 PID（后端重启场景 / cmd 分离窗口场景）
        processManager.killProcessByPort(6185, "AstrBot");
        log.info("AstrBot stopped");
    }
}

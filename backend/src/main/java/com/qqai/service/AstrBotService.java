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
        String token = (apiKey != null && !apiKey.isEmpty()) ? apiKey : astrBotToken;
        HttpPost httpPost = new HttpPost(astrBotApiUrl + "/api/v1/chat");
        httpPost.setHeader("Content-Type", "application/json");
        httpPost.setHeader("X-API-Key", token);

        // 从 prompts.yml 渲染摘要模板
        java.util.Map<String, Object> vars = new java.util.HashMap<>();
        vars.put("content", content);
        String message = promptTemplateService.render(templateKey, groupType, vars);

        ObjectNode requestBody = objectMapper.createObjectNode();
        requestBody.put("message", message);
        requestBody.put("model", "gpt-3.5-turbo");
        requestBody.put("temperature", 0.7);

        httpPost.setEntity(new StringEntity(requestBody.toString(), java.nio.charset.StandardCharsets.UTF_8));

        try (CloseableHttpResponse response = httpClient.execute(httpPost)) {
            BufferedReader reader = new BufferedReader(
                new InputStreamReader(response.getEntity().getContent(), java.nio.charset.StandardCharsets.UTF_8)
            );
            StringBuilder responseContent = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                responseContent.append(line);
            }

            JsonNode responseJson = objectMapper.readTree(responseContent.toString());
            JsonNode responseNode = responseJson.get("response");
            return responseNode != null ? responseNode.asText() : null;
        }
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

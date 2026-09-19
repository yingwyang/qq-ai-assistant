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

    /**
     * 带图调用时使用的 AstrBot 配置文件（config profile）名。
     *
     * <p>摘要/分析走的是 Agent 管线，模型由配置文件里的 provider 决定（请求体里的 model 对
     * Agent 不生效）。默认配置文件指向纯文本模型时，图片会被降级成文本，于是模型只能答
     * "内容未知"。因此带图请求显式切换到视觉配置文件（AstrBot 中名为 vision）。</p>
     */
    @Value("${astrbot.vision-config-name:vision}")
    private String visionConfigName;

    /**
     * 摘要/分析专用的视觉配置文件（同模型，但 AstrBot 人格不挂工具）。
     *
     * <p>Agent 管线会按人格注入工具：{@code persona.tools = null} 时注入<b>全部</b>工具，
     * 于是模型会去调用 {@code send_message_to_user} / {@code future_task}，回复
     * 「请稍等片刻」，结构化摘要里就只剩工具调用文本。所以抽取任务走一个 tools=[] 的人格。</p>
     */
    @Value("${astrbot.vision-task-config-name:vision-task}")
    private String visionTaskConfigName;

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
        return doSummarize(content, apiKey, groupType, "summary.single", null);
    }

    /**
     * 结构化单条消息摘要（输出 JSON：tags + summary + sentiment，便于前端渲染）
     *
     * @param personaConfigName 「人层」配置档案名（由 {@code AstrBotPersonaService} 按用户所选人格给出），
     *                          传 null 表示使用默认档案
     */
    public String summarizeMessageStructured(String content, String apiKey, String groupType,
                                             String personaConfigName) throws Exception {
        String reply = doSummarize(content, apiKey, groupType, "summary.structured", personaConfigName);
        if (needsNeutralRetry(reply, personaConfigName)) {
            log.info("人格档案输出不是结构化 JSON，用中性档案重试一次（岗位格式优先于人格）");
            reply = doSummarize(content, apiKey, groupType, "summary.structured", null);
        }
        return reply;
    }

    public String summarizeMessageStructured(String content, String apiKey, String groupType) throws Exception {
        return doSummarize(content, apiKey, groupType, "summary.structured", null);
    }

    /**
     * 带图摘要：把消息里的图片作为「消息段」一并送出，并使用视觉配置文件。
     *
     * <p>之前的实现只把图片当文本（"附图 1: http://…"）发给模型，模型看不到画面，
     * 于是只能答"内容未知"。这里改为：上传图片到 AstrBot 换 attachment_id → 与提示词拼成
     * {@code [plain, image…]} → 指定 vision profile 调用。</p>
     *
     * @param personaConfigName 「人层」配置档案名（视觉版），null 表示用内置的 vision-task
     */
    public String summarizeMessageStructured(String content, String apiKey, String groupType,
                                             java.util.List<String> imageUrls,
                                             String personaConfigName) throws Exception {
        if (imageUrls == null || imageUrls.isEmpty()) {
            return summarizeMessageStructured(content, apiKey, groupType, personaConfigName);
        }
        if (content == null || content.trim().isEmpty()) {
            return "";
        }
        java.util.Map<String, Object> vars = new java.util.HashMap<>();
        vars.put("content", content);
        String prompt = promptTemplateService.render("summary.structured", groupType, vars);
        java.util.List<String> attachmentIds = uploadImages( imageUrls, apiKey);
        if (attachmentIds.isEmpty()) {
            return doSummarize(content, apiKey, groupType, "summary.structured", personaConfigName);
        }
        String promptWithHint = prompt + imageAttachmentHint(attachmentIds.size());
        Object parts = buildImageParts(promptWithHint, attachmentIds);
        String reply = chat(parts, apiKey, "summary.structured.image", true, personaConfigName);
        if (needsNeutralRetry(reply, personaConfigName)) {
            log.info("人格档案输出不是结构化 JSON，用中性档案重试一次（岗位格式优先于人格）");
            reply = chat(parts, apiKey, "summary.structured.image.retry", true, null);
        }
        return reply;
    }

    /**
     * 重人设（角色扮演）会倾向于用散文回答，导致结构化摘要解析不出 tags/sentiment。
     * 岗位层的格式要求优先：命中这里就换中性档案重试一次。
     */
    static boolean needsNeutralRetry(String reply, String personaConfigName) {
        if (personaConfigName == null || personaConfigName.isBlank()) return false;
        if (reply == null || reply.isBlank()) return true;
        String t = reply.trim();
        // 正常链路 sanitizeReply 已经去过围栏，这里再兜一层，保证判定用的是 JSON 本体
        t = t.replaceAll("^```[a-zA-Z]*\\s*", "").replaceAll("\\s*```$", "").trim();
        // 允许 JSON 后面跟少量尾巴（例如模型多打了一句话）时也能解析，交给解析器取最后一个对象
        return !(t.startsWith("{") && t.endsWith("}"))
                && !(t.startsWith("{") && t.contains("}"));
    }

    public String summarizeMessageStructured(String content, String apiKey, String groupType,
                                             java.util.List<String> imageUrls) throws Exception {
        return summarizeMessageStructured(content, apiKey, groupType, imageUrls, null);
    }

    /**
     * 带图提示词补充说明。
     *
     * <p>摘要模板里没有"图片已随附"的说法，模型面对 {@code [图片] /images/…jpg} 这类文本时
     * 会保守地回答"内容未知/未提供可辨内容"。这里明确告知图片已作为多模态消息段送出，
     * 要求直接描述画面，避免视觉能力被"不确定"话术浪费掉。</p>
     */
    static String imageAttachmentHint(int count) {
        return "\n\n【重要】本次请求已随附 " + count + " 张真实图片（多模态消息段，可直接看到画面）。"
                + "请直接描述图片的实际内容（人物/物体/场景/截图文字/表情包含义等），"
                + "不要再输出「内容未知」「未提供可辨内容」「无法查看图片」这类占位说法；"
                + "只有图片确实模糊到无法辨认时，才说明无法辨认。"
                + "输出格式仍严格遵循上面的要求（该只输出 JSON 的，仍然只输出 JSON，不要加解释）。";
    }

    /** 取出一条消息里的图片 URL（供带图摘要使用） */
    public java.util.List<String> renderMessageImageUrls(com.qqai.entity.Message message) {
        if (message == null) return java.util.List.of();
        try {
            java.util.Map<String, com.qqai.entity.FileRecord> cache = new java.util.HashMap<>();
            if (fileRecordRepository != null && message.getFileId() != null && !message.getFileId().isBlank()) {
                fileRecordRepository.findByFileId(message.getFileId()).ifPresent(fr -> cache.put(fr.getFileId(), fr));
            }
            String base = "http://" + ("0.0.0.0".equals(serverAddress) ? "localhost" : serverAddress) + ":" + serverPort;
            com.qqai.util.RichMessageRenderer.RenderedMessage rm =
                    com.qqai.util.RichMessageRenderer.renderSingle(message, cache, base);
            return rm.getImageUrls() == null ? java.util.List.of() : rm.getImageUrls();
        } catch (Exception e) {
            log.debug("提取消息图片 URL 失败: {}", e.getMessage());
            return java.util.List.of();
        }
    }

    /** 把图片按 URL 上传到 AstrBot，返回 attachment_id 列表（最多 5 张、单张 ≤10MB） */
    private java.util.List<String> uploadImages(java.util.List<String> imageUrls, String apiKey) {
        java.util.List<String> ids = new java.util.ArrayList<>();
        if (imageUrls == null || imageUrls.isEmpty()) return ids;
        String token = (apiKey != null && !apiKey.isEmpty()) ? apiKey : astrBotToken;
        for (String url : imageUrls) {
            if (ids.size() >= 5) break;
            try {
                java.io.File f = resolveLocalFile(url);
                if (f == null || !f.exists() || !f.isFile()) {
                    log.debug("带图摘要跳过不存在的图片: {}", url);
                    continue;
                }
                if (f.length() > 10L * 1024 * 1024) {
                    log.debug("带图摘要跳过超大图片({}B): {}", f.length(), url);
                    continue;
                }
                org.springframework.http.HttpHeaders headers = new org.springframework.http.HttpHeaders();
                headers.set("X-API-Key", token);
                headers.setContentType(org.springframework.http.MediaType.MULTIPART_FORM_DATA);
                org.springframework.util.MultiValueMap<String, Object> body =
                        new org.springframework.util.LinkedMultiValueMap<>();
                body.add("file", new org.springframework.core.io.FileSystemResource(f));
                org.springframework.http.HttpEntity<org.springframework.util.MultiValueMap<String, Object>> entity =
                        new org.springframework.http.HttpEntity<>(body, headers);
                org.springframework.web.client.RestTemplate rt = new org.springframework.web.client.RestTemplate();
                String respBody = rt.postForObject(astrBotApiUrl + "/api/v1/file", entity, String.class);
                if (respBody != null) {
                    JsonNode node = objectMapper.readTree(respBody);
                    String id = node.path("data").path("attachment_id").asText(null);
                    if (id != null && !id.isEmpty()) ids.add(id);
                }
            } catch (Exception e) {
                log.debug("图片上传失败 {}: {}", url, e.getMessage());
            }
        }
        return ids;
    }

    /** URL → 本地文件（与控制器同口径：/images/** → uploads/images/、/uploads/** → uploads/） */
    private java.io.File resolveLocalFile(String imageUrl) {
        if (imageUrl == null || imageUrl.isBlank()) return null;
        String path = imageUrl;
        if (path.startsWith("http://") || path.startsWith("https://")) {
            int idx = path.indexOf("://");
            int start = path.indexOf("/", idx + 3);
            path = start >= 0 ? path.substring(start) : "";
        }
        if (path.startsWith("/images/")) return new java.io.File("uploads" + path);
        if (path.startsWith("/uploads/")) return new java.io.File("uploads" + path.substring("/uploads".length()));
        java.io.File direct = new java.io.File(path);
        return direct.exists() ? direct : null;
    }

    /** 拼 [plain, image…] 消息段 */
    private Object buildImageParts(String prompt, java.util.List<String> attachmentIds) {
        com.fasterxml.jackson.databind.node.ArrayNode arr = objectMapper.createArrayNode();
        com.fasterxml.jackson.databind.node.ObjectNode text = objectMapper.createObjectNode();
        text.put("type", "plain");
        text.put("text", prompt);
        arr.add(text);
        for (String id : attachmentIds) {
            com.fasterxml.jackson.databind.node.ObjectNode img = objectMapper.createObjectNode();
            img.put("type", "image");
            img.put("attachment_id", id);
            arr.add(img);
        }
        log.info("摘要请求带图: {} 张", attachmentIds.size());
        return arr;
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
                // 图片本体由 summarizeMessageStructured(..., imageUrls) 作为消息段送出；
                // 纯文本里只留计数，不再塞 http://…（模型访问不到，还白占上下文）。
                sb.append("\n（含 ").append(rm.getImageUrls().size()).append(" 张图片）");
            }
            String result = sb.toString();
            if (result.trim().isEmpty() && message.getContent() != null) result = message.getContent();
            return result;
        } catch (Exception e) {
            return (message.getContent() != null) ? message.getContent() : "";
        }
    }

    private String doSummarize(String content, String apiKey, String groupType, String templateKey,
                              String personaConfigName) throws Exception {
        if (content == null || content.trim().isEmpty()) {
            return "";
        }
        // 从 prompts.yml 渲染摘要模板（= 岗位层）
        java.util.Map<String, Object> vars = new java.util.HashMap<>();
        vars.put("content", content);
        String message = promptTemplateService.render(templateKey, groupType, vars);
        return chat(message, apiKey, templateKey, personaConfigName);
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
        return doChat(message, apiKey, tag, false, null);
    }

    /**
     * 指定「人层」档案的对话入口（人格由 AstrBot 提供，规则由调用方提示词提供）。
     *
     * @param personaConfigName {@code qqai-p-<slug>-text} 之类的档案名；null = 用默认档案
     */
    public String chat(String message, String apiKey, String tag, String personaConfigName) throws Exception {
        return doChat(message, apiKey, tag, false, personaConfigName);
    }

    /**
     * 带视觉配置的对话入口：message 可以是纯文本，也可以是
     * {@code [{"type":"plain",…},{"type":"image","attachment_id":…}]} 消息段数组。
     *
     * <p>withVision=true 时会额外带上 {@code config_name}，把这次请求切到视觉配置文件，
     * 否则 Agent 会用默认（纯文本）模型，图片段被丢弃、模型只能答"内容未知"。</p>
     */
    public String chat(Object message, String apiKey, String tag, boolean withVision) throws Exception {
        return doChat(message, apiKey, tag, withVision, null);
    }

    /** 视觉 + 指定人格档案：图片用视觉模型，说话方式用用户选的人格 */
    public String chat(Object message, String apiKey, String tag, boolean withVision,
                       String personaConfigName) throws Exception {
        return doChat(message, apiKey, tag, withVision, personaConfigName);
    }

    private String doChat(Object message, String apiKey, String tag, boolean withVision,
                          String personaConfigName) throws Exception {
        if (message == null) {
            return null;
        }
        if (message instanceof String && ((String) message).isBlank()) {
            return null;
        }
        String token = (apiKey != null && !apiKey.isEmpty()) ? apiKey : astrBotToken;
        HttpPost httpPost = new HttpPost(astrBotApiUrl + "/api/v1/chat");
        httpPost.setHeader("Content-Type", "application/json");
        httpPost.setHeader("X-API-Key", token);

        ObjectNode requestBody = objectMapper.createObjectNode();
        if (message instanceof String) {
            requestBody.put("message", (String) message);
        } else {
            requestBody.set("message", objectMapper.valueToTree(message));
        }
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
        // 「人层」档案优先级：调用方按用户所选人格给的档案 > 带图时内置的 vision-task。
        // 人格在 AstrBot 里是绑在配置档案上的（/api/v1/chat 没有 persona 字段），
        // 所以「人」的落地方式就是指定档案；档案由 AstrBotPersonaService 预先生成。
        String personaCfg = (personaConfigName != null && !personaConfigName.isBlank())
                ? personaConfigName.trim() : null;
        String cfg = personaCfg;
        if (cfg == null && withVision) {
            // 视觉请求必须走视觉档案：Agent 的模型来自 profile，而不是请求体里的 model。
            // 用 vision-task（同模型 + 无工具人格），避免模型走 Agent 工具调用而不产出摘要。
            cfg = (visionTaskConfigName != null && !visionTaskConfigName.isBlank())
                    ? visionTaskConfigName.trim()
                    : visionConfigName;
        }
        if (cfg != null && !cfg.isBlank()) {
            requestBody.put("config_name", cfg);
            // 每次请求用一个全新会话：AstrBot 的会话记录里带 persona_id 且优先级高于档案，
            // 复用旧会话会沿用旧人格（tools=null 的人格会挂上全部工具），
            // 模型于是去调 send_message_to_user/future_task，而不是输出摘要。
            requestBody.put("session_id", "qqai-" + java.util.UUID.randomUUID());
        }
        if (withVision) {
            // 带图时不注入默认人格：人格提示词会诱导模型描述"我看到了什么"的元话术，
            // 与摘要任务的 JSON 输出要求冲突（与控制器 /send-with-image 同一策略）。
            flags.put("enable_default_system_prompt", false);
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
            return sanitizeReply(reply);
        }
    }

    /**
     * 清理 AstrBot 回复里的「Agent 工具调用」文本。
     *
     * <p>4.28 的 Agent 管线会把工具调用意图直接写进正文，例如：
     * {@code <tool_call>send_message_to_user<arg_key>messages</arg_key><arg_value>[{...}]</arg_value></tool_call>}，
     * 甚至残留 JSON 片段。这些不是给用户看的内容，统一剥离，避免混进摘要与聊天气泡。</p>
     */
    public static String sanitizeReply(String text) {
        if (text == null || text.isBlank()) return text;
        String s = text;
        // 0) 推理块：<think>…</think> / <thinking>…</thinking>；以及只残留闭合标签的情况
        s = s.replaceAll("(?s)<think(?:ing)?>.*?</think(?:ing)?>", "");
        int lastThinkClose = s.lastIndexOf("</think");
        if (lastThinkClose >= 0) {
            int gt = s.indexOf('>', lastThinkClose);
            s = gt >= 0 ? s.substring(gt + 1) : "";
        }
        // 1) 成对的 <tool_call>...</tool_call>
        s = s.replaceAll("(?s)<tool_call>.*?</tool_call>", "");
        // 2) 未闭合的 <tool_call> 起始（截到末尾）
        int idx = s.indexOf("<tool_call>");
        if (idx >= 0) s = s.substring(0, idx);
        // 3) 遗留标签
        s = s.replaceAll("</?(?:think|thinking|tool_call|arg_key|arg_value|tool_result)>", "");
        // 3.5) 特殊 token：GLM 视觉模型会把答案包成 <|begin_of_box|>…<|end_of_box|>，
        //      留着会让结构化 JSON 解析失败（tags/sentiment 全空，原始 JSON 当正文存库）
        s = s.replaceAll("<\\|[^|>]*\\|>", "");
        // 3.6) Markdown 代码围栏：```json … ``` 去掉围栏只留内容
        s = s.replaceAll("(?s)^\\s*```[a-zA-Z]*\\s*", "").replaceAll("(?s)\\s*```\\s*$", "");
        // 3.7) 整段就是 JSON（结构化摘要/日报要求的输出格式）→ 前面已清掉特殊 token，
        //      直接返回，不要再走「丢 JSON 行 + 按句去重」，那会把合法 JSON 拆坏
        String jsonCandidate = s.trim();
        if ((jsonCandidate.startsWith("{") && jsonCandidate.endsWith("}"))
                || (jsonCandidate.startsWith("[") && jsonCandidate.endsWith("]"))) {
            return jsonCandidate;
        }
        // 4) 行内 JSON 残片：]}, "ts": 1789…} / "ts": 1789…
        s = s.replaceAll("\\]\\}\\s*,?\\s*\"ts\"\\s*:\\s*[0-9.]+\\}?", "");
        s = s.replaceAll("\"ts\"\\s*:\\s*[0-9.]+", "");
        // 5) 整行是 JSON 尾巴（且不含中文）→ 丢弃
        StringBuilder sb = new StringBuilder();
        for (String line : s.split("\n", -1)) {
            String t = line.trim();
            boolean jsonTail = t.matches("^[\\]\\[}{)(,;:].*")
                    && t.matches(".*[\"\\[\\]{}].*")
                    && !t.matches(".*[\\u4e00-\\u9fa5].*");
            if (jsonTail) continue;
            sb.append(line).append('\n');
        }
        // 6) 按句去重：推理模型常把同一句答案重复多遍（先思考一遍、再复述一遍，甚至改写标点）
        String[] sentences = sb.toString().trim().split("(?<=[。！？!?\\n])");
        StringBuilder out = new StringBuilder();
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (String sentence : sentences) {
            String key = sentence.trim();
            if (key.isEmpty()) continue;
            // 归一化后再比较：忽略空白与常见标点/引号差异，避免"同一句换个引号"被当成新句
            String norm = key.replaceAll("[\\s\"'“”‘’《》<>()（）\\[\\]【】,，.。!！?？;；:：、-]", "");
            if (norm.length() > 6 && !seen.add(norm)) continue;   // 短句（如"好的"）允许重复出现
            out.append(key).append('\n');
        }
        String cleaned = out.toString().replaceAll("\\n{3,}", "\n\n").trim();
        return cleaned.isEmpty() ? text.trim() : cleaned;
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

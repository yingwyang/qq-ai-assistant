package com.qqai.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.qqai.entity.AstrBotConversation;
import com.qqai.entity.AstrBotMessage;
import com.qqai.entity.CreditRule;
import com.qqai.entity.FileRecord;
import com.qqai.entity.Message;
import com.qqai.entity.UserSettings;
import com.qqai.entity.enums.CreditTransactionType;
import com.qqai.common.SecurityHelper;
import com.qqai.exception.BizException;
import com.qqai.exception.CreditErrorCode;
import com.qqai.plugin.PluginManager;
import com.qqai.repository.FileRecordRepository;
import com.qqai.service.AstrBotConversationService;
import com.qqai.service.CreditRuleService;
import com.qqai.service.CreditService;
import com.qqai.service.MessageService;
import com.qqai.service.UserSettingsService;
import com.qqai.util.RichMessageRenderer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.*;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.RestTemplate;

import java.io.File;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

/**
 * AstrBot 控制器 - 处理 AstrBot 消息和 AI 分析
 * 
 * 注意：此控制器主要接收 AstrBot 的消息推送，而不是主动调用 AstrBot API
 * 因为 AstrBot 的 API 认证需要 JWT Token，获取 Token 需要登录密码
 */
@RestController
@RequestMapping("/api/astrbot")
public class AstrBotController {

    private static final Logger log = LoggerFactory.getLogger(AstrBotController.class);

    @Autowired
    private MessageService messageService;

    @Autowired
    private AstrBotConversationService conversationService;

    @Autowired
    private PluginManager pluginManager;

    @Autowired
    private SecurityHelper securityHelper;

    @Autowired
    private jakarta.persistence.EntityManager entityManager;

    @Autowired
    private UserSettingsService userSettingsService;

    @Autowired
    private CreditService creditService;

    @Autowired
    private CreditRuleService creditRuleService;

    @Autowired
    private com.qqai.repository.CreditTransactionRepository creditTransactionRepository;

    @Autowired
    private com.qqai.service.PromptTemplateService promptTemplateService;

    @Autowired
    private com.qqai.repository.GroupRepository groupRepository;

    @Autowired
    private FileRecordRepository fileRecordRepository;

    @Value("${astrbot.api-url:http://localhost:6185}")
    private String astrBotApiUrl;

    @Value("${astrbot.token:}")
    private String astrBotToken;

    @Value("${astrbot.data-path:}")
    private String astrBotDataPath;

    @Value("${server.port:8081}")
    private int serverPort;

    @Value("${server.address:localhost}")
    private String serverAddress;

    private final RestTemplate restTemplate = new RestTemplate();

    private final ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());

    private String buildImageBaseUrl() {
        String addr = (serverAddress == null || serverAddress.isBlank() || "0.0.0.0".equals(serverAddress))
                ? "localhost"
                : serverAddress;
        return "http://" + addr + ":" + serverPort;
    }

    private Map<String, FileRecord> buildFileRecordCache(List<Message> msgs) {
        if (msgs == null || msgs.isEmpty()) return new LinkedHashMap<>();
        Set<String> fileIds = msgs.stream()
                .map(Message::getFileId)
                .filter(Objects::nonNull)
                .filter(s -> !s.isBlank())
                .collect(Collectors.toCollection(LinkedHashSet::new));
        List<FileRecord> records = fileRecordRepository.findAllByFileIdIn(fileIds);
        Map<String, FileRecord> cache = new LinkedHashMap<>();
        for (FileRecord fr : records) {
            if (fr != null && fr.getFileId() != null) {
                cache.put(fr.getFileId(), fr);
            }
        }
        return cache;
    }

    private RichMessageRenderer.RenderedBatch buildRenderedBatch(List<Message> msgs) {
        if (msgs == null) msgs = Collections.emptyList();
        Map<String, FileRecord> cache = buildFileRecordCache(msgs);
        return RichMessageRenderer.renderBatch(msgs, cache, buildImageBaseUrl(), 36000);
    }

    /**
     * 将图片 URL（如 http://localhost:8081/images/images/xxx.jpg）映射为本地文件。
     * 规则参考 WebMvcConfig：/images/** → uploads/images/，/uploads/** → uploads/
     */
    private File resolveImageFileFromUrl(String imageUrl) {
        if (imageUrl == null || imageUrl.isBlank()) return null;
        try {
            String path;
            if (imageUrl.startsWith("http://") || imageUrl.startsWith("https://")) {
                int idx = imageUrl.indexOf("://");
                int pathStart = imageUrl.indexOf("/", idx + 3);
                path = pathStart >= 0 ? imageUrl.substring(pathStart) : "";
            } else {
                path = imageUrl;
            }
            if (path.startsWith("/images/")) {
                return new File("uploads" + path);
            } else if (path.startsWith("/uploads/")) {
                return new File("uploads" + path.substring("/uploads".length()));
            }
        } catch (Exception e) {
            log.debug("解析图片URL失败 {}: {}", imageUrl, e.getMessage());
        }
        return null;
    }

    /**
     * 上传图片到 AstrBot /api/v1/file 获取 attachment_id（多模态视觉识别所需）。
     * 限制最多 5 张，单张超过 10MB 跳过。
     * @return attachment_id 列表；全部失败返回空列表
     */
    private List<String> uploadImagesToAstrBot(List<String> imageUrls, String token) {
        if (imageUrls == null || imageUrls.isEmpty()) return Collections.emptyList();
        List<String> attachmentIds = new ArrayList<>();
        int maxImages = 5;
        long maxSize = 10L * 1024 * 1024; // 10MB
        int uploaded = 0;
        for (String imageUrl : imageUrls) {
            if (uploaded >= maxImages) break;
            try {
                File imageFile = resolveImageFileFromUrl(imageUrl);
                if (imageFile == null || !imageFile.exists() || !imageFile.isFile()) {
                    log.debug("图片文件不存在，跳过: {}", imageUrl);
                    continue;
                }
                if (imageFile.length() > maxSize) {
                    log.debug("图片过大({}B)，跳过: {}", imageFile.length(), imageUrl);
                    continue;
                }
                String uploadUrl = astrBotApiUrl + "/api/v1/file";
                HttpHeaders uploadHeaders = new HttpHeaders();
                uploadHeaders.set("X-API-Key", token);
                uploadHeaders.setContentType(MediaType.MULTIPART_FORM_DATA);

                MultiValueMap<String, Object> multipartBody = new LinkedMultiValueMap<>();
                multipartBody.add("file", new FileSystemResource(imageFile));

                HttpEntity<MultiValueMap<String, Object>> uploadEntity = new HttpEntity<>(multipartBody, uploadHeaders);

                RestTemplate uploadRestTemplate = new RestTemplate();
                ResponseEntity<String> uploadResp = uploadRestTemplate.postForEntity(uploadUrl, uploadEntity, String.class);

                if (uploadResp.getStatusCode().is2xxSuccessful() && uploadResp.getBody() != null) {
                    JsonNode respJson = objectMapper.readTree(uploadResp.getBody());
                    JsonNode dataNode = respJson.path("data");
                    String attachmentId = dataNode.path("attachment_id").asText(null);
                    if (attachmentId != null && !attachmentId.isEmpty()) {
                        attachmentIds.add(attachmentId);
                        uploaded++;
                        log.debug("图片上传成功: {} -> attachment_id={}", imageUrl, attachmentId);
                    }
                }
            } catch (Exception e) {
                log.warn("上传图片到AstrBot失败 {}: {}", imageUrl, e.getMessage());
            }
        }
        return attachmentIds;
    }

    /**
     * 构造多模态 message：如果图片上传成功则用列表格式（plain + image），
     * 否则回退为纯文本 prompt（方案B 文本注入URL已在调用方完成）。
     */
    private Object buildMultimodalMessage(String prompt, List<String> imageUrls, String token) {
        if (imageUrls == null || imageUrls.isEmpty()) {
            return prompt;
        }
        try {
            List<String> attachmentIds = uploadImagesToAstrBot(imageUrls, token);
            if (attachmentIds.isEmpty()) {
                log.info("图片全部上传失败，回退到纯文本方案");
                return prompt;
            }
            List<Map<String, Object>> messageParts = new ArrayList<>();
            Map<String, Object> textPart = new HashMap<>();
            textPart.put("type", "plain");
            textPart.put("text", prompt);
            messageParts.add(textPart);
            for (String attachmentId : attachmentIds) {
                Map<String, Object> imagePart = new HashMap<>();
                imagePart.put("type", "image");
                imagePart.put("attachment_id", attachmentId);
                messageParts.add(imagePart);
            }
            log.info("构造多模态message: 1段文本 + {}张图片", attachmentIds.size());
            return messageParts;
        } catch (Exception e) {
            log.warn("构造多模态message失败，回退到纯文本: {}", e.getMessage());
            return prompt;
        }
    }

    private String getCurrentUserAstrbotApiKey() {
        UserSettings settings = getCurrentUserSettings();
        if (settings != null && settings.getAstrbotApiKey() != null) {
            return settings.getAstrbotApiKey();
        }
        return null;
    }

    private String getCurrentUserLlmModel() {
        UserSettings settings = getCurrentUserSettings();
        if (settings != null && settings.getLlmModel() != null && !settings.getLlmModel().isEmpty()) {
            return settings.getLlmModel();
        }
        return null;
    }

    /**
     * 根据 groupId 解析群类型（取第一条匹配记录的 groupType）
     */
    private String resolveGroupType(String groupId) {
        if (groupId == null || groupId.isBlank()) return null;
        try {
            List<com.qqai.entity.Group> groups = groupRepository.safeFindByGroupId(groupId);
            if (!groups.isEmpty()) {
                String gt = groups.get(0).getGroupType();
                return (gt != null && !gt.isBlank()) ? gt : null;
            }
        } catch (Exception e) {
            log.debug("解析群类型失败 groupId={}: {}", groupId, e.getMessage());
        }
        return null;
    }

    /**
     * 根据 groupId 解析群信息（群名、群类型标签），用于 prompt 变量注入
     */
    private Map<String, Object> resolveGroupInfo(String groupId) {
        Map<String, Object> info = new HashMap<>();
        if (groupId == null || groupId.isBlank()) return info;
        try {
            List<com.qqai.entity.Group> groups = groupRepository.safeFindByGroupId(groupId);
            if (!groups.isEmpty()) {
                com.qqai.entity.Group g = groups.get(0);
                info.put("groupName", g.getGroupName());
                com.qqai.constant.GroupType gt = com.qqai.constant.GroupType.fromString(g.getGroupType());
                info.put("groupTypeLabel", gt.getLabel());
            }
        } catch (Exception e) {
            log.debug("解析群信息失败 groupId={}: {}", groupId, e.getMessage());
        }
        return info;
    }

    private UserSettings getCurrentUserSettings() {
        try {
            Long userId = securityHelper.getCurrentUserId();
            if (userId != null) {
                return userSettingsService.findByUserId(userId.toString()).orElse(null);
            }
        } catch (Exception e) {
            log.warn("获取用户设置失败: {}", e.getMessage());
        }
        return null;
    }

    /**
     * 接收 AstrBot 的消息推送
     * 这是 AstrBot 主动推送到 SpringBoot 的消息
     */
    @PostMapping("/callback")
    public ResponseEntity<?> receiveFromAstrBot(@RequestBody String payload) {
        log.info("【AstrBot回调】收到消息: {}", payload);

        try {
            JsonNode json = objectMapper.readTree(payload);

            // 解析 AstrBot 消息格式
            String messageType = json.has("message_type") ? json.get("message_type").asText() : null;
            String content = json.has("message") ? json.get("message").asText() : null;
            String senderId = json.has("sender_id") ? json.get("sender_id").asText() : null;
            String senderName = json.has("sender_name") ? json.get("sender_name").asText() : null;
            String groupId = json.has("group_id") ? json.get("group_id").asText() : null;
            String sessionId = json.has("session_id") ? json.get("session_id").asText() : null;
            
            // 如果是群消息或私聊消息，存入数据库
            if (groupId != null || sessionId != null) {
                // 通过插件格式化 AstrBot 推送的内容
                Map<String, Object> callbackContext = new HashMap<>();
                callbackContext.put("source", "callback");
                callbackContext.put("groupId", groupId);
                callbackContext.put("sessionId", sessionId);
                callbackContext.put("senderId", senderId);
                content = pluginManager.applyPlugins(content, callbackContext);

                Message message = new Message();
                message.setGroupId(groupId != null ? groupId : sessionId);
                message.setUserQq(senderId);
                message.setUserNickname(senderName != null ? senderName : "AstrBot");
                message.setContent(content);
                message.setMessageType(Message.MessageType.TEXT);
                message.setSendTime(LocalDateTime.now());
                message.setSelfQq("astrbot");
                message.setSelfMessage(false);
                
                Message saved = messageService.saveMessage(message);
                log.info("AstrBot消息已保存, ID: {}", saved.getId());
            }
            
            Map<String, Object> okResult = new HashMap<>();
            okResult.put("status", "ok");
            return ResponseEntity.ok(okResult);
        } catch (Exception e) {
            log.error("处理AstrBot消息失败: {}", e.getMessage(), e);
            Map<String, Object> errorResult = new HashMap<>();
            errorResult.put("status", "error");
            errorResult.put("message", e.getMessage());
            return ResponseEntity.ok(errorResult);
        }
    }

    /**
     * 让 AstrBot 分析群聊消息
     * 直接调用 AstrBot API 获取分析结果
     */
    @PostMapping("/analyze")
    public ResponseEntity<?> analyzeGroupMessages(@RequestBody Map<String, Object> request) {
        String groupId = (String) request.get("groupId");
        Integer messageCount = (Integer) request.getOrDefault("messageCount", 50);
        String analysisType = (String) request.getOrDefault("type", "summary");

        if (groupId == null || groupId.trim().isEmpty()) {
            Map<String, Object> errorResult = new HashMap<>();
            errorResult.put("error", "群号不能为空");
            return ResponseEntity.badRequest().body(errorResult);
        }

        try {
            // 1. 从数据库获取最近的消息
            // 首先尝试用 groupId 查询，如果为空，再尝试用 group_name 查询
            String resolvedGroupId = groupId;
            List<Message> messages = messageService.getMessagesByGroupId(groupId);
            
            // 如果 groupId 查询为空，尝试用 group_name 查询
            if (messages.isEmpty() && !groupId.matches("\\d+")) {
                // 查询群号
                String sql = "SELECT group_id FROM chat_groups WHERE group_name = ? AND active = 1 LIMIT 1";
                log.debug("尝试用群名查询群号: {}", groupId);
                try {
                    jakarta.persistence.Query query = entityManager.createNativeQuery(sql);
                    query.setParameter(1, groupId);
                    Object result = query.getSingleResult();
                    if (result != null) {
                        String actualGroupId = result.toString();
                        log.debug("找到群号: {}", actualGroupId);
                        resolvedGroupId = actualGroupId;
                        messages = messageService.getMessagesByGroupId(actualGroupId);
                    }
                } catch (Exception e) {
                    log.warn("群名查询失败: {}", e.getMessage());
                }
            }

            // 权限:仅允许分析自己绑定 QQ 拥有的群(管理员豁免)
            if (!securityHelper.isAdmin() && !securityHelper.hasGroupAccess(resolvedGroupId)) {
                return ResponseEntity.status(403)
                        .body(Map.of("status", "error", "message", "无权分析该群聊"));
            }
            
            // 2. 构造富媒体消息内容 + 分析提示词
            RichMessageRenderer.RenderedBatch rendered = buildRenderedBatch(messages);
            String messageContents = rendered.getFullText();
            // FR-6 预检：完全无有效内容才直接返回
            if (!rendered.isAllHasValidContent() && (messageContents == null || messageContents.trim().isEmpty())) {
                ObjectNode emptyResp = objectMapper.createObjectNode();
                emptyResp.put("status", "error");
                emptyResp.put("message", "❌ 未检测到有效的聊天内容，无法生成总结。");
                return ResponseEntity.ok()
                        .header("Content-Type", "application/json; charset=UTF-8")
                        .body(objectMapper.writeValueAsString(emptyResp));
            }
            // 方案 B：追加图片 URL 文本注入（降级，后续 Task6 可升级为多模态 contexts）
            if (rendered.getAllImageUrls() != null && !rendered.getAllImageUrls().isEmpty()) {
                StringBuilder appendix = new StringBuilder("\n\n附图（供视觉模型参考）：\n");
                int idx = 1;
                for (String u : rendered.getAllImageUrls()) {
                    appendix.append("附图 ").append(idx++).append(": ").append(u).append("\n");
                }
                messageContents = messageContents + appendix;
            }
            if (rendered.isWasTruncated()) {
                messageContents = messageContents + "\n⚠️ 消息内容超长已截断。\n";
            }

            // 用户附加输入（和转发内容一起发给 LLM）
            String userPrompt = (String) request.get("userPrompt");
            if (userPrompt != null && !userPrompt.isBlank()) {
                messageContents = messageContents + "\n\n【用户补充说明】\n" + userPrompt.trim() + "\n";
            }

            // 3. 构造分析提示词（从 prompts.yml 渲染，按群类型 + analysisType 定制）
            String groupType = resolveGroupType(groupId);
            Map<String, Object> analysisVars = new HashMap<>();
            analysisVars.put("groupId", groupId);
            analysisVars.put("analysisType", analysisType != null ? analysisType : "default");
            analysisVars.put("messageContents", messageContents);
            String prompt = promptTemplateService.render("analysis.group", groupType, analysisVars);

            // 旧模板 analysis.group 可能没有 {messageContents} 变量，手动追加到 prompt 末尾
            prompt = prompt + "\n\n以下是最近群聊消息：\n" + messageContents;

            // 4. 直接调用 AstrBot API 获取分析结果
            String url = astrBotApiUrl + "/api/v1/chat";

            String userApiKey = getCurrentUserAstrbotApiKey();
            String token = (userApiKey != null && !userApiKey.isEmpty()) ? userApiKey : astrBotToken;

            HttpHeaders headers = new HttpHeaders();
            headers.set("X-API-Key", token);
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.set("Accept", "application/json");

            Map<String, Object> body = new HashMap<>();
            // 多模态：如果有图片，上传到AstrBot获取attachment_id，构造列表格式message
            body.put("message", buildMultimodalMessage(prompt, rendered.getAllImageUrls(), token));
            body.put("username", "analyzer");
            body.put("enable_streaming", false);

            // 模型选择：优先用户默认模型，允许请求体 model 字段覆盖
            String defaultModel = getCurrentUserLlmModel();
            if (defaultModel != null) {
                body.put("model", defaultModel);
            }
            String overrideModel = (String) request.get("model");
            if (overrideModel != null && !overrideModel.isBlank() && !"default".equalsIgnoreCase(overrideModel)) {
                body.put("model", overrideModel);
            }

            // 判断是否为视觉模型（从模型ID推断，为 Task 6 预留；如果 body.get("model") 含 vision key 可升级）
            // （当前仅方案 B 注入图片 URL 文本，不强制依赖视觉能力，留一个日志即可）
            log.debug("本次分析最终模型={}, imageUrls count={}", body.get("model"), rendered != null ? rendered.getAllImageUrls().size() : 0);

            HttpEntity<Map<String, Object>> entity = new HttpEntity<>(body, headers);
            
            // 配置消息转换器，使用 UTF-8 编码
            org.springframework.http.client.SimpleClientHttpRequestFactory factory = 
                new org.springframework.http.client.SimpleClientHttpRequestFactory();
            factory.setConnectTimeout(60000);
            factory.setReadTimeout(120000);
            
            java.util.List<org.springframework.http.converter.HttpMessageConverter<?>> converters = 
                new java.util.ArrayList<>();
            org.springframework.http.converter.StringHttpMessageConverter stringConverter = 
                new org.springframework.http.converter.StringHttpMessageConverter(java.nio.charset.StandardCharsets.UTF_8);
            stringConverter.setWriteAcceptCharset(false);
            converters.add(stringConverter);
            converters.add(new org.springframework.http.converter.json.MappingJackson2HttpMessageConverter());
            
            RestTemplate restTemplateWithTimeout = new RestTemplate(factory);
            restTemplateWithTimeout.setMessageConverters(converters);
            
            ResponseEntity<String> response = restTemplateWithTimeout.postForEntity(url, entity, String.class);
            
            // 解析响应
            String responseBody = response.getBody();
            StringBuilder replyText = new StringBuilder();

            if (responseBody != null) {
                String[] lines = responseBody.split("\n");
                for (String line : lines) {
                    line = line.trim();
                    if (line.startsWith("data: ")) {
                        String jsonData = line.substring(6);
                        try {
                            JsonNode json = objectMapper.readTree(jsonData);
                            String type = json.has("type") ? json.get("type").asText() : null;
                            if ("plain".equals(type)) {
                                String data = json.has("data") ? json.get("data").asText() : null;
                                if (data != null) {
                                    replyText.append(data);
                                }
                            }
                        } catch (Exception e) {
                            // 忽略解析错误
                        }
                    }
                }
            }
            
            String analysis = replyText.toString().trim();
            if (analysis.isEmpty()) {
                analysis = "抱歉，无法分析群聊消息。";
            }
            
            // 过滤掉工具调用的 JSON 内容
            // 使用递归正则移除嵌套的 JSON 对象（包含 id 和 name 字段的）
            String jsonPattern = "\\s*\\{[^{}]*(?:\"id\"\\s*:\\s*\"[^\"]+\"|\"name\"\\s*:\\s*\"[^\"]+\")[^{}]*(?:\\{[^{}]*\\}[^{}]*)*\\}\\s*";
            analysis = analysis.replaceAll(jsonPattern, " ");
            // 再次清理可能残留的简单 JSON
            analysis = analysis.replaceAll("\\s*\\{[^{}]*\"id\"[^{}]*\\}\\s*", " ");
            // 移除多余的水平空格，但保留 Markdown 段落结构
            analysis = analysis.replaceAll("[ \\t]+", " ").trim();

            // 通过插件链格式化分析结果
            Map<String, Object> analyzeContext = new HashMap<>();
            analyzeContext.put("source", "analyze");
            analyzeContext.put("groupId", groupId);
            analyzeContext.put("analysisType", analysisType);
            analysis = pluginManager.applyPlugins(analysis, analyzeContext);
            
            log.info("AstrBot 分析结果: {}", analysis);

            // 积分扣费（AI_ANALYZE）
            // 统一走 CreditService.spendForAnalyze：内部处理管理员免费、消息条数计费、
            // 余额不足抛 BizException（携带 need/balance 详情），事务+行锁防透支。
            Long userIdForAnalyze = securityHelper.getCurrentUserId();
            Integer analyzeCostDeducted = null;
            Integer analyzeBalanceAfter = null;
            if (userIdForAnalyze != null) {
                int analyzedMessageCount = messages != null ? messages.size() : 0;
                CreditService.CreditCostResult analyzeResult = creditService.spendForAnalyze(
                        userIdForAnalyze, analyzedMessageCount, analysisType, groupId, securityHelper.isAdmin());
                analyzeCostDeducted = analyzeResult.getCost();
                analyzeBalanceAfter = analyzeResult.getBalanceAfter();
            }

            // 返回分析结果
            ObjectNode result = objectMapper.createObjectNode();
            result.put("status", "ok");
            result.put("analysis", analysis);
            if (analyzeCostDeducted != null) {
                result.put("cost", analyzeCostDeducted);
            }
            if (analyzeBalanceAfter != null) {
                result.put("balance", analyzeBalanceAfter);
            }

            return ResponseEntity.ok()
                .header("Content-Type", "application/json; charset=UTF-8")
                .body(objectMapper.writeValueAsString(result));
            
        } catch (Exception e) {
            log.error("AstrBot 分析请求失败: {}", e.getMessage(), e);
            try {
                ObjectNode error = objectMapper.createObjectNode();
                error.put("status", "error");
                error.put("message", "AstrBot 服务暂时不可用，请稍后重试");
                return ResponseEntity.ok()
                    .header("Content-Type", "application/json; charset=UTF-8")
                    .body(objectMapper.writeValueAsString(error));
            } catch (Exception ex) {
                return ResponseEntity.ok()
                    .header("Content-Type", "application/json; charset=UTF-8")
                    .body("{\"status\":\"error\",\"message\":\"AstrBot 服务暂时不可用，请稍后重试\"}");
            }
        }
    }

    /**
     * 分析选中的消息（前端 ChatInterface 选中消息后调用）
     * 接收 messageIds，从数据库查消息内容，按 analysisType 渲染 prompts.yml 模板，调 AstrBot LLM
     *
     * Request: { groupId, messageIds: [], analysisType }
     * Response: { status, analysis, cost?, balance? }
     */
    @PostMapping("/analyze-selected")
    public ResponseEntity<?> analyzeSelected(@RequestBody Map<String, Object> request) {
        String groupId = (String) request.get("groupId");
        Object rawIds = request.get("messageIds");
        String analysisType = (String) request.getOrDefault("analysisType", "summary");

        // 1. 校验参数
        if (rawIds == null) {
            return ResponseEntity.badRequest().body(Map.of("status", "error", "message", "messageIds 不能为空"));
        }
        @SuppressWarnings("unchecked")
        List<Object> rawIdList = rawIds instanceof List ? (List<Object>) rawIds : List.of(rawIds);
        if (rawIdList.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("status", "error", "message", "至少选择一条消息"));
        }
        List<Long> messageIds = new ArrayList<>();
        for (Object o : rawIdList) {
            try {
                messageIds.add(Long.valueOf(String.valueOf(o)));
            } catch (Exception e) {
                return ResponseEntity.badRequest().body(Map.of("status", "error", "message", "messageIds 包含非法值: " + o));
            }
        }

        // 允许的 analysisType 白名单（对应 prompts.yml analysis.selected.* 的子键）
        java.util.Set<String> ALLOWED_TYPES = java.util.Set.of(
                "summary", "social-graph", "topic-trend",
                "integration-guide", "meme-dictionary", "persona-match", "default");
        if (!ALLOWED_TYPES.contains(analysisType)) {
            return ResponseEntity.badRequest().body(Map.of(
                    "status", "error",
                    "message", "不支持的 analysisType: " + analysisType + "，可选: " + ALLOWED_TYPES));
        }

        try {
            // 权限:分析指定群时需要该群访问权限(管理员豁免)
            if (groupId != null && !groupId.isBlank()
                    && !securityHelper.isAdmin() && !securityHelper.hasGroupAccess(groupId)) {
                return ResponseEntity.status(403).body(Map.of(
                        "status", "error", "message", "无权分析该群聊"));
            }

            // 2. 按 ID 查消息（逐个查询，保证兼容）
            List<Message> messages = new ArrayList<>();
            List<String> userQqBindings = securityHelper.getCurrentUserQqBindings();
            boolean isAdminUser = securityHelper.isAdmin();
            for (Long mid : messageIds) {
                messageService.getMessageById(mid).ifPresent(m -> {
                    // 归属校验:消息必须属于当前用户绑定的 QQ(或管理员),且与 groupId 一致
                    if (groupId != null && !groupId.isBlank() && !groupId.equals(m.getGroupId())) {
                        return;
                    }
                    if (!isAdminUser && (m.getSelfQq() == null || !userQqBindings.contains(m.getSelfQq()))) {
                        return;
                    }
                    messages.add(m);
                });
            }
            if (messages.isEmpty()) {
                return ResponseEntity.ok(Map.of("status", "error", "message", "未找到匹配的消息"));
            }

            // 3. 富媒体渲染（替换旧的 nick+content 拼接）
            RichMessageRenderer.RenderedBatch rendered = buildRenderedBatch(messages);
            String messageContents = rendered.getFullText();
            // FR-6 预检：完全无有效内容才直接返回
            if (!rendered.isAllHasValidContent() && (messageContents == null || messageContents.trim().isEmpty())) {
                ObjectNode emptyResp = objectMapper.createObjectNode();
                emptyResp.put("status", "error");
                emptyResp.put("message", "❌ 未检测到有效的聊天内容，无法生成总结。");
                return ResponseEntity.ok()
                        .header("Content-Type", "application/json; charset=UTF-8")
                        .body(objectMapper.writeValueAsString(emptyResp));
            }
            // 方案 B：追加图片 URL 文本注入（降级，后续 Task6 可升级为多模态 contexts）
            if (rendered.getAllImageUrls() != null && !rendered.getAllImageUrls().isEmpty()) {
                StringBuilder appendix = new StringBuilder("\n\n附图（供视觉模型参考）：\n");
                int idx = 1;
                for (String u : rendered.getAllImageUrls()) {
                    appendix.append("附图 ").append(idx++).append(": ").append(u).append("\n");
                }
                messageContents = messageContents + appendix;
            }
            if (rendered.isWasTruncated()) {
                messageContents = messageContents + "\n⚠️ 消息内容超长已截断。\n";
            }

            // 用户附加输入（和转发内容一起发给 LLM）
            String userPrompt = (String) request.get("userPrompt");
            if (userPrompt != null && !userPrompt.isBlank()) {
                messageContents = messageContents + "\n\n【用户补充说明】\n" + userPrompt.trim() + "\n";
            }

            // 4. 解析群类型 + 群信息注入模板变量
            String groupType = resolveGroupType(groupId);
            Map<String, Object> groupInfo = resolveGroupInfo(groupId);

            Map<String, Object> templateVars = new HashMap<>();
            templateVars.put("messageContents", messageContents);
            if (groupId != null) templateVars.put("groupId", groupId);
            templateVars.put("analysisType", analysisType);
            if (groupInfo.get("groupName") != null) templateVars.put("groupName", groupInfo.get("groupName"));
            if (groupInfo.get("groupTypeLabel") != null)
                templateVars.put("groupTypeLabel", groupInfo.get("groupTypeLabel"));

            String prompt = promptTemplateService.render("analysis.selected", groupType, templateVars);

            // 5. 调 AstrBot LLM（与 analyzeGroupMessages 相同的 RestTemplate 配置）
            String url = astrBotApiUrl + "/api/v1/chat";
            String userApiKey = getCurrentUserAstrbotApiKey();
            String token = (userApiKey != null && !userApiKey.isEmpty()) ? userApiKey : astrBotToken;

            HttpHeaders headers = new HttpHeaders();
            headers.set("X-API-Key", token);
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.set("Accept", "application/json");

            Map<String, Object> body = new HashMap<>();
            // 多模态：如果有图片，上传到AstrBot获取attachment_id，构造列表格式message
            body.put("message", buildMultimodalMessage(prompt, rendered.getAllImageUrls(), token));
            body.put("username", "analyzer");
            body.put("enable_streaming", false);

            String selectedModel = getCurrentUserLlmModel();
            if (selectedModel != null) {
                body.put("model", selectedModel);
            }
            // 前端可通过请求体 model 覆盖本次分析所用模型
            String overrideModel = (String) request.get("model");
            if (overrideModel != null && !overrideModel.isBlank() && !"default".equalsIgnoreCase(overrideModel)) {
                body.put("model", overrideModel);
            }

            // 判断是否为视觉模型（从模型ID推断，为 Task 6 预留；如果 body.get("model") 含 vision key 可升级）
            // （当前仅方案 B 注入图片 URL 文本，不强制依赖视觉能力，留一个日志即可）
            log.debug("本次分析最终模型={}, imageUrls count={}", body.get("model"), rendered != null ? rendered.getAllImageUrls().size() : 0);

            HttpEntity<Map<String, Object>> entity = new HttpEntity<>(body, headers);

            org.springframework.http.client.SimpleClientHttpRequestFactory factory =
                    new org.springframework.http.client.SimpleClientHttpRequestFactory();
            factory.setConnectTimeout(60000);
            factory.setReadTimeout(120000);

            java.util.List<org.springframework.http.converter.HttpMessageConverter<?>> converters =
                    new java.util.ArrayList<>();
            org.springframework.http.converter.StringHttpMessageConverter stringConverter =
                    new org.springframework.http.converter.StringHttpMessageConverter(java.nio.charset.StandardCharsets.UTF_8);
            stringConverter.setWriteAcceptCharset(false);
            converters.add(stringConverter);
            converters.add(new org.springframework.http.converter.json.MappingJackson2HttpMessageConverter());

            RestTemplate restTemplateWithTimeout = new RestTemplate(factory);
            restTemplateWithTimeout.setMessageConverters(converters);

            ResponseEntity<String> response = restTemplateWithTimeout.postForEntity(url, entity, String.class);

            // 6. 解析 SSE 响应（与 sendMessage 保持一致的解析逻辑）
            String responseBody = response.getBody();
            StringBuilder replyText = new StringBuilder();
            if (responseBody != null) {
                log.debug("analyze-selected AstrBot 原始响应: {}", responseBody.substring(0, Math.min(500, responseBody.length())));
                String[] lines = responseBody.split("\n");
                for (String line : lines) {
                    line = line.trim();
                    if (line.startsWith("data: ")) {
                        String jsonData = line.substring(6);
                        try {
                            JsonNode json = objectMapper.readTree(jsonData);
                            String type = json.has("type") ? json.get("type").asText() : null;
                            if ("plain".equals(type)) {
                                String data = json.has("data") ? json.get("data").asText() : null;
                                if (data != null) {
                                    // 过滤工具调用 JSON（与 sendMessage 保持一致）
                                    boolean isToolResult = (data.contains("\"id\"") && data.contains("\"ts\"") && data.contains("\"result\""))
                                        || (data.contains("\"id\"") && data.contains("\"name\"") && data.contains("\"parameters\""))
                                        || (data.startsWith("{") && data.contains("\"static\"") && data.contains("\"content\""));
                                    if (!isToolResult) {
                                        replyText.append(data);
                                    }
                                }
                            }
                        } catch (Exception ignored) {
                        }
                    }
                }
            }

            String analysis = replyText.toString().trim();
            if (analysis.isEmpty()) analysis = "抱歉，无法分析所选消息。";

            // 额外过滤工具调用 JSON
            String jsonPattern = "\\s*\\{[^{}]*(?:\"id\"\\s*:\\s*\"[^\"]+\"|\"name\"\\s*:\\s*\"[^\"]+\")[^{}]*(?:\\{[^{}]*\\}[^{}]*)*\\}\\s*";
            analysis = analysis.replaceAll(jsonPattern, " ");
            analysis = analysis.replaceAll("\\s*\\{[^{}]*\"id\"[^{}]*\\}\\s*", " ");
            analysis = analysis.replaceAll("[ \\t]+", " ").trim();

            // 插件链格式化
            Map<String, Object> ctx = new HashMap<>();
            ctx.put("source", "analyze");
            ctx.put("groupId", groupId);
            ctx.put("analysisType", analysisType);
            analysis = pluginManager.applyPlugins(analysis, ctx);

            log.info("analyze-selected 完成 groupId={} analysisType={} analysisLen={}", groupId, analysisType, analysis.length());

            // 7. 积分扣费（AI_ANALYZE）
            // 统一走 CreditService.spendForAnalyze：内部处理管理员免费、消息条数计费、
            // 余额不足抛 BizException（携带 need/balance 详情），事务+行锁防透支。
            Long userId = securityHelper.getCurrentUserId();
            Integer costDeducted = null;
            Integer balanceAfter = null;
            if (userId != null) {
                int analyzedMessageCount = messageIds != null ? messageIds.size() : 0;
                CreditService.CreditCostResult analyzeResult = creditService.spendForAnalyze(
                        userId, analyzedMessageCount, analysisType,
                        groupId != null ? groupId : "", securityHelper.isAdmin());
                costDeducted = analyzeResult.getCost();
                balanceAfter = analyzeResult.getBalanceAfter();
            }

            // 8. 返回结果（同时按 groupType 推荐分析类型，便于前端展示默认推荐）
            ObjectNode result = objectMapper.createObjectNode();
            result.put("status", "ok");
            result.put("analysis", analysis);
            result.put("analysisType", analysisType);
            try {
                com.qqai.constant.GroupType gt = com.qqai.constant.GroupType.fromString(groupType);
                ArrayNode recommended = objectMapper.valueToTree(gt.getRecommendedAnalysisTypes());
                result.set("recommendedAnalysisTypes", recommended);
                result.put("groupType", gt.name());
                result.put("groupTypeLabel", gt.getLabel());
            } catch (Exception ignored) {
                result.put("groupType", "OTHER");
                result.put("groupTypeLabel", "其他");
                result.set("recommendedAnalysisTypes", objectMapper.valueToTree(java.util.List.of("summary", "integration-guide")));
            }
            if (groupInfo.get("groupName") != null) result.put("groupName", String.valueOf(groupInfo.get("groupName")));
            if (costDeducted != null) result.put("cost", costDeducted);
            if (balanceAfter != null) result.put("balance", balanceAfter);

            // 9. 保存消息到会话（如果前端传了 conversationId）
            String conversationId = (String) request.get("conversationId");
            if (conversationId != null && !conversationId.isBlank()) {
                try {
                    // 保存用户分析请求
                    String userMessage = String.format("[%s] 分析 %d 条消息",
                            analysisTypeLabel(analysisType), messageIds.size());
                    Map<String, Object> userExtra = new HashMap<>();
                    userExtra.put("groupId", groupId);
                    userExtra.put("messageIds", messageIds);
                    userExtra.put("analysisType", analysisType);
                    conversationService.addUserMessage(conversationId, userMessage, userExtra);

                    // 保存 AI 分析结果
                    conversationService.addAssistantMessage(conversationId, analysis, null, null, null, null);

                    log.info("analyze-selected 消息已保存 conversationId={}", conversationId);
                } catch (Exception saveEx) {
                    log.warn("保存分析消息失败: {}", saveEx.getMessage());
                }
            }

            return ResponseEntity.ok()
                    .header("Content-Type", "application/json; charset=UTF-8")
                    .body(objectMapper.writeValueAsString(result));

        } catch (Exception e) {
            log.error("analyze-selected 失败 groupId={} analysisType={}: {}", groupId, analysisType, e.getMessage());
            try {
                ObjectNode error = objectMapper.createObjectNode();
                error.put("status", "error");
                if (e instanceof BizException) {
                    BizException be = (BizException) e;
                    error.put("message", be.getMessage() != null ? be.getMessage() : "分析失败");
                    if (be.getErrorCode() != null) error.put("errorCode", be.getErrorCode());
                    if (be.getDetails() != null) error.set("details", objectMapper.valueToTree(be.getDetails()));
                } else {
                    error.put("message", "AstrBot 服务暂时不可用，请稍后重试");
                }
                return ResponseEntity.ok()
                        .header("Content-Type", "application/json; charset=UTF-8")
                        .body(objectMapper.writeValueAsString(error));
            } catch (Exception ex) {
                return ResponseEntity.ok()
                        .header("Content-Type", "application/json; charset=UTF-8")
                        .body("{\"status\":\"error\",\"message\":\"AstrBot 服务暂时不可用，请稍后重试\"}");
            }
        }
    }

    /**
     * 获取 AstrBot 状态
     * 通过检查服务是否可访问来判断
     */
    @GetMapping("/status")
    public ResponseEntity<?> getAstrBotStatus() {
        Map<String, Object> result = new HashMap<>();
        try {
            // 尝试访问 AstrBot 的根路径或健康检查端点
            String url = astrBotApiUrl;
            ResponseEntity<String> response = restTemplate.getForEntity(url, String.class);
            result.put("status", "online");
            result.put("message", "AstrBot 运行中");
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            result.put("status", "offline");
            result.put("message", e.getMessage());
            return ResponseEntity.ok(result);
        }
    }

    /**
     * 发送消息给 AstrBot 并获取回复（带对话存储）
     * 支持 conversationId 参数来维持对话上下文
     */
    @PostMapping("/send")
    public ResponseEntity<?> sendMessage(@RequestBody Map<String, Object> request) {
        String message = (String) request.get("message");
        String groupId = (String) request.get("groupId");
        String userQq = (String) request.get("userQq");
        String userNickname = (String) request.get("userNickname");
        String conversationId = (String) request.get("conversationId");
        String model = (String) request.getOrDefault("model", "default");

        if (message == null || message.trim().isEmpty()) {
            Map<String, Object> errorResult = new HashMap<>();
            errorResult.put("error", "消息不能为空");
            return ResponseEntity.badRequest().body(errorResult);
        }

        String currentConversationId = null;
        try {
            Long userId = securityHelper.getCurrentUserId();

            // 权限:带群上下文聊天时需要该群访问权限(管理员豁免)
            if (groupId != null && !groupId.isBlank()
                    && !securityHelper.isAdmin() && !securityHelper.hasGroupAccess(groupId)) {
                return ResponseEntity.status(403).body(Map.of(
                        "status", "error", "message", "无权在该群聊使用 AI 对话"));
            }

            AstrBotConversation conversation = conversationService.getOrCreateConversation(
                    conversationId, userId, groupId, userQq, userNickname, model);
            currentConversationId = conversation.getConversationId();

            // 2. 先构建对话上下文（最近10条历史消息，不包含当前这条）
            // 避免当前消息在 body.message 和 context 中重复出现，导致模型混乱
            List<Map<String, String>> context = conversationService.buildConversationContext(currentConversationId, 10);
            log.debug("sendMessage 上下文构建完成 context size={}（未来扩展可在此注入 RichMessageRenderer）", context.size());

            // 3. 保存用户消息到数据库
            conversationService.addUserMessage(currentConversationId, message, null);

            // 3.5 先预估本次费用（基于当前已有的上下文消息数，传入 context.size()），
            // 用于前端显示预估消耗；实际扣费放在成功拿到 AstrBot 回复之后，失败时不扣费。
            Integer estimateCost = null;
            Integer balanceBefore = null;
            if (userId != null) {
                // 仅预估，不扣费
                CreditService.CreditCostResult estimated = creditService.estimateChatCost(
                        userId, model, null, null, 0, context.size(), securityHelper.isAdmin());
                estimateCost = estimated.getCost();
                balanceBefore = estimated.getBalanceAfter();
            }

            // 4. 调用 AstrBot HTTP API
            String url = astrBotApiUrl + "/api/v1/chat";
            
            String userApiKey = getCurrentUserAstrbotApiKey();
            String token = (userApiKey != null && !userApiKey.isEmpty()) ? userApiKey : astrBotToken;
            
            HttpHeaders headers = new HttpHeaders();
            headers.set("X-API-Key", token);
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.set("Accept", "application/json");
            
            Map<String, Object> body = new HashMap<>();
            // ---- 富媒体渲染一致性说明 ----
            // 主聊天/分析链路/异步摘要三条链路的消息文本渲染统一使用 RichMessageRenderer：
            // - 分析链路：见 analyzeSelected()/analyzeGroupMessages() buildRenderedBatch() 调用
            // - 异步摘要链路：见 AiAnalysisConsumer + AstrBotService.doSummarize()
            // - 主聊天：此处用户输入以纯文本为主（前端暂不支持图片上传），如果未来传入 IM Message 对象，也必须调用 RichMessageRenderer.renderSingle() 渲染
            body.put("message", message);
            body.put("username", userQq != null ? userQq : "web_user");
            body.put("enable_streaming", false);
            // 优先级：前端本次请求显式传入 model > 用户默认模型
            String activeModel = (model != null && !model.isBlank() && !"default".equalsIgnoreCase(model))
                    ? model
                    : getCurrentUserLlmModel();
            if (activeModel != null && !activeModel.isBlank()) {
                body.put("model", activeModel);
                log.info("使用模型: {} (fromRequest={})", activeModel, (model != null && !model.isBlank() && !"default".equalsIgnoreCase(model)));
            }
            if (groupId != null) {
                body.put("session_id", groupId);
            }
            // 在上下文最前面追加系统提示（从 prompts.yml 渲染，按群类型定制）
            String groupType = resolveGroupType(groupId);
            Map<String, Object> systemVars = new HashMap<>();
            if (groupId != null) systemVars.put("groupId", groupId);
            Map<String, Object> groupInfo = resolveGroupInfo(groupId);
            if (groupInfo.get("groupName") != null) systemVars.put("groupName", groupInfo.get("groupName"));
            if (groupType != null) systemVars.put("groupTypeLabel", groupInfo.get("groupTypeLabel"));
            String systemContent = promptTemplateService.render("chat.system", groupType, systemVars);

            Map<String, String> systemMessage = new HashMap<>();
            systemMessage.put("role", "system");
            systemMessage.put("content", systemContent);
            List<Map<String, String>> finalContext = new ArrayList<>();
            finalContext.add(systemMessage);
            finalContext.addAll(context);
            // 如果有上下文，传递给 AstrBot
            if (!finalContext.isEmpty()) {
                body.put("context", finalContext);
            }
            
            HttpEntity<Map<String, Object>> entity = new HttpEntity<>(body, headers);
            
            // 使用 SimpleClientHttpRequestFactory 设置超时
            org.springframework.http.client.SimpleClientHttpRequestFactory factory = 
                new org.springframework.http.client.SimpleClientHttpRequestFactory();
            factory.setConnectTimeout(30000);
            factory.setReadTimeout(60000);
            
            // 配置消息转换器，使用 UTF-8 编码
            java.util.List<org.springframework.http.converter.HttpMessageConverter<?>> converters = 
                new java.util.ArrayList<>();
            org.springframework.http.converter.StringHttpMessageConverter stringConverter = 
                new org.springframework.http.converter.StringHttpMessageConverter(java.nio.charset.StandardCharsets.UTF_8);
            stringConverter.setWriteAcceptCharset(false);
            converters.add(stringConverter);
            converters.add(new org.springframework.http.converter.json.MappingJackson2HttpMessageConverter());
            
            RestTemplate restTemplateWithTimeout = new RestTemplate(factory);
            restTemplateWithTimeout.setMessageConverters(converters);
            
            ResponseEntity<String> response = restTemplateWithTimeout.postForEntity(url, entity, String.class);
            
            // 5. 解析响应
            String responseBody = response.getBody();
            StringBuilder replyText = new StringBuilder();
            Integer promptTokens = null;
            Integer completionTokens = null;
            Integer totalTokens = null;

            if (responseBody != null) {
                log.debug("AstrBot 原始响应: {}", responseBody.substring(0, Math.min(500, responseBody.length())));

                // 解析 SSE 格式的数据行
                String[] lines = responseBody.split("\n");
                for (String line : lines) {
                    line = line.trim();
                    if (line.startsWith("data: ")) {
                        String jsonData = line.substring(6);
                        try {
                            JsonNode json = objectMapper.readTree(jsonData);
                            String type = json.has("type") ? json.get("type").asText() : null;
                            if ("plain".equals(type)) {
                                String data = json.has("data") ? json.get("data").asText() : null;
                                if (data != null) {
                                    // 过滤掉工具调用的 JSON 内容
                                    // 检查是否是工具调用结果（包含 id + ts + result 或 id + name 等特征）
                                    boolean isToolResult = (data.contains("\"id\"") && data.contains("\"ts\"") && data.contains("\"result\""))
                                        || (data.contains("\"id\"") && data.contains("\"name\"") && data.contains("\"parameters\""))
                                        || (data.startsWith("{") && data.contains("\"static\"") && data.contains("\"content\""));
                                    if (!isToolResult) {
                                        replyText.append(data);
                                    }
                                }
                            }
                            // 尝试解析 token 使用量
                            if (json.has("usage")) {
                                JsonNode usage = json.get("usage");
                                if (usage != null) {
                                    promptTokens = usage.has("prompt_tokens") ? usage.get("prompt_tokens").asInt() : null;
                                    completionTokens = usage.has("completion_tokens") ? usage.get("completion_tokens").asInt() : null;
                                    totalTokens = usage.has("total_tokens") ? usage.get("total_tokens").asInt() : null;
                                }
                            }
                        } catch (Exception e) {
                            // 忽略解析错误
                        }
                    }
                }
            }

            String finalReply = replyText.toString().trim();
            if (finalReply.isEmpty()) {
                finalReply = "抱歉，我没有理解您的问题。";
            }

            // 通过插件链格式化 AI 回复
            Map<String, Object> replyContext = new HashMap<>();
            replyContext.put("source", "send");
            replyContext.put("conversationId", currentConversationId);
            replyContext.put("groupId", groupId);
            replyContext.put("userId", userQq);
            finalReply = pluginManager.applyPlugins(finalReply, replyContext);

            log.debug("AstrBot 最终回复: {}", finalReply);

            // 6. 保存 AI 回复到数据库
            conversationService.addAssistantMessage(
                    currentConversationId, finalReply, model,
                    totalTokens, promptTokens, completionTokens);

            // 7. 积分扣费（AI_CHAT）
            // 统一走 CreditService.spendForChat（带上下文消息数）：内部处理管理员免费、token 计费、
            // 月度配额、月卡折扣、阶梯折扣、每日封顶、余额不足抛 BizException，事务+行锁防透支。
            Integer costDeducted = null;
            Integer balanceAfter = null;

            if (userId != null) {
                CreditService.CreditCostResult chatResult = creditService.spendForChat(
                        userId, model, promptTokens, completionTokens, 0, context.size(),
                        currentConversationId, securityHelper.isAdmin());
                costDeducted = chatResult.getCost();
                balanceAfter = chatResult.getBalanceAfter();
            }

            // 8. 如果是新对话，自动生成标题
            if (conversation.getMessageCount() <= 2 && conversation.getTitle().equals("新对话")) {
                conversationService.autoGenerateTitle(currentConversationId);
            }

            // 9. 构建 JSON 响应
            ObjectNode result = objectMapper.createObjectNode();
            result.put("status", "ok");
            result.put("data", finalReply);
            result.put("conversationId", currentConversationId);
            result.put("messageCount", conversation.getMessageCount() + 2); // +2 因为刚保存了两条消息
            if (estimateCost != null) {
                result.put("estimateCost", estimateCost);
            }
            if (costDeducted != null) {
                result.put("cost", costDeducted);
            }
            if (balanceAfter != null) {
                result.put("balance", balanceAfter);
            }

            // 直接返回 ObjectNode，让 Spring 自动转换为 JSON
            return ResponseEntity.ok(result);

        } catch (Exception e) {
            log.error("发送消息到 AstrBot 失败: {}", e.getMessage(), e);
            
            String errorMsg = e.getMessage();
            String fallbackMessage = "AstrBot 服务暂时不可用，请稍后重试";
            
            if (currentConversationId != null) {
                try {
                    conversationService.addAssistantMessage(
                            currentConversationId, fallbackMessage, model,
                            null, null, null);
                    log.info("AstrBot错误消息已保存到对话: {}", currentConversationId);
                } catch (Exception saveEx) {
                    log.warn("保存错误消息失败: {}", saveEx.getMessage());
                }
            }
            
            ObjectNode error = objectMapper.createObjectNode();
            error.put("status", "error");
            error.put("message", fallbackMessage);
            return ResponseEntity.ok()
                .header("Content-Type", "application/json; charset=UTF-8")
                .body(error);
        }
    }

    // ==================== 对话管理 API ====================

    /**
     * 获取对话列表（按当前登录用户隔离）
     * 支持按 groupId 或 userQq 筛选，但始终限定在当前用户范围内
     */
    @GetMapping("/conversations")
    public ResponseEntity<?> getConversations(
            @RequestParam(required = false) String groupId,
            @RequestParam(required = false) String userQq,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        try {
            Long currentUserId = securityHelper.getCurrentUserId();
            if (currentUserId == null) {
                ObjectNode error = objectMapper.createObjectNode();
                error.put("status", "error");
                error.put("message", "未登录");
                return ResponseEntity.status(401).body(error);
            }

            List<AstrBotConversation> conversations;
            if (groupId != null && !groupId.isEmpty()) {
                // 按群筛选：需要验证当前用户是否有权访问该群
                if (!securityHelper.hasGroupAccess(groupId)) {
                    ObjectNode error = objectMapper.createObjectNode();
                    error.put("status", "error");
                    error.put("message", "无权访问该群");
                    return ResponseEntity.status(403).body(error);
                }
                conversations = conversationService.getGroupConversations(groupId);
            } else if (userQq != null && !userQq.isEmpty()) {
                // 按 QQ 号筛选：只筛选当前用户绑定的 QQ 号
                List<String> userQqBindings = securityHelper.getCurrentUserQqBindings();
                if (!userQqBindings.contains(userQq)) {
                    ObjectNode error = objectMapper.createObjectNode();
                    error.put("status", "error");
                    error.put("message", "无权访问该 QQ 号的对话");
                    return ResponseEntity.status(403).body(error);
                }
                conversations = conversationService.getUserConversations(userQq);
            } else {
                // 默认返回当前用户的所有未归档对话
                conversations = conversationService.getUserActiveConversations(currentUserId);
            }

            ObjectNode result = objectMapper.createObjectNode();
            result.put("status", "ok");
            result.putPOJO("data", conversations);
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            ObjectNode error = objectMapper.createObjectNode();
            error.put("status", "error");
            error.put("message", e.getMessage());
            return ResponseEntity.ok(error);
        }
    }

    /**
     * 获取单个对话详情（验证权限）
     */
    @GetMapping("/conversations/{conversationId}")
    public ResponseEntity<?> getConversation(@PathVariable String conversationId) {
        try {
            Long currentUserId = securityHelper.getCurrentUserId();
            if (currentUserId == null) {
                return ResponseEntity.status(401).body(Map.of("status", "error", "message", "未登录"));
            }

            Optional<AstrBotConversation> conversation = conversationService.getConversation(conversationId);
            if (conversation.isPresent()) {
                AstrBotConversation conv = conversation.get();
                // 验证当前用户是否有权访问该会话
                if (conv.getUserId() != null && !conv.getUserId().equals(currentUserId)) {
                    return ResponseEntity.status(403).body(Map.of("status", "error", "message", "无权访问该对话"));
                }
                // 如果会话关联了群，验证群访问权限
                if (conv.getGroupId() != null && !conv.getGroupId().isEmpty()) {
                    if (!securityHelper.hasGroupAccess(conv.getGroupId())) {
                        return ResponseEntity.status(403).body(Map.of("status", "error", "message", "无权访问该对话"));
                    }
                }
                ObjectNode result = objectMapper.createObjectNode();
                result.put("status", "ok");
                result.putPOJO("data", conv);
                return ResponseEntity.ok(result);
            } else {
                Map<String, Object> errorResult = new HashMap<>();
                errorResult.put("error", "对话不存在");
                return ResponseEntity.status(404).body(errorResult);
            }
        } catch (Exception e) {
            ObjectNode error = objectMapper.createObjectNode();
            error.put("status", "error");
            error.put("message", e.getMessage());
            return ResponseEntity.ok(error);
        }
    }

    /**
     * 获取对话的消息列表（验证权限）
     */
    @GetMapping("/conversations/{conversationId}/messages")
    public ResponseEntity<?> getConversationMessages(
            @PathVariable String conversationId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {
        try {
            Long currentUserId = securityHelper.getCurrentUserId();
            if (currentUserId == null) {
                return ResponseEntity.status(401).body(Map.of("status", "error", "message", "未登录"));
            }

            // 先验证会话权限
            Optional<AstrBotConversation> conversation = conversationService.getConversation(conversationId);
            if (conversation.isEmpty()) {
                return ResponseEntity.status(404).body(Map.of("status", "error", "message", "对话不存在"));
            }
            AstrBotConversation conv = conversation.get();
            if (conv.getUserId() != null && !conv.getUserId().equals(currentUserId)) {
                return ResponseEntity.status(403).body(Map.of("status", "error", "message", "无权访问该对话"));
            }

            List<AstrBotMessage> messages = conversationService.getConversationMessages(conversationId);
            ObjectNode result = objectMapper.createObjectNode();
            result.put("status", "ok");
            result.putPOJO("data", messages);
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            ObjectNode error = objectMapper.createObjectNode();
            error.put("status", "error");
            error.put("message", e.getMessage());
            return ResponseEntity.ok(error);
        }
    }

    /**
     * 创建新对话
     */
    @PostMapping("/conversations")
    public ResponseEntity<?> createConversation(@RequestBody Map<String, Object> request) {
        try {
            Long userId = securityHelper.getCurrentUserId();
            String groupId = (String) request.get("groupId");
            String userQq = (String) request.get("userQq");
            String userNickname = (String) request.get("userNickname");
            String title = (String) request.get("title");
            String model = (String) request.get("model");

            AstrBotConversation conversation = conversationService.createConversation(
                    userId, groupId, userQq, userNickname, title, model);

            ObjectNode result = objectMapper.createObjectNode();
            result.put("status", "ok");
            result.putPOJO("data", conversation);
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            ObjectNode error = objectMapper.createObjectNode();
            error.put("status", "error");
            error.put("message", e.getMessage());
            return ResponseEntity.ok(error);
        }
    }

    /**
     * 更新对话标题（验证权限）
     */
    @PutMapping("/conversations/{conversationId}/title")
    public ResponseEntity<?> updateConversationTitle(
            @PathVariable String conversationId,
            @RequestBody Map<String, String> request) {
        try {
            Long currentUserId = securityHelper.getCurrentUserId();
            if (currentUserId == null) {
                return ResponseEntity.status(401).body(Map.of("status", "error", "message", "未登录"));
            }
            // 验证权限
            Optional<AstrBotConversation> convOpt = conversationService.getConversation(conversationId);
            if (convOpt.isEmpty()) {
                return ResponseEntity.status(404).body(Map.of("status", "error", "message", "对话不存在"));
            }
            if (convOpt.get().getUserId() != null && !convOpt.get().getUserId().equals(currentUserId)) {
                return ResponseEntity.status(403).body(Map.of("status", "error", "message", "无权操作该对话"));
            }

            String title = request.get("title");
            conversationService.updateConversationTitle(conversationId, title);
            Map<String, Object> okResult = new HashMap<>();
            okResult.put("status", "ok");
            return ResponseEntity.ok(okResult);
        } catch (Exception e) {
            ObjectNode error = objectMapper.createObjectNode();
            error.put("status", "error");
            error.put("message", e.getMessage());
            return ResponseEntity.ok(error);
        }
    }

    /**
     * 归档对话（验证权限）
     */
    @PostMapping("/conversations/{conversationId}/archive")
    public ResponseEntity<?> archiveConversation(@PathVariable String conversationId) {
        try {
            Long currentUserId = securityHelper.getCurrentUserId();
            if (currentUserId == null) {
                return ResponseEntity.status(401).body(Map.of("status", "error", "message", "未登录"));
            }
            Optional<AstrBotConversation> convOpt = conversationService.getConversation(conversationId);
            if (convOpt.isEmpty()) {
                return ResponseEntity.status(404).body(Map.of("status", "error", "message", "对话不存在"));
            }
            if (convOpt.get().getUserId() != null && !convOpt.get().getUserId().equals(currentUserId)) {
                return ResponseEntity.status(403).body(Map.of("status", "error", "message", "无权操作该对话"));
            }

            conversationService.archiveConversation(conversationId);
            Map<String, Object> okResult = new HashMap<>();
            okResult.put("status", "ok");
            return ResponseEntity.ok(okResult);
        } catch (Exception e) {
            ObjectNode error = objectMapper.createObjectNode();
            error.put("status", "error");
            error.put("message", e.getMessage());
            return ResponseEntity.ok(error);
        }
    }

    /**
     * 删除对话（验证权限）
     */
    @DeleteMapping("/conversations/{conversationId}")
    public ResponseEntity<?> deleteConversation(@PathVariable String conversationId) {
        try {
            Long currentUserId = securityHelper.getCurrentUserId();
            if (currentUserId == null) {
                return ResponseEntity.status(401).body(Map.of("status", "error", "message", "未登录"));
            }
            Optional<AstrBotConversation> convOpt = conversationService.getConversation(conversationId);
            if (convOpt.isEmpty()) {
                return ResponseEntity.status(404).body(Map.of("status", "error", "message", "对话不存在"));
            }
            if (convOpt.get().getUserId() != null && !convOpt.get().getUserId().equals(currentUserId)) {
                return ResponseEntity.status(403).body(Map.of("status", "error", "message", "无权操作该对话"));
            }

            conversationService.deleteConversation(conversationId);
            Map<String, Object> okResult = new HashMap<>();
            okResult.put("status", "ok");
            return ResponseEntity.ok(okResult);
        } catch (Exception e) {
            ObjectNode error = objectMapper.createObjectNode();
            error.put("status", "error");
            error.put("message", e.getMessage());
            return ResponseEntity.ok(error);
        }
    }

    /**
     * 获取对话统计信息（验证权限）
     */
    @GetMapping("/conversations/{conversationId}/stats")
    public ResponseEntity<?> getConversationStats(@PathVariable String conversationId) {
        try {
            Long currentUserId = securityHelper.getCurrentUserId();
            if (currentUserId == null) {
                return ResponseEntity.status(401).body(Map.of("status", "error", "message", "未登录"));
            }
            Optional<AstrBotConversation> convOpt = conversationService.getConversation(conversationId);
            if (convOpt.isEmpty()) {
                return ResponseEntity.status(404).body(Map.of("status", "error", "message", "对话不存在"));
            }
            if (convOpt.get().getUserId() != null && !convOpt.get().getUserId().equals(currentUserId)) {
                return ResponseEntity.status(403).body(Map.of("status", "error", "message", "无权访问该对话"));
            }

            Map<String, Object> stats = conversationService.getConversationStats(conversationId);
            ObjectNode result = objectMapper.createObjectNode();
            result.put("status", "ok");
            result.putPOJO("data", stats);
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            ObjectNode error = objectMapper.createObjectNode();
            error.put("status", "error");
            error.put("message", e.getMessage());
            return ResponseEntity.ok(error);
        }
    }

    /**
     * 获取 AstrBot 可用模型列表
     * 优先读取本地 cmd_config.json 解析，失败时 fallback 到 AstrBot /api/v1/models 接口
     */
    @GetMapping("/models")
    public ResponseEntity<?> getModels() {
        ArrayNode modelsArray = objectMapper.createArrayNode();

        // ========== 策略1：优先从本地 cmd_config.json 读取 ==========
        if (astrBotDataPath != null && !astrBotDataPath.isBlank()) {
            try {
                java.io.File configFile = new java.io.File(astrBotDataPath, "cmd_config.json");
                if (configFile.exists() && configFile.canRead()) {
                    JsonNode root = objectMapper.readTree(configFile);
                    JsonNode providers = root.has("provider") ? root.get("provider") : null;
                    if (providers != null && providers.isArray()) {
                        for (JsonNode p : providers) {
                            ObjectNode modelInfo = objectMapper.createObjectNode();
                            String id = p.has("id") ? p.get("id").asText() : "";
                            String modelName = p.has("model") ? p.get("model").asText() : id;
                            boolean enabled = p.has("enable") && p.get("enable").asBoolean();
                            String providerSource = p.has("provider_source_id") ? p.get("provider_source_id").asText() : "";

                            // 生成友好的显示名（去掉 provider 前缀）
                            String label = modelName;
                            if (modelName.contains("/")) {
                                String[] parts = modelName.split("/");
                                label = parts[parts.length - 1];
                            }
                            // 去掉 Pro/ 前缀（如果有）
                            if (label.startsWith("Pro/")) {
                                label = label.substring(4);
                            }

                            modelInfo.put("id", id.isEmpty() ? modelName : id);
                            modelInfo.put("name", label);
                            modelInfo.put("fullName", modelName);
                            modelInfo.put("enabled", enabled);
                            modelInfo.put("provider", providerSource);

                            // 解析 modalities 作为标签
                            if (p.has("modalities")) {
                                JsonNode mods = p.get("modalities");
                                ArrayNode tags = objectMapper.createArrayNode();
                                if (mods.isArray()) {
                                    for (JsonNode m : mods) {
                                        tags.add(m.asText());
                                    }
                                }
                                modelInfo.set("modalities", tags);
                            }
                            if (p.has("max_context_tokens")) {
                                modelInfo.put("maxContextTokens", p.get("max_context_tokens").asLong(0));
                            }
                            modelsArray.add(modelInfo);
                        }
                    }
                }
            } catch (Exception e) {
                log.warn("从 cmd_config.json 读取模型失败，fallback 到 AstrBot API: {}", e.getMessage());
                modelsArray = objectMapper.createArrayNode(); // 清空，走 fallback
            }
        }

        // ========== 策略2：如果本地读取为空，fallback 到 AstrBot API ==========
        if (modelsArray.size() == 0) {
            try {
                String userApiKey = getCurrentUserAstrbotApiKey();
                String token = (userApiKey != null && !userApiKey.isEmpty()) ? userApiKey : astrBotToken;

                HttpHeaders headers = new HttpHeaders();
                headers.set("X-API-Key", token);
                headers.setAccept(Collections.singletonList(MediaType.APPLICATION_JSON));

                HttpEntity<Void> entity = new HttpEntity<>(headers);
                ResponseEntity<String> response = restTemplate.exchange(
                        astrBotApiUrl + "/api/v1/models", HttpMethod.GET, entity, String.class);

                if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                    JsonNode data = objectMapper.readTree(response.getBody());
                    JsonNode models = data.has("data") ? data.get("data") : data;

                    if (models.isArray()) {
                        for (JsonNode model : models) {
                            ObjectNode modelInfo = objectMapper.createObjectNode();
                            String id = model.has("id") ? model.get("id").asText() : "";
                            String name = model.has("name") ? model.get("name").asText() : id;
                            modelInfo.put("id", id.isEmpty() ? name : id);
                            modelInfo.put("name", name);
                            modelInfo.put("enabled", model.has("enabled") && model.get("enabled").asBoolean());
                            modelInfo.put("provider", model.has("provider") ? model.get("provider").asText() : "");
                            if (model.has("description")) {
                                modelInfo.put("description", model.get("description").asText());
                            }
                            modelsArray.add(modelInfo);
                        }
                    }
                }
            } catch (Exception e) {
                log.error("AstrBot API 模型列表也获取失败: {}", e.getMessage());
            }
        }

        // ========== 响应：无论成功与否都返回当前结果，前端根据 count 判断是否有错误 ==========
        if (modelsArray.size() > 0) {
            return ResponseEntity.ok(Map.of(
                    "status", "ok",
                    "models", modelsArray,
                    "count", modelsArray.size(),
                    "source", (astrBotDataPath != null && !astrBotDataPath.isBlank()) ? "cmd_config.json" : "api"
            ));
        }

        return ResponseEntity.ok(Map.of(
                "status", "error",
                "message", "未找到可用模型，请在 AstrBot 中配置后重试",
                "models", modelsArray,
                "count", 0
        ));
    }

    /**
     * 设置当前用户的默认模型
     */
    @PostMapping("/set-model")
    public ResponseEntity<?> setModel(@RequestBody Map<String, Object> request) {
        String model = (String) request.get("model");
        if (model == null || model.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("status", "error", "message", "模型名不能为空"));
        }

        try {
            UserSettings settings = getCurrentUserSettings();
            if (settings == null) {
                return ResponseEntity.status(404).body(Map.of("status", "error", "message", "用户设置不存在"));
            }

            settings.setLlmModel(model);
            userSettingsService.save(settings);

            return ResponseEntity.ok(Map.of("status", "ok", "model", model));
        } catch (Exception e) {
            log.error("设置模型失败: {}", e.getMessage());
            return ResponseEntity.ok(Map.of("status", "error", "message", "设置模型失败"));
        }
    }

    /**
     * 分析类型标签映射
     */
    private String analysisTypeLabel(String analysisType) {
        return switch (analysisType) {
            case "summary" -> "群聊速览";
            case "social-graph" -> "社交图谱";
            case "topic-trend" -> "话题趋势";
            case "integration-guide" -> "融入指南";
            case "meme-dictionary" -> "梗词典";
            case "persona-match" -> "人设匹配";
            default -> "AI分析";
        };
    }
}

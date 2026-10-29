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
import com.qqai.service.AstrBotModelSelector;
import com.qqai.service.AstrBotSessionMemoryService;
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
import org.springframework.web.multipart.MultipartFile;
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

    /** 模型 id → AstrBot 4.28 的 selected_provider / selected_model（见类注释） */
    @Autowired
    private com.qqai.service.AstrBotModelSelector astrBotModelSelector;

    /** 会话记忆键（session_id）与删对话时的级联清理 */
    @Autowired
    private AstrBotSessionMemoryService astrBotSessionMemoryService;

    /** 对话能力契约：请求组装/模型下发/人格/会话/SSE 解析由适配器负责 */
    @Autowired
    private com.qqai.capability.chat.ChatProvider chatProvider;

    /** 多模态图片上传（主聊天与分析链路共用） */
    @Autowired
    private com.qqai.adapter.chat.AstrBotMediaSupport mediaSupport;

    @Autowired
    private FileRecordRepository fileRecordRepository;

    @Value("${astrbot.api-url:http://localhost:6185}")
    private String astrBotApiUrl;

    @Value("${astrbot.token:}")
    private String astrBotToken;

    @Value("${astrbot.data-path:}")
    private String astrBotDataPath;

    /**
     * 带图提问时使用的 AstrBot「配置文件(profile)」名。
     *
     * <p>为什么需要它：4.28 的 Agent 以「配置里的主模型」为准，请求体里的 model 不覆盖它；
     * 而主模型（DeepSeek）在配置里被标了 image 模态，AstrBot 于是认为它能看图、不触发视觉回退，
     * 最终把图片降级成文本路径（模型回答"看不到图"）。实测：把带图请求指向「主模型为 GLM-4.5V」
     * 的 profile（本机名为 vision）后，图片可被正确识别。</p>
     */
    @Value("${astrbot.vision-config-name:vision}")
    private String visionConfigName;

    /**
     * 分析链路（/analyze、/analyze-selected）用的视觉配置文件：同模型，但 AstrBot 人格
     * {@code tools=[]}。人格不挂工具时 Agent 不会去调 send_message_to_user / future_task，
     * 也就不会把"请稍等片刻"当成分析结果返回。/send-with-image（AI 对话）仍用 vision，
     * 保留用户自己的人格设定。
     */
    @Value("${astrbot.vision-task-config-name:vision-task}")
    private String visionTaskConfigName;

    /** 双层提示词的人层：用户选定的人格 → 配置档案 */
    @Autowired
    private com.qqai.service.AstrBotPersonaService astrBotPersonaService;

    @Value("${server.port:8081}")
    private int serverPort;

    @Value("${server.address:localhost}")
    private String serverAddress;

    /** 调试开关：true 时回调接口会打印完整载荷（含聊天正文），仅排查链路时临时打开 */
    @Value("${app.debug.log-media-payload:false}")
    private boolean logMediaPayload;

    /** AstrBot 大模型调用统一走带超时的共享客户端（原先 new RestTemplate() 无超时，下游卡死会占满 Tomcat 线程） */
    @Autowired
    private RestTemplate restTemplate;

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
        return mediaSupport.resolveImageFileFromUrl(imageUrl);
    }

    /**
     * 上传图片到 AstrBot /api/v1/file 获取 attachment_id（多模态视觉识别所需）。
     * 限制最多 5 张，单张超过 10MB 跳过。
     * @return attachment_id 列表；全部失败返回空列表
     */
    private List<String> uploadImagesToAstrBot(List<String> imageUrls, String token) {
        return mediaSupport.uploadImagesToAstrBot(imageUrls, token);
    }

    /**
     * 构造多模态 message：如果图片上传成功则用列表格式（plain + image），
     * 否则回退为纯文本 prompt（方案B 文本注入URL已在调用方完成）。
     */
    private Object buildMultimodalMessage(String prompt, List<String> imageUrls, String token) {
        return mediaSupport.buildMultimodalMessage(prompt, imageUrls, token);
    }

    /**
     * 带图时补充提示词：分析模板里的「富媒体识别规则」要求模型描述图片时保守
     * （"不编造具体图片内容即可"）。当图片真的作为多模态消息段随附时，这条规则会
     * 让模型继续输出"内容未知"，把视觉能力浪费掉。这里明确告知可以看图。
     */
    private String augmentPromptForImages(String prompt, List<String> imageUrls) {
        return mediaSupport.augmentPromptForImages(prompt, imageUrls);
    }

    /**
     * 双层提示词的「人层」落地：按当前用户选定的人格挑配置档案。
     *
     * <p>规则（优先级从上到下）：</p>
     * <ol>
     *   <li>用户选了人格 → 用该人格的档案（带图用 {@code -image} 视觉版，否则 {@code -text}）；</li>
     *   <li>没选人格但带图 → 用内置 {@code vision-task}（视觉模型 + 无工具人格）；</li>
     *   <li>都没有 → 不改档案，走 AstrBot 默认。</li>
     * </ol>
     *
     * <p>只要指定了档案就带一个全新 {@code session_id}：AstrBot 会话记录里的 persona
     * 优先级高于档案，复用旧会话会让新人格不生效。</p>
     */
    private void applyPersonaLayer(Map<String, Object> body, Map<String, Object> chatFlags,
                                   List<String> imageUrls) {
        boolean hasImage = imageUrls != null && !imageUrls.isEmpty();
        String personaCfg = astrBotPersonaService.resolveConfigName(securityHelper.getCurrentUserId(), hasImage);
        String cfg = personaCfg;
        if (cfg == null && hasImage) {
            cfg = (visionTaskConfigName != null && !visionTaskConfigName.isBlank())
                    ? visionTaskConfigName.trim() : visionConfigName;
        }
        // 分析永远是一次性会话：即使没有配置档案也要显式给 session_id，
        // 否则 AstrBot 会自造随机会话，调用方无法清理（AstrBot 对话列表会越积越多）。
        body.put("session_id", "qqai-" + java.util.UUID.randomUUID());
        if (cfg != null && !cfg.isBlank()) {
            body.put("config_name", cfg);
            // 新会话 → 不会沿用旧会话记住的人格（挂着全部工具的人格会让模型跑去调工具）
        }
        if (hasImage && chatFlags != null) {
            chatFlags.put("enable_default_system_prompt", false);
        }
        log.info("本次请求：带图 {} 张，人层档案={}（用户选择={}）", imageUrls == null ? 0 : imageUrls.size(), cfg, personaCfg);
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
        // 脱敏：不再打印完整载荷（含聊天正文）。与 RootWebhookController 同口径，只记长度；
        // 需要排查时用 app.debug.log-media-payload=true 临时打开全文。
        log.info("【AstrBot回调】收到消息, payload长度={}", payload != null ? payload.length() : 0);
        if (logMediaPayload && payload != null) {
            log.info("【AstrBot回调】完整载荷(调试开关已开): {}",
                    payload.length() > 4000 ? payload.substring(0, 4000) : payload);
        }

        try {
            JsonNode json = objectMapper.readTree(payload);

            // 解析 AstrBot 消息格式
            String messageType = json.has("message_type") ? json.get("message_type").asText() : null;
            String content = json.has("message") ? json.get("message").asText() : null;
            String senderId = json.has("sender_id") ? json.get("sender_id").asText() : null;
            String senderName = json.has("sender_name") ? json.get("sender_name").asText() : null;
            String groupId = json.has("group_id") ? json.get("group_id").asText() : null;
            String sessionId = json.has("session_id") ? json.get("session_id").asText() : null;

            // 归属校验：回调会把内容写进消息库，群消息必须落在调用者自己绑定 QQ 拥有的群（管理员豁免），
            // 否则任何登录用户都能往别人的群里塞假消息。口径与 /analyze 一致。
            if (groupId != null && !groupId.isBlank()
                    && !securityHelper.isAdmin() && !securityHelper.hasGroupAccess(groupId)) {
                log.warn("【安全】拒绝 AstrBot 回调写入无权访问的群: groupId={}", groupId);
                return ResponseEntity.status(403)
                        .body(Map.of("status", "error", "message", "无权写入该群聊"));
            }

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
    /**
     * AI 分析取样条数：默认 {@value #DEFAULT_ANALYZE_MESSAGES}、上限 {@value #MAX_ANALYZE_MESSAGES}。
     * 分析只用末尾样本，整群历史全量加载既无用又会 OOM。
     */
    private static final int DEFAULT_ANALYZE_MESSAGES = 50;
    private static final int MAX_ANALYZE_MESSAGES = 200;

    /** /analyze-selected 单次可携带的消息 ID 上限（防滥用；正常界面勾选远低于此值） */
    private static final int MAX_ANALYZE_SELECTED_MESSAGES = 2000;

    /** 解析 messageCount：兼容数字/数字字符串，非法或越界一律收敛到 [1, MAX_ANALYZE_MESSAGES]（包级可见便于单测） */
    static int resolveAnalyzeMessageCount(Object raw) {
        int value = DEFAULT_ANALYZE_MESSAGES;
        if (raw instanceof Number number) {
            value = number.intValue();
        } else if (raw instanceof String str && !str.isBlank()) {
            try {
                value = Integer.parseInt(str.trim());
            } catch (NumberFormatException ignored) {
                // 保留默认值
            }
        }
        return Math.min(Math.max(value, 1), MAX_ANALYZE_MESSAGES);
    }

    @PostMapping("/analyze")
    public ResponseEntity<?> analyzeGroupMessages(@RequestBody Map<String, Object> request) {
        String groupId = (String) request.get("groupId");
        // messageCount 此前被声明却未使用，实际按「整群历史」加载 → 大群 OOM。
        // 现在真正生效并收敛到 [1, MAX_ANALYZE_MESSAGES]，分析本来就只吃末尾若干条样本。
        int messageCount = resolveAnalyzeMessageCount(request.get("messageCount"));
        String analysisType = request.get("type") instanceof String typeStr ? typeStr : "summary";

        if (groupId == null || groupId.trim().isEmpty()) {
            Map<String, Object> errorResult = new HashMap<>();
            errorResult.put("error", "群号不能为空");
            return ResponseEntity.badRequest().body(errorResult);
        }

        try {
            // 1. 从数据库获取最近的消息（有界：只取最近 messageCount 条有效消息）
            // 首先尝试用 groupId 查询，如果为空，再尝试用 group_name 查询
            String resolvedGroupId = groupId;
            List<Message> messages = messageService.getRecentActiveMessages(groupId, messageCount);

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
                        messages = messageService.getRecentActiveMessages(actualGroupId, messageCount);
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
            // 图片已改为多模态消息段随请求送出（buildMultimodalMessage），
            // 这里只留一条计数说明：把 http://… 的图片 URL 塞进提示词既占上下文，
            // 模型也无法访问（旧"方案 B"文本注入的遗留做法）。
            if (rendered.getAllImageUrls() != null && !rendered.getAllImageUrls().isEmpty()) {
                messageContents = messageContents + "\n\n（本次聊天记录含 "
                        + rendered.getAllImageUrls().size() + " 张图片，已作为图片消息段一并送出，可直接查看画面。）\n";
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
            // 有图时补充「可以直接看图」的说明（否则模板里的保守规则会让模型答"内容未知"）
            prompt = augmentPromptForImages(prompt, rendered.getAllImageUrls());

            // 4. 直接调用 AstrBot API 获取分析结果
            String url = astrBotApiUrl + "/api/v1/chat";

            String userApiKey = getCurrentUserAstrbotApiKey();
            String token = (userApiKey != null && !userApiKey.isEmpty()) ? userApiKey : astrBotToken;

            HttpHeaders headers = new HttpHeaders();
            headers.set("X-API-Key", token);
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.set("Accept", "application/json");

            // 模型/人格/会话/SSE 统一走 ChatProvider；分析会话标题由适配器写入 AstrBot
            String analysis = chatProvider.chat(
                    new com.qqai.capability.chat.ChatRequest(
                            securityHelper.getCurrentUserId(), null, prompt,
                            rendered.getAllImageUrls(), (String) request.get("model"),
                            null, null, "analyzer",
                            analysisConversationTitle(analysisType, resolvedGroupId),
                            com.qqai.capability.chat.ChatPurpose.ANALYSIS)).text();

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
        // 单次分析选中的消息数上限：防止一次请求携带巨量 ID 把库与应用内存打满
        // （正常界面按范围勾选，远低于该值）
        if (messageIds.size() > MAX_ANALYZE_SELECTED_MESSAGES) {
            return ResponseEntity.badRequest().body(Map.of(
                    "status", "error",
                    "message", "一次最多分析 " + MAX_ANALYZE_SELECTED_MESSAGES + " 条消息，当前 " + messageIds.size() + " 条"));
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

            // 2. 按 ID 批量查消息（原先逐个 getMessageById → N+1 查询；选中 100 条就是 100 次往返）
            List<Message> messages = new ArrayList<>();
            List<String> userQqBindings = securityHelper.getCurrentUserQqBindings();
            boolean isAdminUser = securityHelper.isAdmin();
            Map<Long, Message> fetched = messageService.getMessagesByIds(messageIds).stream()
                    .filter(m -> m.getId() != null)
                    .collect(java.util.stream.Collectors.toMap(Message::getId, m -> m, (a, b) -> a));
            // 按请求顺序遍历，保持原有的输出顺序与逐条归属校验语义
            for (Long mid : messageIds) {
                Message m = fetched.get(mid);
                if (m == null) {
                    continue;
                }
                if (groupId != null && !groupId.isBlank() && !groupId.equals(m.getGroupId())) {
                    continue;
                }
                if (!isAdminUser && (m.getSelfQq() == null || !userQqBindings.contains(m.getSelfQq()))) {
                    continue;
                }
                messages.add(m);
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
            // 图片已改为多模态消息段随请求送出（buildMultimodalMessage），
            // 这里只留一条计数说明：把 http://… 的图片 URL 塞进提示词既占上下文，
            // 模型也无法访问（旧"方案 B"文本注入的遗留做法）。
            if (rendered.getAllImageUrls() != null && !rendered.getAllImageUrls().isEmpty()) {
                messageContents = messageContents + "\n\n（本次聊天记录含 "
                        + rendered.getAllImageUrls().size() + " 张图片，已作为图片消息段一并送出，可直接查看画面。）\n";
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
            // 有图时补充「可以直接看图」的说明（同 /analyze）
            prompt = augmentPromptForImages(prompt, rendered.getAllImageUrls());

            // 5. 调 AstrBot LLM（与 analyzeGroupMessages 相同的 RestTemplate 配置）
            String url = astrBotApiUrl + "/api/v1/chat";
            String userApiKey = getCurrentUserAstrbotApiKey();
            String token = (userApiKey != null && !userApiKey.isEmpty()) ? userApiKey : astrBotToken;

            // 模型/人格/一次性会话/SSE 统一走 ChatProvider
            String analysis = chatProvider.chat(
                    new com.qqai.capability.chat.ChatRequest(
                            securityHelper.getCurrentUserId(), null, prompt,
                            rendered.getAllImageUrls(), (String) request.get("model"),
                            null, null, "analyzer",
                            analysisConversationTitle(analysisType, groupId),
                            com.qqai.capability.chat.ChatPurpose.ANALYSIS)).text();

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
        } catch (Exception e) {
            result.put("status", "offline");
            result.put("message", e.getMessage());
        }
        // 「人层」状态：顶栏只需要当前人格名，用轻量视图（就绪状态由 /astrbot/personas 提供）
        try {
            result.put("persona", astrBotPersonaService.currentSelectionLight(securityHelper.getCurrentUserId()));
        } catch (Exception e) {
            log.debug("读取人格状态失败: {}", e.getMessage());
        }
        return ResponseEntity.ok(result);
    }

    /**
     * 把所有人格副本同步到最新人设与契约（改完 `doc/personas/*.md` 后用）。
     *
     * <p>只对有差异的副本写库，并在需要时重启一次 AstrBot；已选人格的配置档案一并刷新。</p>
     */
    @PostMapping("/personas/sync")
    public ResponseEntity<?> syncPersonas() {
        try {
            return ResponseEntity.ok(Map.of("status", "ok", "data", astrBotPersonaService.syncAllClones()));
        } catch (com.qqai.exception.BizException e) {
            return ResponseEntity.status(e.getCode())
                    .body(Map.of("status", "error", "message", e.getMessage()));
        }
    }

    // ==================== 双层提示词：人层（AstrBot 人格）====================

    /**
     * 可选人格列表 + 当前用户的选择（供 AstrBot 模块的「人格」下拉框使用）。
     *
     * <p>列表直接读 AstrBot 的 personas 表（不需要 persona scope 的 API Key）；
     * 写入时由 {@link com.qqai.service.AstrBotPersonaService} 生成「无工具副本 + 配置档案」。</p>
     */
    @GetMapping("/personas")
    public ResponseEntity<?> listPersonas() {
        try {
            Map<String, Object> data = new HashMap<>();
            data.put("personas", astrBotPersonaService.listPersonas());
            data.put("selection", astrBotPersonaService.selectionStatus(securityHelper.getCurrentUserId()));
            data.put("contract", astrBotPersonaService.currentContract());
            data.put("note", "人格由 AstrBot 提供（'人'），任务规则与输出格式由本后端提供（'岗位'）");
            return ResponseEntity.ok(Map.of("status", "ok", "data", data));
        } catch (com.qqai.exception.BizException e) {
            return ResponseEntity.status(e.getCode())
                    .body(Map.of("status", "error", "message", e.getMessage()));
        }
    }

    /**
     * 选择「人」：{@code {"personaId":"灰泽满"}}，传空字符串表示恢复默认（不指定人格）。
     *
     * <p>首次选中某个人格时需要给它生成无工具副本（AstrBot 的人格列表是启动时载入的），
     * 因此这一次会自动重启 AstrBot 并等它就绪；之后切换是秒切。</p>
     */
    @PostMapping("/persona")
    public ResponseEntity<?> selectPersona(@RequestBody Map<String, Object> request) {
        Object raw = request.get("personaId");
        String personaId = raw == null ? null : String.valueOf(raw);
        try {
            Map<String, Object> view = astrBotPersonaService.select(securityHelper.getCurrentUserId(), personaId);
            return ResponseEntity.ok(Map.of("status", "ok", "data", view));
        } catch (com.qqai.exception.BizException e) {
            return ResponseEntity.status(e.getCode())
                    .body(Map.of("status", "error", "message", e.getMessage()));
        }
    }

    /**
     * 发送消息给 AstrBot 并获取回复（带对话存储）
     * 支持 conversationId 参数来维持对话上下文
     */
    /**
     * 判断回复是否是"我看不到图片"这类视觉失败（4.28 的 Agent 偶尔先试工具调用且失败）。
     * 命中的话调用方会重试一次。
     */
    private boolean looksLikeVisionRejected(String reply) {
        if (reply == null || reply.isBlank()) return false;
        String[] patterns = {
                "无法查看图片", "无法直接查看", "看不到这张图", "看不到图", "看不到图片",
                "没有图像识别", "未启用图片识别", "无法读取图片", "图片识别能力"
        };
        for (String p : patterns) {
            if (reply.contains(p)) return true;
        }
        return false;
    }

    /**
     * 从 AstrBot 的 cmd_config.json 读出「支持图片」的模型 id 集合（modalities 含 image）。
     * 读不到时返回空集合（调用方据此决定是否回退到默认模型）。
     */
    private java.util.Set<String> loadVisionModelIds() {
        java.util.Set<String> ids = new java.util.HashSet<>();
        try {
            if (astrBotDataPath == null || astrBotDataPath.isBlank()) return ids;
            java.io.File configFile = new java.io.File(astrBotDataPath, "cmd_config.json");
            if (!configFile.exists() || !configFile.canRead()) return ids;
            JsonNode root = objectMapper.readTree(configFile);
            JsonNode providers = root.has("provider") ? root.get("provider") : null;
            if (providers == null || !providers.isArray()) return ids;
            for (JsonNode p : providers) {
                JsonNode mods = p.get("modalities");
                if (mods == null || !mods.isArray()) continue;
                boolean hasImage = false;
                for (JsonNode m : mods) {
                    if ("image".equalsIgnoreCase(m.asText())) { hasImage = true; break; }
                }
                if (hasImage && p.has("id")) ids.add(p.get("id").asText());
            }
        } catch (Exception e) {
            log.debug("读取视觉模型清单失败: {}", e.getMessage());
        }
        return ids;
    }

    /**
     * 带图提问：{@code POST /api/astrbot/send-with-image}（multipart）。
     *
     * <p>为什么不让前端先走 {@code /api/messages/upload}：那条链路依赖 MinIO（本机未启动时会
     * "Failed to connect to /127.0.0.1:9000"）。这里改为把图片落到 {@code uploads/chat-tmp/}，
     * 再用 {@code /uploads/...} 形式交给既有的多模态逻辑（{@link #buildMultimodalMessage}）上传给 AstrBot。</p>
     *
     * <p>处理方法：先把表单参数整理成与 {@code /send} 相同的 Map，然后直接复用 {@link #sendMessage}，
     * 因此计费、会话、模型选择、多模态等行为与纯文本对话完全一致。临时文件在调用结束后删除。</p>
     */
    @PostMapping(value = "/send-with-image", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<?> sendMessageWithImage(
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "message", required = false) String message,
            @RequestParam(value = "conversationId", required = false) String conversationId,
            @RequestParam(value = "groupId", required = false) String groupId,
            @RequestParam(value = "userQq", required = false) String userQq,
            @RequestParam(value = "userNickname", required = false) String userNickname,
            @RequestParam(value = "model", required = false, defaultValue = "default") String model) {

        if (file == null || file.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("status", "error", "message", "图片不能为空"));
        }
        String contentType = String.valueOf(file.getContentType() == null ? "" : file.getContentType());
        if (!contentType.startsWith("image/")) {
            return ResponseEntity.badRequest().body(Map.of("status", "error", "message", "只能发送图片文件"));
        }
        if (file.getSize() > 10L * 1024 * 1024) {
            return ResponseEntity.badRequest().body(Map.of("status", "error", "message", "图片过大，最大 10MB"));
        }

        File temp = null;
        try {
            // 必须用绝对路径：MultipartFile#transferTo 对相对路径会解析到 Tomcat 的 work 目录
            // （work/Tomcat/localhost/ROOT/...），导致 FileNotFoundException。
            java.nio.file.Path dir = java.nio.file.Paths.get("uploads", "chat-tmp").toAbsolutePath();
            java.nio.file.Files.createDirectories(dir);
            String original = String.valueOf(file.getOriginalFilename() == null ? "" : file.getOriginalFilename());
            String ext = "";
            int dot = original.lastIndexOf('.');
            if (dot >= 0 && original.length() - dot <= 6) ext = original.substring(dot).toLowerCase();
            java.nio.file.Path target = dir.resolve(java.util.UUID.randomUUID() + ext);
            try (java.io.InputStream in = file.getInputStream()) {
                java.nio.file.Files.copy(in, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
            temp = target.toFile();

            Map<String, Object> request = new HashMap<>();
            request.put("message", message == null ? "" : message);
            request.put("imageUrls", List.of("/uploads/chat-tmp/" + temp.getName()));
            // 带图请求默认走「视觉配置文件」（其主模型为视觉模型），可用 astrbot.vision-config-name 覆盖
            if (visionConfigName != null && !visionConfigName.isBlank()) {
                request.put("config_name", visionConfigName);
            }
            if (conversationId != null && !conversationId.isBlank()) request.put("conversationId", conversationId);
            if (groupId != null && !groupId.isBlank()) request.put("groupId", groupId);
            if (userQq != null && !userQq.isBlank()) request.put("userQq", userQq);
            if (userNickname != null && !userNickname.isBlank()) request.put("userNickname", userNickname);
            request.put("model", model);
            log.info("带图提问: ext={}, size={}B", ext, file.getSize());

            // 模型兜底：当前会话选的模型若不支持图片（AstrBot 配置的 modalities 无 image），
            // 自动改用「用户默认模型 → 配置里第一个支持图片的模型」。否则会出现调用 90s+
            // 后返回无法解析的响应（实测 DeepSeek-V2.5 带图即如此）。
            String requestedModel = (model == null || model.isBlank() || "default".equalsIgnoreCase(model)) ? null : model;
            String modelUsed = requestedModel;
            boolean switched = false;
            if (requestedModel != null) {
                java.util.Set<String> visionIds = loadVisionModelIds();
                if (!visionIds.isEmpty() && !visionIds.contains(requestedModel)) {
                    String fallback = getCurrentUserLlmModel();
                    if (fallback == null || fallback.isBlank() || !visionIds.contains(fallback)) {
                        fallback = visionIds.iterator().next();
                    }
                    modelUsed = fallback;
                    switched = true;
                    request.put("model", fallback);
                    log.info("带图提问模型兜底: {} 不支持图片 → 改用 {}", requestedModel, fallback);
                }
            }

            ResponseEntity<?> resp = sendMessage(request);

            // 4.28 的 Agent 偶尔会先尝试调用 send_message_to_user 工具（且失败），
            // 于是把"看不到图片"当成回答。识别到这种回复就自动重试一次，多数情况下第二次会直接看图作答。
            Object firstBody = resp.getBody();
            if (firstBody instanceof Map<?, ?> fm && looksLikeVisionRejected(String.valueOf(fm.get("data")))) {
                log.info("带图提问疑似视觉失败，自动重试一次");
                resp = sendMessage(request);
            }

            // 把「实际使用的模型 / 是否发生过兜底」附回响应，前端可据此提示用户
            Object bodyObj = resp.getBody();
            if (bodyObj instanceof Map<?, ?> rawMap) {
                @SuppressWarnings("unchecked")
                Map<String, Object> body = (Map<String, Object>) rawMap;
                body.put("modelUsed", modelUsed);
                body.put("modelSwitched", switched);
                if (switched) body.put("modelRequested", requestedModel);
            }
            return resp;
        } catch (Exception e) {
            log.warn("带图提问失败: {}", e.getMessage());
            return ResponseEntity.status(500).body(Map.of("status", "error", "message", "图片处理失败: " + e.getMessage()));
        } finally {
            // AstrBot 在调用过程中已把图片转成自己的 attachment，这里的临时文件可以安全删除
            if (temp != null && temp.exists() && !temp.delete()) {
                log.debug("临时图片删除失败: {}", temp.getAbsolutePath());
            }
        }
    }

    @PostMapping("/send")
    public ResponseEntity<?> sendMessage(@RequestBody Map<String, Object> request) {
        String message = (String) request.get("message");
        String groupId = (String) request.get("groupId");
        String userQq = (String) request.get("userQq");
        String userNickname = (String) request.get("userNickname");
        String conversationId = (String) request.get("conversationId");
        String model = (String) request.getOrDefault("model", "default");
        // 多模态：前端可传 imageUrls（本后端的相对路径或完整 URL），后端读本地文件上传 AstrBot 换 attachment_id
        List<String> requestImageUrls = new ArrayList<>();
        Object rawImages = request.get("imageUrls");
        if (rawImages instanceof List<?> list) {
            for (Object o : list) {
                if (o != null && !String.valueOf(o).isBlank()) requestImageUrls.add(String.valueOf(o).trim());
            }
        }
        boolean hasImage = !requestImageUrls.isEmpty();
        // 配置文件(profile)透传：4.28 用 config_name/config_id 指定该会话用哪套模型配置
        // （带图时 sendMessageWithImage 会自动填入视觉 profile，这里负责把它带进 AstrBot 请求体）
        String cfgName = (String) request.get("config_name");
        String cfgId = (String) request.get("config_id");

        if ((message == null || message.trim().isEmpty()) && !hasImage) {
            Map<String, Object> errorResult = new HashMap<>();
            errorResult.put("error", "消息不能为空");
            return ResponseEntity.badRequest().body(errorResult);
        }
        // 只发图不带文字时给一个默认提示，避免模型不知道要做什么
        if ((message == null || message.trim().isEmpty()) && hasImage) {
            message = "请看这张图片，说明其中的内容。";
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

            // 2. 读最近 10 条历史消息：只用于「上下文条数」计费与日志。
            // AstrBot 不消费请求体里的 context，模型实际看到的是它自己的会话历史（由 session_id 决定）。
            List<Map<String, String>> context = conversationService.buildConversationContext(currentConversationId, 10);
            log.debug("sendMessage 上下文条数={}（仅计费用）", context.size());

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

            // 4. 调用 AstrBot（传输/模型/人格/会话统一由 ChatProvider 适配器负责）
            com.qqai.capability.chat.ChatReply chatReply = chatProvider.chat(
                    new com.qqai.capability.chat.ChatRequest(
                            userId, currentConversationId, message, requestImageUrls,
                            model, cfgName, cfgId, userQq, null,
                            com.qqai.capability.chat.ChatPurpose.CHAT));
            String finalReply = chatReply.text();
            Integer promptTokens = chatReply.promptTokens();
            Integer completionTokens = chatReply.completionTokens();
            Integer totalTokens = chatReply.totalTokens();
            log.debug("AstrBot 最终回复: {}", finalReply);

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

        } catch (com.qqai.exception.BizException biz) {
            return ResponseEntity.status(biz.getCode()).body(Map.of(
                    "status", "error", "message", biz.getMessage()));
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
            // 连带删掉 AstrBot 侧该会话的记忆（best-effort：失败只记日志，不影响本地删除）
            Map<String, Object> memoryCleanup = astrBotSessionMemoryService.purgeConversation(conversationId);
            Map<String, Object> okResult = new HashMap<>();
            okResult.put("status", "ok");
            okResult.put("memoryCleanup", memoryCleanup);
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
     * 获取 AstrBot 可用模型列表。
     *
     * <p>数据来源顺序（AstrBot 4.28 起 {@code /api/v1/models} 已被移除，404，所以不能再依赖它）：
     * <ol>
     *   <li>{@code <data-path>/cmd_config.json} 的 {@code provider[]}（4.28 仍是这个结构）</li>
     *   <li>配置档案目录 {@code <data-path>/config/abconf_*.json}——4.28 里每个档案都是整份配置的快照，
     *       同样带 {@code provider[]}；当主配置被拆到档案里时从这里兜底</li>
     *   <li>最后才尝试旧的 HTTP 接口（4.26 及以前可用）</li>
     * </ol>
     */
    @GetMapping("/models")
    public ResponseEntity<?> getModels() {
        ArrayNode modelsArray = objectMapper.createArrayNode();
        String source = "";

        // ========== 策略1：优先从本地 cmd_config.json 读取 ==========
        if (astrBotDataPath != null && !astrBotDataPath.isBlank()) {
            try {
                java.io.File configFile = new java.io.File(astrBotDataPath, "cmd_config.json");
                if (configFile.exists() && configFile.canRead()) {
                    JsonNode root = objectMapper.readTree(configFile);
                    modelsArray = collectModelsFromConfig(root);
                    if (modelsArray.size() > 0) {
                        source = "cmd_config.json";
                    }
                }
            } catch (Exception e) {
                log.warn("从 cmd_config.json 读取模型失败: {}", e.getMessage());
                modelsArray = objectMapper.createArrayNode();
            }
        }

        // ========== 策略2：主配置里没有 provider[] 时，从配置档案兜底 ==========
        if (modelsArray.size() == 0 && astrBotDataPath != null && !astrBotDataPath.isBlank()) {
            try {
                java.io.File configDir = new java.io.File(astrBotDataPath, "config");
                java.io.File[] profiles = configDir.listFiles(
                        (dir, name) -> name.startsWith("abconf_") && name.endsWith(".json"));
                if (profiles != null) {
                    for (java.io.File profile : profiles) {
                        ArrayNode fromProfile = collectModelsFromConfig(objectMapper.readTree(profile));
                        if (fromProfile.size() > 0) {
                            modelsArray = fromProfile;
                            source = "config/" + profile.getName();
                            break;
                        }
                    }
                }
            } catch (Exception e) {
                log.warn("从配置档案读取模型失败: {}", e.getMessage());
            }
        }

        // ========== 策略3：兜底走 AstrBot HTTP 接口（4.28+ 已移除该接口，会 404） ==========
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
                    source = "api";
                }
            } catch (Exception e) {
                // 4.28 起 /api/v1/models 不存在（404），这里只记 debug 级即可，真正的原因在下面的 message 里说明
                log.warn("AstrBot HTTP 模型接口不可用（4.28+ 已移除 /api/v1/models）: {}", e.getMessage());
            }
        }

        if (modelsArray.size() > 0) {
            return ResponseEntity.ok(Map.of(
                    "status", "ok",
                    "models", modelsArray,
                    "count", modelsArray.size(),
                    "source", source
            ));
        }

        return ResponseEntity.ok(Map.of(
                "status", "error",
                "message", "未读到任何模型：请确认 astrbot.data-path 指向 AstrBot 的 data 目录"
                        + "（其中应有 cmd_config.json 或在 config/ 下有 abconf_*.json 档案），"
                        + "并在 AstrBot 里配置模型。注意 AstrBot 4.28 起不再提供 /api/v1/models 接口。",
                "models", modelsArray,
                "count", 0
        ));
    }

    /**
     * 从一份 AstrBot 配置（cmd_config.json 或 abconf_*.json 档案）里提取 provider[] 模型列表。
     * 两份文件结构一致：{@code provider[]} 每项含 id / model / enable / provider_source_id / modalities。
     *
     * <p>包级可见是为了让单测直接喂 JSON（不需要启动 Spring）。</p>
     */
    ArrayNode collectModelsFromConfig(JsonNode root) {
        ArrayNode modelsArray = objectMapper.createArrayNode();
        if (root == null) return modelsArray;
        JsonNode providers = root.has("provider") ? root.get("provider") : null;
        if (providers == null || !providers.isArray()) return modelsArray;

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
        return modelsArray;
    }

    /**
     * 聊天链路（{@code /send}）的模型下发：写入 AstrBot 4.28 认的 {@code selected_provider} + {@code selected_model}。
     *
     * <p>唯一不下发的情况是「带图 + 档案已生效 + 所选模型看不了图」：这时必须让视觉档案的模型兜底，
     * 否则图片会被降级成文本路径（模型回答"看不到图"）。文本对话一律按用户所选模型走，
     * 不能因为挂了人格档案就把模型选择丢掉——那正是「选了 DeepSeek、账单记在 SiliconFlow」的成因。</p>
     *
     * @return {@link ModelDispatch#APPLIED} 表示本次确实会用所选模型；{@code UNKNOWN} 表示配置里没有；
     *         {@link ModelDispatch#VISION_PROFILE_WINS} 表示带图时让位视觉档案
     */
    private ModelDispatch applySelectedModelForChat(Map<String, Object> body, String modelId,
                                                   boolean hasImage, boolean profileApplied) {
        if (modelId == null || modelId.isBlank()) return ModelDispatch.UNKNOWN;
        // 先按 provider 实例 id 精确匹配；再按上游模型名反查（自定义模型面板允许手输短名）
        JsonNode selected = astrBotModelSelector.resolve(modelId);
        if (selected == null) return ModelDispatch.UNKNOWN;
        if (hasImage && profileApplied && !AstrBotModelSelector.supportsImage(selected)) {
            log.info("带图请求保留档案模型：所选 {} 不支持图片（profileApplied=true）", modelId);
            return ModelDispatch.VISION_PROFILE_WINS;
        }
        return astrBotModelSelector.apply(body, modelId) ? ModelDispatch.APPLIED : ModelDispatch.UNKNOWN;
    }

    /** 模型下发结果：{@code /send} 需要区分「配置里没有」与「带图让位视觉档案」两种未下发情况 */
    private enum ModelDispatch {
        /** 已写入 selected_provider/selected_model，本次确实用所选模型 */
        APPLIED,
        /** 模型不在 AstrBot 配置里（配置读不到时也是这个） */
        UNKNOWN,
        /** 带图 + 所选模型不支持图片：保留视觉档案的模型 */
        VISION_PROFILE_WINS
    }

    /**
     * 分析链路（{@code /analyze}、{@code /analyze-selected}）的模型下发。
     *
     * <p>分析请求总会挂人格/视觉档案，所以带图时能不能顶掉档案模型取决于所选模型是否支持图片；
     * 找不到模型时只记警告（分析链路不该因为一个过期的模型名整条失败）。</p>
     */
    private void applySelectedModelForAnalysis(Map<String, Object> body, String modelId, boolean hasImage) {
        if (modelId == null || modelId.isBlank() || "default".equalsIgnoreCase(modelId)) return;
        body.put("model", modelId);   // 兼容 4.26 及以前版本
        JsonNode selected = astrBotModelSelector.resolve(modelId);
        if (selected == null) {
            log.warn("分析链路所选模型 {} 不在 AstrBot 配置中，本次交给档案默认模型", modelId);
            return;
        }
        if (hasImage && !AstrBotModelSelector.supportsImage(selected)) {
            log.info("分析链路带图但所选 {} 不支持图片，保留视觉档案模型", modelId);
            return;
        }
        astrBotModelSelector.apply(body, modelId);
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
    /**
     * 分析对话在 AstrBot 里的标题：{@code 群分析 · 群聊速览 · 群名 · MM-dd HH:mm}。
     *
     * <p>用户要求保留分析对话并补标题（2026-10-28）：AstrBot 走开放 API 不会自动起标题，
     * 不补的话它在「数据与日志 → 对话」里就是「无标题对话」。</p>
     */
    private String analysisConversationTitle(String analysisType, String groupId) {
        String label = analysisTypeLabel(
                analysisType == null || analysisType.isBlank() ? "summary" : analysisType);
        String groupName = null;
        try {
            Map<String, Object> info = resolveGroupInfo(groupId);
            if (info != null && info.get("groupName") != null) groupName = String.valueOf(info.get("groupName"));
        } catch (Exception ignored) {
            // 群信息读不到就用群号兜底
        }
        if (groupName == null || groupName.isBlank()) {
            groupName = (groupId != null && !groupId.isBlank()) ? "群 " + groupId : "群聊";
        }
        return "群分析 · " + label + " · " + groupName + " · "
                + java.time.LocalDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("MM-dd HH:mm"));
    }

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

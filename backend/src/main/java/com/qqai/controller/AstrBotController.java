package com.qqai.controller;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.qqai.entity.AstrBotConversation;
import com.qqai.entity.AstrBotMessage;
import com.qqai.entity.Message;
import com.qqai.plugin.PluginManager;
import com.qqai.service.AstrBotConversationService;
import com.qqai.service.MessageService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.RestTemplate;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.ArrayList;

/**
 * AstrBot 控制器 - 处理 AstrBot 消息和 AI 分析
 * 
 * 注意：此控制器主要接收 AstrBot 的消息推送，而不是主动调用 AstrBot API
 * 因为 AstrBot 的 API 认证需要 JWT Token，获取 Token 需要登录密码
 */
@RestController
@RequestMapping("/api/astrbot")
public class AstrBotController {

    @Autowired
    private MessageService messageService;

    @Autowired
    private AstrBotConversationService conversationService;

    @Autowired
    private PluginManager pluginManager;

    @Autowired
    private jakarta.persistence.EntityManager entityManager;

    @Value("${astrbot.api-url:http://localhost:6185}")
    private String astrBotApiUrl;

    @Value("${astrbot.token:}")
    private String astrBotToken;

    private final RestTemplate restTemplate = new RestTemplate();

    /**
     * 接收 AstrBot 的消息推送
     * 这是 AstrBot 主动推送到 SpringBoot 的消息
     */
    @PostMapping("/callback")
    public ResponseEntity<?> receiveFromAstrBot(@RequestBody String payload) {
        System.out.println("【AstrBot回调】收到消息: " + payload);

        try {
            JSONObject json = JSON.parseObject(payload);
            
            // 解析 AstrBot 消息格式
            String messageType = json.getString("message_type");
            String content = json.getString("message");
            String senderId = json.getString("sender_id");
            String senderName = json.getString("sender_name");
            String groupId = json.getString("group_id");
            String sessionId = json.getString("session_id");
            
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
                System.out.println("AstrBot消息已保存, ID: " + saved.getId());
            }
            
            Map<String, Object> okResult = new HashMap<>();
            okResult.put("status", "ok");
            return ResponseEntity.ok(okResult);
        } catch (Exception e) {
            System.err.println("处理AstrBot消息失败: " + e.getMessage());
            e.printStackTrace();
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
            List<Message> messages = messageService.getMessagesByGroupId(groupId);
            
            // 如果 groupId 查询为空，尝试用 group_name 查询
            if (messages.isEmpty() && !groupId.matches("\\d+")) {
                // 查询群号
                String sql = "SELECT group_id FROM chat_groups WHERE group_name = ? AND active = 1 LIMIT 1";
                System.out.println("尝试用群名查询群号: " + groupId);
                try {
                    jakarta.persistence.Query query = entityManager.createNativeQuery(sql);
                    query.setParameter(1, groupId);
                    Object result = query.getSingleResult();
                    if (result != null) {
                        String actualGroupId = result.toString();
                        System.out.println("找到群号: " + actualGroupId);
                        messages = messageService.getMessagesByGroupId(actualGroupId);
                    }
                } catch (Exception e) {
                    System.out.println("群名查询失败: " + e.getMessage());
                }
            }
            
            // 2. 构造分析提示词
            StringBuilder prompt = new StringBuilder();
            
            // 添加系统指令，让 AstrBot 使用工具查询并分析
            prompt.append("【系统指令】你是一名群聊分析助手。请使用 MySQL 工具查询群聊 \"" + groupId + "\" 的消息数据。\n");
            prompt.append("【重要】完成分析后，请使用 Markdown 格式输出总结，支持标题、列表、加粗、链接。不要包含任何 JSON 格式或工具调用信息。\n\n");
            
            switch (analysisType) {
                case "summary":
                    prompt.append("请总结该群聊的主要内容，用简洁的语言描述：\n\n");
                    break;
                case "sentiment":
                    prompt.append("请分析该群聊的情感倾向，给出整体氛围评价：\n\n");
                    break;
                case "keywords":
                    prompt.append("请提取该群聊的关键词（最多5个）：\n\n");
                    break;
                case "active":
                    prompt.append("请分析该群聊中谁最活跃，列出前3名：\n\n");
                    break;
                default:
                    prompt.append("请分析该群聊的消息内容：\n\n");
            }
            
            prompt.append("群号：" + groupId + "\n");
            prompt.append("请查询数据库并给出分析结果。");

            // 3. 直接调用 AstrBot API 获取分析结果
            String url = astrBotApiUrl + "/api/v1/chat";
            
            HttpHeaders headers = new HttpHeaders();
            headers.set("Authorization", "Bearer " + astrBotToken);
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.set("Accept", "application/json");
            
            Map<String, Object> body = new HashMap<>();
            body.put("message", prompt.toString());
            body.put("username", "analyzer");
            body.put("enable_streaming", false);
            
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
                            JSONObject json = JSON.parseObject(jsonData);
                            String type = json.getString("type");
                            if ("plain".equals(type)) {
                                String data = json.getString("data");
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
            
            System.out.println("AstrBot 分析结果: " + analysis);

            // 返回分析结果
            JSONObject result = new JSONObject();
            result.put("status", "ok");
            result.put("analysis", analysis);
            
            return ResponseEntity.ok()
                .header("Content-Type", "application/json; charset=UTF-8")
                .body(result.toJSONString());
            
        } catch (Exception e) {
            System.err.println("AstrBot 分析请求失败: " + e.getMessage());
            e.printStackTrace();
            JSONObject error = new JSONObject();
            error.put("status", "error");
            error.put("message", e.getMessage());
            return ResponseEntity.ok()
                .header("Content-Type", "application/json; charset=UTF-8")
                .body(error.toJSONString());
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

        try {
            // 1. 获取或创建对话
            AstrBotConversation conversation = conversationService.getOrCreateConversation(
                    conversationId, groupId, userQq, userNickname, model);
            String currentConversationId = conversation.getConversationId();

            // 2. 保存用户消息到数据库
            conversationService.addUserMessage(currentConversationId, message, null);

            // 3. 构建对话上下文（最近10条消息）
            List<Map<String, String>> context = conversationService.buildConversationContext(currentConversationId, 10);

            // 4. 调用 AstrBot HTTP API
            String url = astrBotApiUrl + "/api/v1/chat";
            
            HttpHeaders headers = new HttpHeaders();
            headers.set("Authorization", "Bearer " + astrBotToken);
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.set("Accept", "application/json");
            
            Map<String, Object> body = new HashMap<>();
            body.put("message", message);
            body.put("username", userQq != null ? userQq : "web_user");
            body.put("enable_streaming", false);
            if (groupId != null) {
                body.put("session_id", groupId);
            }
            // 在上下文最前面追加系统提示，要求 AI 使用 Markdown 格式回复
            Map<String, String> systemMessage = new HashMap<>();
            systemMessage.put("role", "system");
            systemMessage.put("content", "请使用 Markdown 格式回复。支持标题、列表、加粗、代码块、引用、链接。如需摘要请使用 <details><summary>摘要</summary>...</details>。");
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
                System.out.println("AstrBot 原始响应: " + responseBody.substring(0, Math.min(500, responseBody.length())));
                
                // 解析 SSE 格式的数据行
                String[] lines = responseBody.split("\n");
                for (String line : lines) {
                    line = line.trim();
                    if (line.startsWith("data: ")) {
                        String jsonData = line.substring(6);
                        try {
                            JSONObject json = JSON.parseObject(jsonData);
                            String type = json.getString("type");
                            if ("plain".equals(type)) {
                                String data = json.getString("data");
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
                            if (json.containsKey("usage")) {
                                JSONObject usage = json.getJSONObject("usage");
                                if (usage != null) {
                                    promptTokens = usage.getInteger("prompt_tokens");
                                    completionTokens = usage.getInteger("completion_tokens");
                                    totalTokens = usage.getInteger("total_tokens");
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
            
            System.out.println("AstrBot 最终回复: " + finalReply);
            
            // 6. 保存 AI 回复到数据库
            conversationService.addAssistantMessage(
                    currentConversationId, finalReply, model, 
                    totalTokens, promptTokens, completionTokens);

            // 7. 如果是新对话，自动生成标题
            if (conversation.getMessageCount() <= 2 && conversation.getTitle().equals("新对话")) {
                conversationService.autoGenerateTitle(currentConversationId);
            }
            
            // 8. 构建 JSON 响应
            JSONObject result = new JSONObject();
            result.put("status", "ok");
            result.put("data", finalReply);
            result.put("conversationId", currentConversationId);
            result.put("messageCount", conversation.getMessageCount() + 2); // +2 因为刚保存了两条消息
            
            // 直接返回 JSONObject，让 Spring 自动转换为 JSON
            return ResponseEntity.ok(result);
            
        } catch (Exception e) {
            System.err.println("发送消息到 AstrBot 失败: " + e.getMessage());
            e.printStackTrace();
            JSONObject error = new JSONObject();
            error.put("status", "error");
            error.put("message", e.getMessage());
            return ResponseEntity.ok()
                .header("Content-Type", "application/json; charset=UTF-8")
                .body(error.toJSONString());
        }
    }

    // ==================== 对话管理 API ====================

    /**
     * 获取对话列表
     * 支持按 groupId 或 userQq 筛选，如果不提供则返回所有未归档的对话
     */
    @GetMapping("/conversations")
    public ResponseEntity<?> getConversations(
            @RequestParam(required = false) String groupId,
            @RequestParam(required = false) String userQq,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        try {
            List<AstrBotConversation> conversations;
            if (groupId != null && !groupId.isEmpty()) {
                conversations = conversationService.getGroupConversations(groupId);
            } else if (userQq != null && !userQq.isEmpty()) {
                conversations = conversationService.getUserConversations(userQq);
            } else {
                // 如果没有提供参数，返回所有未归档的对话
                conversations = conversationService.getAllActiveConversations();
            }

            JSONObject result = new JSONObject();
            result.put("status", "ok");
            result.put("data", conversations);
            return ResponseEntity.ok(result.toJSONString());
        } catch (Exception e) {
            JSONObject error = new JSONObject();
            error.put("status", "error");
            error.put("message", e.getMessage());
            return ResponseEntity.ok(error.toJSONString());
        }
    }

    /**
     * 获取单个对话详情
     */
    @GetMapping("/conversations/{conversationId}")
    public ResponseEntity<?> getConversation(@PathVariable String conversationId) {
        try {
            Optional<AstrBotConversation> conversation = conversationService.getConversation(conversationId);
            if (conversation.isPresent()) {
                JSONObject result = new JSONObject();
                result.put("status", "ok");
                result.put("data", conversation.get());
                return ResponseEntity.ok(result.toJSONString());
            } else {
                Map<String, Object> errorResult = new HashMap<>();
                errorResult.put("error", "对话不存在");
                return ResponseEntity.status(404).body(errorResult);
            }
        } catch (Exception e) {
            JSONObject error = new JSONObject();
            error.put("status", "error");
            error.put("message", e.getMessage());
            return ResponseEntity.ok(error.toJSONString());
        }
    }

    /**
     * 获取对话的消息列表
     */
    @GetMapping("/conversations/{conversationId}/messages")
    public ResponseEntity<?> getConversationMessages(
            @PathVariable String conversationId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {
        try {
            List<AstrBotMessage> messages = conversationService.getConversationMessages(conversationId);
            JSONObject result = new JSONObject();
            result.put("status", "ok");
            result.put("data", messages);
            return ResponseEntity.ok(result.toJSONString());
        } catch (Exception e) {
            JSONObject error = new JSONObject();
            error.put("status", "error");
            error.put("message", e.getMessage());
            return ResponseEntity.ok(error.toJSONString());
        }
    }

    /**
     * 创建新对话
     */
    @PostMapping("/conversations")
    public ResponseEntity<?> createConversation(@RequestBody Map<String, Object> request) {
        try {
            String groupId = (String) request.get("groupId");
            String userQq = (String) request.get("userQq");
            String userNickname = (String) request.get("userNickname");
            String title = (String) request.get("title");
            String model = (String) request.get("model");

            AstrBotConversation conversation = conversationService.createConversation(
                    groupId, userQq, userNickname, title, model);

            JSONObject result = new JSONObject();
            result.put("status", "ok");
            result.put("data", conversation);
            return ResponseEntity.ok(result.toJSONString());
        } catch (Exception e) {
            JSONObject error = new JSONObject();
            error.put("status", "error");
            error.put("message", e.getMessage());
            return ResponseEntity.ok(error.toJSONString());
        }
    }

    /**
     * 更新对话标题
     */
    @PutMapping("/conversations/{conversationId}/title")
    public ResponseEntity<?> updateConversationTitle(
            @PathVariable String conversationId,
            @RequestBody Map<String, String> request) {
        try {
            String title = request.get("title");
            conversationService.updateConversationTitle(conversationId, title);
            Map<String, Object> okResult = new HashMap<>();
            okResult.put("status", "ok");
            return ResponseEntity.ok(okResult);
        } catch (Exception e) {
            JSONObject error = new JSONObject();
            error.put("status", "error");
            error.put("message", e.getMessage());
            return ResponseEntity.ok(error.toJSONString());
        }
    }

    /**
     * 归档对话
     */
    @PostMapping("/conversations/{conversationId}/archive")
    public ResponseEntity<?> archiveConversation(@PathVariable String conversationId) {
        try {
            conversationService.archiveConversation(conversationId);
            Map<String, Object> okResult = new HashMap<>();
            okResult.put("status", "ok");
            return ResponseEntity.ok(okResult);
        } catch (Exception e) {
            JSONObject error = new JSONObject();
            error.put("status", "error");
            error.put("message", e.getMessage());
            return ResponseEntity.ok(error.toJSONString());
        }
    }

    /**
     * 删除对话
     */
    @DeleteMapping("/conversations/{conversationId}")
    public ResponseEntity<?> deleteConversation(@PathVariable String conversationId) {
        try {
            conversationService.deleteConversation(conversationId);
            Map<String, Object> okResult = new HashMap<>();
            okResult.put("status", "ok");
            return ResponseEntity.ok(okResult);
        } catch (Exception e) {
            JSONObject error = new JSONObject();
            error.put("status", "error");
            error.put("message", e.getMessage());
            return ResponseEntity.ok(error.toJSONString());
        }
    }

    /**
     * 获取对话统计信息
     */
    @GetMapping("/conversations/{conversationId}/stats")
    public ResponseEntity<?> getConversationStats(@PathVariable String conversationId) {
        try {
            Map<String, Object> stats = conversationService.getConversationStats(conversationId);
            JSONObject result = new JSONObject();
            result.put("status", "ok");
            result.put("data", stats);
            return ResponseEntity.ok(result.toJSONString());
        } catch (Exception e) {
            JSONObject error = new JSONObject();
            error.put("status", "error");
            error.put("message", e.getMessage());
            return ResponseEntity.ok(error.toJSONString());
        }
    }
}

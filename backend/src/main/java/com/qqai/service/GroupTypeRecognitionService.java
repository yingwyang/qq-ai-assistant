package com.qqai.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.qqai.constant.GroupType;
import com.qqai.entity.Message;
import com.qqai.repository.GroupRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 群类型 AI 识别服务
 * 基于群聊最近消息样本，调用 LLM 推断群类型（GAME/STUDY/WORK/HOBBY/LIFE/SOCIAL/OTHER）
 */
@Service
public class GroupTypeRecognitionService {

    private static final Logger log = LoggerFactory.getLogger(GroupTypeRecognitionService.class);
    private static final int SAMPLE_COUNT = 50;
    private static final long CACHE_TTL_MS = 24 * 60 * 60 * 1000L; // 24h

    @Autowired
    private AstrBotService astrBotService;

    /** 人层（用户选定的人格）；测试直接 new 时为 null */
    @Autowired(required = false)
    private AstrBotPersonaService astrBotPersonaService;

    @Autowired(required = false)
    private com.qqai.common.SecurityHelper securityHelper;

    @Autowired
    private MessageService messageService;

    @Autowired
    private PromptTemplateService promptTemplateService;

    @Autowired
    private GroupRepository groupRepository;

    private final ObjectMapper objectMapper = new ObjectMapper();

    // 识别结果缓存：groupId -> {result, expireAt}
    private final Map<String, long[]> cacheTimestamps = new ConcurrentHashMap<>();
    private final Map<String, RecognitionResult> cacheResults = new ConcurrentHashMap<>();

    public static class RecognitionResult {
        public final String groupType;
        public final double confidence;
        public final String reason;
        /** 是否真的拿到了可用的 LLM 结果：false 表示识别失败，调用方据此决定是否扣积分 */
        public final boolean success;

        public RecognitionResult(String groupType, double confidence, String reason) {
            this(groupType, confidence, reason, false);
        }

        public RecognitionResult(String groupType, double confidence, String reason, boolean success) {
            this.groupType = groupType;
            this.confidence = confidence;
            this.reason = reason;
            this.success = success;
        }

        public Map<String, Object> toMap() {
            Map<String, Object> m = new HashMap<>();
            m.put("recognizedType", groupType);
            GroupType gt = GroupType.fromString(groupType);
            m.put("recognizedTypeLabel", gt.getLabel());
            m.put("confidence", confidence);
            m.put("reason", reason);
            return m;
        }
    }

    /**
     * 识别群类型（带 24h 缓存）
     * @param groupId 群号
     * @param forceRefresh 是否强制刷新缓存
     */
    public RecognitionResult recognizeGroupType(String groupId, boolean forceRefresh) {
        if (groupId == null || groupId.isBlank()) {
            return new RecognitionResult("OTHER", 0.0, "群号为空");
        }

        // 检查缓存
        if (!forceRefresh) {
            long[] ts = cacheTimestamps.get(groupId);
            if (ts != null && System.currentTimeMillis() < ts[0]) {
                RecognitionResult cached = cacheResults.get(groupId);
                if (cached != null) return cached;
            }
        }

        // 1. 取最近消息样本
        List<Message> messages = messageService.getMessagesByGroupId(groupId);
        if (messages == null || messages.isEmpty()) {
            RecognitionResult result = new RecognitionResult("OTHER", 0.0, "群聊无消息，无法判断");
            cacheResult(groupId, result);
            return result;
        }

        int fromIndex = Math.max(0, messages.size() - SAMPLE_COUNT);
        List<Message> recent = messages.subList(fromIndex, messages.size());

        StringBuilder samples = new StringBuilder();
        for (Message m : recent) {
            String speaker = m.getUserNickname() != null ? m.getUserNickname() : m.getUserQq();
            String content = m.getContent();
            if (content != null && !content.isBlank()) {
                samples.append(speaker).append(": ").append(truncate(content, 100)).append("\n");
            }
        }

        if (samples.length() == 0) {
            RecognitionResult result = new RecognitionResult("OTHER", 0.0, "消息内容均为空");
            cacheResult(groupId, result);
            return result;
        }

        // 2. 渲染识别 prompt
        Map<String, Object> vars = new HashMap<>();
        vars.put("messageSamples", samples.toString());
        String prompt = promptTemplateService.render("type.recognition", null, vars);

        // 3. 调 AstrBot LLM
        String llmResponse;
        try {
            llmResponse = callLlm(prompt);
        } catch (Exception e) {
            log.error("群类型识别 LLM 调用失败 groupId={}: {}", groupId, e.getMessage());
            RecognitionResult result = new RecognitionResult("OTHER", 0.0, "LLM 调用失败: " + e.getMessage());
            cacheResult(groupId, result);
            return result;
        }

        // 4. 解析返回
        RecognitionResult result = parseRecognitionResult(llmResponse);
        cacheResult(groupId, result);
        log.info("群类型识别完成 groupId={}: type={}, confidence={}, reason={}",
                groupId, result.groupType, result.confidence, result.reason);
        return result;
    }

    private String callLlm(String message) throws Exception {
        // 复用 AstrBotService 中已验证的调用方式（username 必填 + SSE 解析）。
        // 此处原本自己拼了一套请求：缺 username，AstrBot 恒返回
        // {"status":"error","message":"Missing key: username"}，又按整段 JSON 取 response 字段，
        // 于是永远拿到 null，前端显示"其他群 / 置信度 0% / LLM 返回为空"。
        // 人层：群类型识别同样属于「岗位」，说话方式沿用触发者选定的人格（没选则默认）。
        String personaConfig = null;
        try {
            if (astrBotPersonaService != null && securityHelper != null) {
                personaConfig = astrBotPersonaService.resolveConfigName(securityHelper.getCurrentUserId(), false);
            }
        } catch (Exception e) {
            log.debug("解析人格档案失败，使用默认档案: {}", e.getMessage());
        }
        return astrBotService.chat(message, null, "type.recognition", personaConfig);
    }

    private RecognitionResult parseRecognitionResult(String llmResponse) {
        if (llmResponse == null || llmResponse.isBlank()) {
            return new RecognitionResult("OTHER", 0.0, "LLM 返回为空");
        }

        try {
            // 尝试从响应中提取 JSON
            String json = extractJson(llmResponse);
            JsonNode node = objectMapper.readTree(json);

            String groupType = node.has("groupType") ? node.get("groupType").asText() : "OTHER";
            double confidence = node.has("confidence") ? node.get("confidence").asDouble() : 0.0;
            String reason = node.has("reason") ? node.get("reason").asText() : "无";

            // 校验 groupType 合法性
            GroupType gt = GroupType.fromString(groupType);
            if (gt == GroupType.OTHER && !"OTHER".equalsIgnoreCase(groupType.trim())) {
                log.warn("群类型识别返回了未知类型: {}，按 OTHER 处理", groupType);
            }
            return new RecognitionResult(gt.name(), confidence, reason, true);
        } catch (Exception e) {
            log.warn("群类型识别结果解析失败: {} -> {}", llmResponse, e.getMessage());
            return new RecognitionResult("OTHER", 0.0, "解析失败");
        }
    }

    /**
     * 从 LLM 响应中提取 JSON 字符串（兼容 ```json 包裹和纯 JSON）
     */
    private String extractJson(String text) {
        String trimmed = text.trim();
        // 移除 markdown 代码块包裹
        if (trimmed.startsWith("```")) {
            trimmed = trimmed.replaceAll("^```\\w*\\n?", "").replaceAll("\\n?```$", "");
        }
        // 找第一个 { 到最后一个 }
        int start = trimmed.indexOf('{');
        int end = trimmed.lastIndexOf('}');
        if (start >= 0 && end > start) {
            return trimmed.substring(start, end + 1);
        }
        return trimmed;
    }

    private String truncate(String s, int maxLen) {
        return s.length() > maxLen ? s.substring(0, maxLen) + "..." : s;
    }

    private void cacheResult(String groupId, RecognitionResult result) {
        if (!result.success) {
            // 失败结果不缓存：否则 24h 内再点"重新识别"都会直接命中同一条失败结果
            cacheResults.remove(groupId);
            cacheTimestamps.remove(groupId);
            return;
        }
        cacheResults.put(groupId, result);
        cacheTimestamps.put(groupId, new long[]{System.currentTimeMillis() + CACHE_TTL_MS});
    }
}

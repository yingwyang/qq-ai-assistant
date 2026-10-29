package com.qqai.adapter.chat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.qqai.capability.chat.ChatPurpose;
import com.qqai.capability.chat.ChatRequest;
import com.qqai.exception.BizException;
import com.qqai.service.AstrBotModelSelector;
import com.qqai.service.AstrBotPersonaService;
import com.qqai.service.AstrBotSessionMemoryService;
import com.qqai.service.UserSettingsService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * AstrBotChatAdapter 的请求组装契约：
 * 会话键/人格档案/模型下发/多模态/分析一次性会话，全部在适配器内部完成，
 * 业务层只拿 ChatReply。
 */
class AstrBotChatAdapterTest {

    private AstrBotPersonaService personaService;
    private AstrBotModelSelector modelSelector;
    private AstrBotMediaSupport mediaSupport;
    private UserSettingsService userSettingsService;
    private AstrBotChatAdapter adapter;
    private ObjectMapper mapper;

    @BeforeEach
    void setUp() {
        personaService = mock(AstrBotPersonaService.class);
        modelSelector = mock(AstrBotModelSelector.class);
        mediaSupport = mock(AstrBotMediaSupport.class);
        userSettingsService = mock(UserSettingsService.class);
        mapper = new ObjectMapper();
        adapter = new AstrBotChatAdapter(personaService, modelSelector, mediaSupport, userSettingsService, mapper);
        adapter.initHttpClient();
        given(userSettingsService.findByUserId(anyString())).willReturn(Optional.empty());
    }

    private ChatRequest request(List<String> images, String model, String cfgName, ChatPurpose purpose) {
        return new ChatRequest(1L, "conv_abc", "hi", images, model, cfgName, null, "10001", purpose);
    }

    @Test
    @DisplayName("会话键 = qqai-<conversationId>@人格，且带人格档案")
    void buildsSessionAndPersona() {
        given(personaService.currentPersonaId(1L)).willReturn("ray");
        given(personaService.resolveConfigName(1L, false)).willReturn("qqai-p-ray-text");

        Map<String, Object> body = adapter.buildRequestBody(request(List.of(), null, null, ChatPurpose.CHAT));

        String expected = AstrBotSessionMemoryService.buildSessionId(
                "conv_abc", AstrBotSessionMemoryService.personaKey("ray"));
        assertEquals(expected, body.get("session_id"));
        assertEquals("qqai-p-ray-text", body.get("config_name"));
        assertEquals("10001", body.get("username"));
        // 纯文本保留默认系统提示（人格仍生效）；只有带图才关闭
        assertNull(((Map<?, ?>) body.get("flags")).get("enable_default_system_prompt"));
    }

    @Test
    @DisplayName("显式模型存在时下发 selected_provider/selected_model（经 selector.apply）")
    void dispatchesSelectedModel() {
        ObjectNode node = mapper.createObjectNode();
        given(personaService.resolveConfigName(1L, false)).willReturn(null);
        given(modelSelector.resolve("deepseek/deepseek-flash")).willReturn(node);
        given(modelSelector.apply(anyMap(), eq("deepseek/deepseek-flash"))).willReturn(true);

        Map<String, Object> body = adapter.buildRequestBody(
                request(List.of(), "deepseek/deepseek-flash", null, ChatPurpose.CHAT));

        assertEquals("deepseek/deepseek-flash", body.get("model"));
        verify(modelSelector).apply(anyMap(), eq("deepseek/deepseek-flash"));
    }

    @Test
    @DisplayName("显式模型不在配置中：抛 400，不静默回退")
    void unknownExplicitModelFails() {
        given(personaService.resolveConfigName(1L, false)).willReturn(null);
        given(modelSelector.resolve("nope")).willReturn(null);
        given(modelSelector.providers()).willReturn(mapper.createArrayNode());

        BizException ex = assertThrows(BizException.class, () ->
                adapter.buildRequestBody(request(List.of(), "nope", null, ChatPurpose.CHAT)));
        assertEquals(400, ex.getCode());
        assertTrue(ex.getMessage().contains("已不在 AstrBot 配置中"));
    }

    @Test
    @DisplayName("带图：走多模态 message，并关闭默认系统提示")
    void multimodalBody() {
        given(personaService.resolveConfigName(1L, true)).willReturn("qqai-p-ray-image");
        given(mediaSupport.buildMultimodalMessage(eq("hi"), any(), any())).willReturn("MULTI");

        Map<String, Object> body = adapter.buildRequestBody(
                request(List.of("/uploads/a.png"), null, null, ChatPurpose.CHAT));

        assertEquals("MULTI", body.get("message"));
        assertEquals(false, ((Map<?, ?>) body.get("flags")).get("enable_default_system_prompt"));
        assertEquals("qqai-p-ray-image", body.get("config_name"));
    }

    @Test
    @DisplayName("分析：一次性会话，不复用对话 session")
    void analysisUsesOneShotSession() {
        given(personaService.resolveConfigName(1L, false)).willReturn(null);

        Map<String, Object> body = adapter.buildRequestBody(
                request(List.of(), null, null, ChatPurpose.ANALYSIS));

        String session = String.valueOf(body.get("session_id"));
        assertTrue(session.startsWith("qqai-"));
        assertFalse(session.contains("conv_abc"));
    }
}

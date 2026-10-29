package com.qqai.adapter.tts;

import com.qqai.service.GptSovitsService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/** GPT-SoVITS 适配器：只做委托，不改变参数与返回语义。 */
class GptSovitsTtsAdapterTest {

    private GptSovitsService service;
    private GptSovitsTtsAdapter adapter;

    @BeforeEach
    void setUp() {
        service = mock(GptSovitsService.class);
        adapter = new GptSovitsTtsAdapter(service);
    }

    @Test
    @DisplayName("合成/音色/切换全部透传")
    void delegates() throws Exception {
        given(service.generateVoice("hi", 1L, "ray")).willReturn("/uploads/tts/a.mp3");
        given(service.listCharacters()).willReturn(List.of(Map.of("label", "灰泽满", "name", "ray")));
        given(service.getUserCharacter(1L)).willReturn("ray");

        assertEquals("/uploads/tts/a.mp3", adapter.synthesize("hi", 1L, "ray"));
        assertEquals(1, adapter.listCharacters().size());
        assertEquals("ray", adapter.currentCharacter(1L));

        adapter.switchCharacter(1L, "ray");
        verify(service).setCharacterForUser(1L, "ray");
    }

    @Test
    @DisplayName("健康探测：未启动时返回不可用但不抛异常")
    void healthIsSafe() {
        ReflectionTestUtilsSetApiUrl(adapter);
        var status = adapter.health();
        assertFalse(status.healthy());
    }

    private static void ReflectionTestUtilsSetApiUrl(GptSovitsTtsAdapter adapter) {
        org.springframework.test.util.ReflectionTestUtils.setField(adapter, "apiUrl", "http://127.0.0.1:59999");
    }
}

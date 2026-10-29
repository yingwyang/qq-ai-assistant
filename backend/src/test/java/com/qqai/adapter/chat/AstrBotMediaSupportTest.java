package com.qqai.adapter.chat;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestTemplate;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** 多模态支持的基础契约：无图直接回退文本；本地 URL 解析为 uploads 下的文件。 */
class AstrBotMediaSupportTest {

    private final AstrBotMediaSupport support = new AstrBotMediaSupport(new RestTemplate(), new ObjectMapper());

    @Test
    @DisplayName("无图片时直接返回纯文本 prompt")
    void noImagesReturnsPrompt() {
        assertEquals("hello", support.buildMultimodalMessage("hello", List.of(), "token"));
        assertEquals("hello", support.buildMultimodalMessage("hello", null, "token"));
    }

    @Test
    @DisplayName("/images 与 /uploads 映射到 uploads 目录")
    void resolvesLocalPaths() {
        assertEquals("uploads/images/a/b.png",
                support.resolveImageFileFromUrl("/images/a/b.png").getPath().replace('\\', '/'));
        assertEquals("uploads/a.png",
                support.resolveImageFileFromUrl("/uploads/a.png").getPath().replace('\\', '/'));
        assertNull(support.resolveImageFileFromUrl("/other/a.png"));
    }
}

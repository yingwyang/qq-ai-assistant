package com.qqai.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 媒体路径 → 群号解析测试（媒体清理时用于把「全表 LIKE 扫描」收窄到单个群）。
 *
 * <p>清理文件时会「每删一个文件调一次」引用消息查询，不带群号过滤就是每删一个文件全表扫一遍。
 * 解析必须保守：形态不符一律返回 null，让调用方回退到不带群号的查询，避免漏删。</p>
 */
class FilePurgeServiceTest {

    @Test
    @DisplayName("规范形态 <mediaType>/<groupId>/<date>/<file> 能解析出群号")
    void extractsGroupIdFromCanonicalPath() {
        assertEquals("123456789", FilePurgeService.extractGroupIdFromMediaPath("image/123456789/2026-10-15/a.jpg"));
        assertEquals("123456789", FilePurgeService.extractGroupIdFromMediaPath("video/123456789/2026-10-15/a.mp4"));
        assertEquals("123456789", FilePurgeService.extractGroupIdFromMediaPath("voice/123456789/2026-10-15/a.mp3"));
        assertEquals("123456789", FilePurgeService.extractGroupIdFromMediaPath("images/123456789/2026-10-15/a.jpg"));
    }

    @Test
    @DisplayName("前导斜杠不影响解析")
    void toleratesLeadingSlash() {
        assertEquals("123456789", FilePurgeService.extractGroupIdFromMediaPath("/image/123456789/2026-10-15/a.jpg"));
    }

    @Test
    @DisplayName("非媒体类型目录 → null（回退到不带群号的查询，保证不漏删）")
    void returnsNullForNonMediaDirs() {
        assertNull(FilePurgeService.extractGroupIdFromMediaPath("avatars/groups/group_1.jpg"));
        assertNull(FilePurgeService.extractGroupIdFromMediaPath("tts/2/2026-10-15/a.wav"));
        assertNull(FilePurgeService.extractGroupIdFromMediaPath("outbound/123456789/a.jpg"));
    }

    @Test
    @DisplayName("群号段不是数字 / 层级不足 → null")
    void returnsNullForUnexpectedShape() {
        assertNull(FilePurgeService.extractGroupIdFromMediaPath("image/avatars/2026-10-15/a.jpg"));
        assertNull(FilePurgeService.extractGroupIdFromMediaPath("image/123/2026-10-15/a.jpg"), "群号不应短于 5 位");
        assertNull(FilePurgeService.extractGroupIdFromMediaPath("image/123456789"));
        assertNull(FilePurgeService.extractGroupIdFromMediaPath("image/a.jpg"));
        assertNull(FilePurgeService.extractGroupIdFromMediaPath(null));
        assertNull(FilePurgeService.extractGroupIdFromMediaPath(""));
    }
}

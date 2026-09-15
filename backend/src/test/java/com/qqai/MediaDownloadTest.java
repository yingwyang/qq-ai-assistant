package com.qqai;

import com.qqai.service.MediaDownloadService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;

import static org.junit.jupiter.api.Assertions.*;

/**
 * MediaDownloadService 下载测试
 */
@SpringBootTest
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
class MediaDownloadTest {

    @Autowired
    private MediaDownloadService mediaDownloadService;

    @Test
    @DisplayName("视频 CQ 码中 URL 提取应支持反引号格式")
    void shouldExtractUrlFromVideoCqWithBacktick() {
        // NapCat 视频 CQ 码中 URL 被反引号包裹
        String cq = "[CQ:video,file=`test.mp4`,url=`https://example.com/video.mp4`,subType=0]";
        String url = mediaDownloadService.extractUrlFromCQ(cq);
        assertEquals("https://example.com/video.mp4", url);
    }

    @Test
    @DisplayName("视频 CQ 码中 URL 提取应支持普通格式")
    void shouldExtractUrlFromVideoCqWithoutBacktick() {
        String cq = "[CQ:video,file=test.mp4,url=https://example.com/video.mp4,subType=0]";
        String url = mediaDownloadService.extractUrlFromCQ(cq);
        assertEquals("https://example.com/video.mp4", url);
    }

    @Test
    @DisplayName("视频 CQ 码 path 字段提取")
    void shouldExtractPathFromVideoCq() {
        String cq = "[CQ:video,path=`C:/Users/test/NapCat/cache/test.mp4`]";
        String url = mediaDownloadService.extractUrlFromCQ(cq);
        assertNotNull(url);
        assertTrue(url.contains("test.mp4"));
    }

    @Test
    @DisplayName("图片 CQ 码提取")
    void shouldExtractUrlFromImageCq() {
        String cq = "[CQ:image,file=test.jpg,url=https://example.com/img.jpg]";
        String url = mediaDownloadService.extractUrlFromCQ(cq);
        assertEquals("https://example.com/img.jpg", url);
    }

    @Test
    @DisplayName("缺失 URL 的 CQ 码应返回 null")
    void shouldReturnNullForCqWithoutUrl() {
        String cq = "[CQ:video,file=test.mp4]";
        String url = mediaDownloadService.extractUrlFromCQ(cq);
        // 视频可能没有 url/path
        assertNull(url);
    }

    @Test
    @DisplayName("NapCat 本地 HTTP 视频 URL 不应被 SSRF 拦截")
    void shouldAllowNapCatLocalHost() {
        // NapCat 运行在 localhost，视频文件也从 localhost 服务
        assertFalse(mediaDownloadService.isBlockedUrl("http://localhost:6100/get_file?path=/video/test.mp4"),
                "NapCat localhost 不应被 SSRF 拦截");
        // 127.0.0.1 也应允许
        assertFalse(mediaDownloadService.isBlockedUrl("http://127.0.0.1:6100/get_file?path=/video/test.mp4"),
                "NapCat 127.0.0.1 不应被 SSRF 拦截");
    }

    @Test
    @DisplayName("内网 IP 仍应被拦截")
    void shouldBlockInternalIp() {
        assertTrue(mediaDownloadService.isBlockedUrl("http://192.168.1.1/file.mp4"),
                "内网 IP 应被 SSRF 拦截");
        assertTrue(mediaDownloadService.isBlockedUrl("http://10.0.0.1/file.mp4"),
                "私有网络 IP 应被 SSRF 拦截");
    }
}

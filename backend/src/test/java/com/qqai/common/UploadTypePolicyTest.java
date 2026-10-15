package com.qqai.common;

import com.qqai.exception.BizException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 上传类型策略测试（对应审查问题：{@code file.upload.allowed-types} 是死配置）。
 *
 * <p>原先该配置在代码里从未被读取，上传只校验逻辑 fileType 与体积，扩展名与 Content-Type 全由客户端提供，
 * 于是 .html / .svg / .exe / 无扩展名文件都能落库并分发给别人。这里把两类规则钉住：
 * 扩展名白名单（含 image/* 这类通配展开），以及 Content-Type 由扩展名推导。</p>
 */
class UploadTypePolicyTest {

    /** 与 application.yml 的默认配置一致 */
    private static final String DEFAULT_CONFIG =
            "image/*,video/*,audio/*,.pdf,.doc,.docx,.xls,.xlsx,.ppt,.pptx,.txt,.zip";

    @Test
    @DisplayName("通配与扩展名混写：该放行的放行")
    void allowsConfiguredTypes() {
        UploadTypePolicy policy = new UploadTypePolicy(DEFAULT_CONFIG);

        assertTrue(policy.isAllowedExtension("a.jpg"));
        assertTrue(policy.isAllowedExtension("a.PNG"), "扩展名大小写不敏感");
        assertTrue(policy.isAllowedExtension("a.mp4"));
        assertTrue(policy.isAllowedExtension("a.mp3"));
        assertTrue(policy.isAllowedExtension("a.pdf"));
        assertTrue(policy.isAllowedExtension("a.docx"));
        assertTrue(policy.isAllowedExtension("a.zip"));
    }

    @Test
    @DisplayName("白名单外的类型一律拒绝（含可执行/可脚本化类型）")
    void rejectsTypesOutsideWhitelist() {
        UploadTypePolicy policy = new UploadTypePolicy(DEFAULT_CONFIG);

        assertFalse(policy.isAllowedExtension("evil.html"));
        assertFalse(policy.isAllowedExtension("evil.svg"));
        assertFalse(policy.isAllowedExtension("evil.exe"));
        assertFalse(policy.isAllowedExtension("evil.jsp"));
        assertFalse(policy.isAllowedExtension("a.docm"));
        assertFalse(policy.isAllowedExtension("noextension"), "无扩展名应拒绝（fail-closed）");
        assertFalse(policy.isAllowedExtension(null));
    }

    @Test
    @DisplayName("requireAllowedExtension 对不允许的类型抛 400，文案带原因与白名单")
    void requireThrowsBizExceptionWithReason() {
        UploadTypePolicy policy = new UploadTypePolicy(DEFAULT_CONFIG);

        BizException e = assertThrows(BizException.class, () -> policy.requireAllowedExtension("evil.html"));
        assertEquals(400, e.getCode());
        assertTrue(e.getMessage().contains(".html"), "文案应指出实际类型：" + e.getMessage());
        assertTrue(e.getMessage().contains(".jpg"), "文案应列出允许的类型：" + e.getMessage());

        BizException noExt = assertThrows(BizException.class, () -> policy.requireAllowedExtension("README"));
        assertTrue(noExt.getMessage().contains("缺少扩展名"));

        assertEquals("jpg", policy.requireAllowedExtension("a.JPG"));
    }

    @Test
    @DisplayName("Content-Type 由扩展名推导，不采信客户端声明的 MIME")
    void resolvesContentTypeFromExtension() {
        UploadTypePolicy policy = new UploadTypePolicy(DEFAULT_CONFIG);

        assertEquals("image/jpeg", policy.resolveContentType("a.jpg"));
        assertEquals("image/png", policy.resolveContentType("a.png"));
        assertEquals("video/mp4", policy.resolveContentType("a.mp4"));
        assertEquals("audio/mpeg", policy.resolveContentType("a.mp3"));
        assertEquals("application/pdf", policy.resolveContentType("a.pdf"));
        // 未收录/无扩展名 → octet-stream（让浏览器当附件处理，避免被当作 html 执行）
        assertEquals(UploadTypePolicy.DEFAULT_CONTENT_TYPE, policy.resolveContentType("evil.html"));
        assertEquals(UploadTypePolicy.DEFAULT_CONTENT_TYPE, policy.resolveContentType("noext"));
        assertEquals(UploadTypePolicy.DEFAULT_CONTENT_TYPE, policy.resolveContentType(null));
    }

    @Test
    @DisplayName("配置为空 → 不做类型限制（保持旧行为，避免误伤未配置环境）")
    void emptyConfigDoesNotRestrict() {
        UploadTypePolicy policy = new UploadTypePolicy("");

        assertFalse(policy.isEnforced());
        assertTrue(policy.isAllowedExtension("anything.bin"));
        assertTrue(policy.isAllowedExtension("noextension"));
        assertEquals("任意类型", policy.describeAllowed());
    }

    @Test
    @DisplayName("扩展名解析：带路径、多余点号、结尾点号")
    void extensionParsingHandlesEdgeCases() {
        UploadTypePolicy policy = new UploadTypePolicy(DEFAULT_CONFIG);

        assertEquals("jpg", policy.extensionOf("C:\\tmp\\photo.JPG"));
        assertEquals("jpg", policy.extensionOf("/var/tmp/a.jpg"));
        assertEquals("gz", policy.extensionOf("archive.tar.gz"), "取最后一段扩展名");
        assertNull(policy.extensionOf("trailing."), "结尾点号视为没有扩展名");
        assertNull(policy.extensionOf(""), "空名字返回 null");
        assertNull(policy.extensionOf(null));
    }

    @Test
    @DisplayName("不支持的 MIME 通配写法被忽略（宁可不放行，也不误放行）")
    void unsupportedWildcardIsIgnored() {
        UploadTypePolicy policy = new UploadTypePolicy("application/*,.txt");

        assertFalse(policy.isAllowedExtension("a.json"));
        assertFalse(policy.isAllowedExtension("a.zip"));
        assertTrue(policy.isAllowedExtension("a.txt"));
    }
}

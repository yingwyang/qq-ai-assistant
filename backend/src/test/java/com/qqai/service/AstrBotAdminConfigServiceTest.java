package com.qqai.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 管理端 AstrBot 配置服务的纯函数测试。
 *
 * <p>重点：密钥绝不能原样回传前端 —— 这是这页唯一会把 key 暴露给浏览器的路径。
 * 规则：只露前 6 位与后 4 位，中间省略号；太短整体打码。</p>
 */
class AstrBotAdminConfigServiceTest {

    @Test
    @DisplayName("密钥缩写：前6后4，中间省略号")
    void masksKey() {
        String masked = AstrBotAdminConfigService.maskKey("sk-epzsgvbddpumrtuubxobprxfrgknqwvmpfqbqnqtioajhufv");
        assertEquals("sk-epz…hufv", masked);
        assertFalse(masked.contains("ddpumrtuubxobprxfrgknqwvmpfqbqnqtioajhufv"));
    }

    @Test
    @DisplayName("空密钥与短密钥：不打码成半截")
    void masksShortOrEmptyKey() {
        assertEquals("", AstrBotAdminConfigService.maskKey(null));
        assertEquals("", AstrBotAdminConfigService.maskKey("  "));
        assertEquals("****", AstrBotAdminConfigService.maskKey("short"));
        assertEquals("****", AstrBotAdminConfigService.maskKey("1234567890"));
    }
}

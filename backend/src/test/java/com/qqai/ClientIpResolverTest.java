package com.qqai;

import com.qqai.common.ClientIpResolver;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 客户端 IP 解析回归测试（对应审查问题 #3：X-Forwarded-For 可伪造绕过限流）。
 *
 * <p>核心不变量：<b>只要 TCP 来源不可信，就绝不采信转发头</b>。否则攻击者每次带上不同的
 * XFF 即可绕过「按 IP + 用户名 5 次/分」的登录限流，无限尝试密码。</p>
 */
class ClientIpResolverTest {

    private ClientIpResolver resolver(String trusted) {
        return new ClientIpResolver(trusted);
    }

    private MockHttpServletRequest request(String remoteAddr) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/auth/login");
        request.setRemoteAddr(remoteAddr);
        return request;
    }

    // ===================== 关键回归：直连时忽略伪造头 =====================

    @Test
    @DisplayName("直连（来源不可信）时伪造 X-Forwarded-For 无效 → 用 TCP 源地址")
    void forgedXffIgnoredWhenSourceNotTrusted() {
        ClientIpResolver r = resolver("127.0.0.1,::1");

        MockHttpServletRequest req = request("203.0.113.9");
        req.addHeader("X-Forwarded-For", "1.2.3.4");
        assertEquals("203.0.113.9", r.resolve(req), "非可信来源不得采信 XFF");

        // 每次换一个伪造值，限流键必须保持稳定，否则等于没限流
        req = request("203.0.113.9");
        req.addHeader("X-Forwarded-For", "5.6.7.8");
        assertEquals("203.0.113.9", r.resolve(req));
    }

    @Test
    @DisplayName("直连时伪造 X-Real-IP 同样无效")
    void forgedRealIpIgnoredWhenSourceNotTrusted() {
        ClientIpResolver r = resolver("127.0.0.1,::1");
        MockHttpServletRequest req = request("198.51.100.7");
        req.addHeader("X-Real-IP", "9.9.9.9");
        assertEquals("198.51.100.7", r.resolve(req));
    }

    // ===================== 可信反代：采信转发头 =====================

    @Test
    @DisplayName("来源为本机反代时，采信 X-Forwarded-For")
    void xffHonoredBehindTrustedProxy() {
        ClientIpResolver r = resolver("127.0.0.1,::1");
        MockHttpServletRequest req = request("127.0.0.1");
        req.addHeader("X-Forwarded-For", "203.0.113.9");
        assertEquals("203.0.113.9", r.resolve(req));
    }

    @Test
    @DisplayName("XFF 链从右往左取第一个非可信地址（左侧是客户端可伪造的部分）")
    void takesRightmostUntrustedFromChain() {
        ClientIpResolver r = resolver("127.0.0.1,10.0.0.1");
        MockHttpServletRequest req = request("127.0.0.1");
        // 客户端伪造成 "1.1.1.1"，代理追加真实地址 203.0.113.9，最后一跳是内网代理 10.0.0.1
        req.addHeader("X-Forwarded-For", "1.1.1.1, 203.0.113.9, 10.0.0.1");
        assertEquals("203.0.113.9", r.resolve(req), "必须跳过可信跳、拒绝采信左侧伪造值");
    }

    @Test
    @DisplayName("链上全是可信地址时，回落到 X-Real-IP")
    void fallsBackToRealIpWhenChainAllTrusted() {
        ClientIpResolver r = resolver("127.0.0.1,10.0.0.1");
        MockHttpServletRequest req = request("127.0.0.1");
        req.addHeader("X-Forwarded-For", "10.0.0.1");
        req.addHeader("X-Real-IP", "203.0.113.20");
        assertEquals("203.0.113.20", r.resolve(req));
    }

    // ===================== 非法输入 =====================

    @Test
    @DisplayName("非 IP 字面量（域名 / 注入字符）一律忽略，且不触发 DNS 解析")
    void ignoresNonIpLiteralEntries() {
        ClientIpResolver r = resolver("127.0.0.1");
        MockHttpServletRequest req = request("127.0.0.1");
        req.addHeader("X-Forwarded-For", "evil.example.com, \"><script>, 999.999.999.999, 203.0.113.5");
        assertEquals("203.0.113.5", r.resolve(req), "只认合法 IP 字面量，其余跳过");
    }

    @Test
    @DisplayName("XFF 里只有非法值 / unknown 时回落到 TCP 源地址")
    void fallsBackWhenAllEntriesInvalid() {
        ClientIpResolver r = resolver("127.0.0.1");
        MockHttpServletRequest req = request("127.0.0.1");
        req.addHeader("X-Forwarded-For", "unknown, not-an-ip");
        assertEquals("127.0.0.1", r.resolve(req));
    }

    @Test
    @DisplayName("request 为 null 时返回 unknown")
    void nullRequestIsSafe() {
        assertEquals(ClientIpResolver.UNKNOWN, resolver("127.0.0.1").resolve(null));
    }

    // ===================== CIDR 与 IPv6 =====================

    @Test
    @DisplayName("可信代理支持 CIDR（如容器网络 172.17.0.0/16）")
    void supportsCidrTrustedProxy() {
        ClientIpResolver r = resolver("172.17.0.0/16");
        MockHttpServletRequest req = request("172.17.0.5");
        req.addHeader("X-Forwarded-For", "203.0.113.9");
        assertEquals("203.0.113.9", r.resolve(req), "CIDR 内的来源应被视为可信代理");

        MockHttpServletRequest outside = request("172.18.0.5");
        outside.addHeader("X-Forwarded-For", "1.2.3.4");
        assertEquals("172.18.0.5", r.resolve(outside), "CIDR 外的来源不得采信 XFF");
    }

    @Test
    @DisplayName("IPv6 回环与方括号写法")
    void handlesIpv6() {
        ClientIpResolver r = resolver("::1");
        MockHttpServletRequest req = request("::1");
        req.addHeader("X-Forwarded-For", "[2001:db8::1]");
        assertEquals("2001:db8::1", r.resolve(req), "方括号应被剥离");
    }

    @Test
    @DisplayName("可信代理列表为空时完全忽略转发头")
    void emptyTrustedListIgnoresHeaders() {
        ClientIpResolver r = resolver("");
        MockHttpServletRequest req = request("127.0.0.1");
        req.addHeader("X-Forwarded-For", "203.0.113.9");
        assertEquals("127.0.0.1", r.resolve(req));
    }
}

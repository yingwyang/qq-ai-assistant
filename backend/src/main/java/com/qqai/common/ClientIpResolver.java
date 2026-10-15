package com.qqai.common;

import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.InetAddress;
import java.util.ArrayList;
import java.util.List;

/**
 * 客户端 IP 解析（限流 / 审计的口径统一入口）。
 *
 * <p><b>为什么必须统一</b>：{@code X-Forwarded-For} 是请求头，客户端可以随便伪造。若不加判断直接取它当
 * 「客户端 IP」，登录限流（按 IP+用户名 5 次/分）、注册限流（按 IP）就能被一行伪造头绕过——
 * 每次带不同的 XFF 即可无限尝试密码。</p>
 *
 * <p><b>正确做法</b>：只有当请求确实来自可信反向代理（{@code app.security.trusted-proxies}，
 * 默认仅本机回环）时，才采信转发头；否则一律用 {@code getRemoteAddr()}。
 * 采信时从右往左取第一个「非可信」地址——左侧的条目是客户端可伪造的，右侧才是代理逐跳追加的。</p>
 *
 * <p>纯 IP 字面量校验，不会触发 DNS 解析（避免被伪造值诱导做域名查询）。</p>
 */
@Component
public class ClientIpResolver {

    private static final Logger log = LoggerFactory.getLogger(ClientIpResolver.class);

    /** 无法确定时的兜底值（与历史实现一致，避免下游出现 null） */
    public static final String UNKNOWN = "unknown";

    private static final String IPV4_PATTERN = "^(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})$";
    /** IPv6 字面量：只含十六进制字符、冒号与点（含 IPv4 映射写法），不会匹配到域名 */
    private static final String IPV6_CHARS_PATTERN = "^[0-9A-Fa-f:.]+$";

    private final List<TrustedEntry> trustedProxies;

    public ClientIpResolver(@Value("${app.security.trusted-proxies:127.0.0.1,::1}") String rawProxies) {
        this.trustedProxies = parseTrusted(rawProxies);
        log.info("可信反向代理列表: {}", trustedProxies.isEmpty() ? "(空：忽略所有转发头)" : trustedProxies);
    }

    /**
     * 解析客户端 IP。
     *
     * @return 客户端 IP；无法判定时返回 {@link #UNKNOWN}（由调用方决定怎么用，不要拼成限流键以外的东西）
     */
    public String resolve(HttpServletRequest request) {
        if (request == null) {
            return UNKNOWN;
        }
        String remote = normalize(request.getRemoteAddr());
        if (!isTrusted(remote)) {
            // 直连或不可信来源：完全忽略转发头，否则限流键可被伪造绕过
            return remote != null ? remote : UNKNOWN;
        }

        String fromXff = rightmostUntrusted(normalizeXff(request.getHeader("X-Forwarded-For")));
        if (fromXff != null) {
            return fromXff;
        }

        String realIp = normalize(request.getHeader("X-Real-IP"));
        if (realIp != null && isValidIpLiteral(realIp) && !isTrusted(realIp)) {
            return realIp;
        }

        return remote != null ? remote : UNKNOWN;
    }

    /** 直连（非可信来源）时为 true，便于调用方判断当前是否处于代理之后 */
    public boolean isTrustedProxy(String ip) {
        return isTrusted(normalize(ip));
    }

    // ==================== 内部实现 ====================

    /**
     * 从 XFF 链右侧取第一个非可信地址。
     * 客户端可以随意在最左侧伪造任意多个条目，因此只能从右往左取。
     */
    private String rightmostUntrusted(List<String> chain) {
        for (int i = chain.size() - 1; i >= 0; i--) {
            String candidate = chain.get(i);
            if (!isValidIpLiteral(candidate)) {
                continue;
            }
            if (!isTrusted(candidate)) {
                return candidate;
            }
        }
        return null;
    }

    private List<String> normalizeXff(String header) {
        List<String> result = new ArrayList<>();
        if (header == null || header.isBlank()) {
            return result;
        }
        for (String part : header.split(",")) {
            String ip = normalize(part);
            if (ip != null) {
                result.add(ip);
            }
        }
        return result;
    }

    /** 去掉空白与 IPv6 方括号；"unknown" 与空值归一为 null */
    private String normalize(String value) {
        if (value == null) {
            return null;
        }
        String v = value.trim();
        if (v.isEmpty() || "unknown".equalsIgnoreCase(v)) {
            return null;
        }
        if (v.startsWith("[") && v.endsWith("]")) {
            v = v.substring(1, v.length() - 1);
        }
        return v.isEmpty() ? null : v;
    }

    private boolean isTrusted(String ip) {
        if (ip == null) {
            return false;
        }
        for (TrustedEntry entry : trustedProxies) {
            if (entry.matches(ip)) {
                return true;
            }
        }
        return false;
    }

    private List<TrustedEntry> parseTrusted(String raw) {
        List<TrustedEntry> entries = new ArrayList<>();
        if (raw == null || raw.isBlank()) {
            return entries;
        }
        for (String part : raw.split(",")) {
            String item = part.trim();
            if (item.isEmpty()) {
                continue;
            }
            TrustedEntry entry = TrustedEntry.parse(item);
            if (entry != null) {
                entries.add(entry);
            } else {
                log.warn("忽略无法解析的可信代理配置项: {}", item);
            }
        }
        return entries;
    }

    /** 严格 IP 字面量校验：不触发 DNS（只对匹配字面量形态的字符串才解析） */
    static boolean isValidIpLiteral(String value) {
        return parseIp(value) != null;
    }

    static byte[] parseIp(String value) {
        if (value == null || value.isEmpty()) {
            return null;
        }
        if (value.matches(IPV4_PATTERN)) {
            String[] octets = value.split("\\.");
            byte[] bytes = new byte[4];
            for (int i = 0; i < 4; i++) {
                int octet = Integer.parseInt(octets[i]);
                if (octet > 255) {
                    return null;
                }
                bytes[i] = (byte) octet;
            }
            return bytes;
        }
        if (value.contains(":") && value.matches(IPV6_CHARS_PATTERN)) {
            try {
                // 已确认是字面量形态（只有 0-9a-fA-F: .），getByName 不会走 DNS
                return InetAddress.getByName(value).getAddress();
            } catch (Exception e) {
                return null;
            }
        }
        return null;
    }

    /** 可信代理条目：精确 IP 或 CIDR */
    static final class TrustedEntry {
        private final String raw;
        private final byte[] network;
        private final int prefixLength;

        private TrustedEntry(String raw, byte[] network, int prefixLength) {
            this.raw = raw;
            this.network = network;
            this.prefixLength = prefixLength;
        }

        static TrustedEntry parse(String item) {
            int slash = item.indexOf('/');
            if (slash < 0) {
                byte[] ip = parseIp(item);
                return ip == null ? null : new TrustedEntry(item, ip, ip.length * 8);
            }
            String ipPart = item.substring(0, slash).trim();
            String prefixPart = item.substring(slash + 1).trim();
            byte[] ip = parseIp(ipPart);
            if (ip == null) {
                return null;
            }
            int prefix;
            try {
                prefix = Integer.parseInt(prefixPart);
            } catch (NumberFormatException e) {
                return null;
            }
            if (prefix < 0 || prefix > ip.length * 8) {
                return null;
            }
            return new TrustedEntry(item, ip, prefix);
        }

        boolean matches(String ip) {
            byte[] candidate = parseIp(ip);
            if (candidate == null || candidate.length != network.length) {
                return false;
            }
            int fullBytes = prefixLength / 8;
            for (int i = 0; i < fullBytes; i++) {
                if (candidate[i] != network[i]) {
                    return false;
                }
            }
            int remainingBits = prefixLength % 8;
            if (remainingBits == 0) {
                return true;
            }
            int mask = 0xFF << (8 - remainingBits) & 0xFF;
            return (candidate[fullBytes] & mask) == (network[fullBytes] & mask);
        }

        @Override
        public String toString() {
            return raw;
        }
    }
}

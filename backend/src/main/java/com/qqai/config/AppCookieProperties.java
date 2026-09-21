package com.qqai.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Cookie 安全配置。
 *
 * `app.cookie.secure=false`（默认）只适用于本地 HTTP 开发：此时浏览器会把 qqai_token
 * 明文发在 HTTP 上，同网络可被窃听。**生产 HTTPS 部署必须设置环境变量
 * `APP_COOKIE_SECURE=true`**，让 Cookie 带上 Secure 属性。
 */
@ConfigurationProperties(prefix = "app.cookie")
public class AppCookieProperties {

    /** true = Cookie 仅通过 HTTPS 传输（生产必须为 true） */
    private boolean secure = false;

    public boolean isSecure() {
        return secure;
    }

    public void setSecure(boolean secure) {
        this.secure = secure;
    }
}

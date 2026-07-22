package com.qqai.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app.registration")
public class AppRegistrationProperties {

    private boolean enabled = true;
    private int rateLimitPerIp = 5;
    private int rateLimitWindowMinutes = 1;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public int getRateLimitPerIp() {
        return rateLimitPerIp;
    }

    public void setRateLimitPerIp(int rateLimitPerIp) {
        this.rateLimitPerIp = rateLimitPerIp;
    }

    public int getRateLimitWindowMinutes() {
        return rateLimitWindowMinutes;
    }

    public void setRateLimitWindowMinutes(int rateLimitWindowMinutes) {
        this.rateLimitWindowMinutes = rateLimitWindowMinutes;
    }
}

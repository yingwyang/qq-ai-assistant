package com.qqai.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationStartedEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.stereotype.Component;

@Component
public class SecurityPropertiesValidator implements ApplicationListener<ApplicationStartedEvent> {

    @Value("${jwt.secret:}")
    private String jwtSecret;

    @Value("${napcat.token:}")
    private String napcatToken;

    @Value("${napcat.webhook-token:}")
    private String napcatWebhookToken;

    @Value("${minio.access-key:}")
    private String minioAccessKey;

    @Value("${minio.secret-key:}")
    private String minioSecretKey;

    @Override
    public void onApplicationEvent(ApplicationStartedEvent event) {
        validate();
    }

    public void validate() {
        if (jwtSecret == null || jwtSecret.isBlank() || jwtSecret.length() < 32) {
            throw new IllegalStateException(
                "Security configuration error: jwt.secret must be set and at least 32 characters long. Please configure via environment variable JWT_SECRET."
            );
        }

        if (napcatWebhookToken == null || napcatWebhookToken.isBlank()) {
            throw new IllegalStateException(
                "Security configuration error: napcat.webhook-token must be set. Please configure via environment variable NAPCAT_WEBHOOK_TOKEN."
            );
        }

        if (napcatToken == null || napcatToken.isBlank()) {
            throw new IllegalStateException(
                "Security configuration error: napcat.token must be set. Please configure via environment variable NAPCAT_TOKEN."
            );
        }

        if (minioAccessKey == null || minioAccessKey.isBlank() || minioSecretKey == null || minioSecretKey.isBlank()) {
            throw new IllegalStateException(
                "Security configuration error: minio.access-key and minio.secret-key must be set. Please configure via environment variables MINIO_ACCESS_KEY and MINIO_SECRET_KEY."
            );
        }
    }
}

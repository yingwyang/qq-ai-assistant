package com.qqai.capability;

/** 能力实现的健康状态（组件层与适配器共用）。 */
public record HealthStatus(boolean healthy, String detail) {
}

package com.qqai.dto.common;

import org.slf4j.MDC;

public record ApiErrorResponse(
        Integer code,
        String error,
        String message,
        String traceId,
        String path
) {

    public static ApiErrorResponse of(int code, String error, String message) {
        return new ApiErrorResponse(code, error, message, MDC.get("traceId"), null);
    }

    public static ApiErrorResponse of(int code, String error, String message, String path) {
        return new ApiErrorResponse(code, error, message, MDC.get("traceId"), path);
    }

    public static ApiErrorResponse internalError(String traceId) {
        return new ApiErrorResponse(500, "Internal Server Error", "服务器内部错误", traceId, null);
    }

    public static ApiErrorResponse internalError(String traceId, String path) {
        return new ApiErrorResponse(500, "Internal Server Error", "服务器内部错误", traceId, path);
    }
}

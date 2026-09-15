package com.qqai.dto.common;

import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.NON_NULL)
public class ApiResponse<T> {
    private int code;
    private String message;
    private T data;
    private String status;
    private String errorCode;
    private Object details;
    private String traceId;

    public ApiResponse() {
    }

    public ApiResponse(int code, String message, T data) {
        this.code = code;
        this.message = message;
        this.data = data;
    }

    public static <T> ApiResponse<T> success(T data) {
        ApiResponse<T> resp = new ApiResponse<>(200, "ok", data);
        resp.setStatus("ok");
        return resp;
    }

    public static <T> ApiResponse<T> success() {
        ApiResponse<T> resp = new ApiResponse<>(200, "ok", null);
        resp.setStatus("ok");
        return resp;
    }

    public static <T> ApiResponse<T> error(int code, String message) {
        ApiResponse<T> resp = new ApiResponse<>(code, message, null);
        resp.setStatus("error");
        return resp;
    }

    public static <T> ApiResponse<T> error(int code, String errorCode, String message) {
        ApiResponse<T> resp = new ApiResponse<>(code, message, null);
        resp.setStatus("error");
        resp.setErrorCode(errorCode);
        return resp;
    }

    public static <T> ApiResponse<T> error(int code, String errorCode, String message, Object details) {
        ApiResponse<T> resp = new ApiResponse<>(code, message, null);
        resp.setStatus("error");
        resp.setErrorCode(errorCode);
        resp.setDetails(details);
        return resp;
    }

    public static <T> ApiResponse<T> errorWithTrace(int code, String message, String traceId) {
        ApiResponse<T> resp = new ApiResponse<>(code, message, null);
        resp.setStatus("error");
        resp.setTraceId(traceId);
        return resp;
    }

    public static <T> ApiResponse<T> errorWithTrace(int code, String errorCode, String message, String traceId) {
        ApiResponse<T> resp = new ApiResponse<>(code, message, null);
        resp.setStatus("error");
        resp.setErrorCode(errorCode);
        resp.setTraceId(traceId);
        return resp;
    }

    public int getCode() {
        return code;
    }

    public void setCode(int code) {
        this.code = code;
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }

    public T getData() {
        return data;
    }

    public void setData(T data) {
        this.data = data;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getErrorCode() {
        return errorCode;
    }

    public void setErrorCode(String errorCode) {
        this.errorCode = errorCode;
    }

    public Object getDetails() {
        return details;
    }

    public void setDetails(Object details) {
        this.details = details;
    }

    public String getTraceId() {
        return traceId;
    }

    public void setTraceId(String traceId) {
        this.traceId = traceId;
    }
}

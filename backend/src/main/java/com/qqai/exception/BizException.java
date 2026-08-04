package com.qqai.exception;

public class BizException extends RuntimeException {
    private final int code;
    private final String errorCode;
    private final Object details;

    public BizException(int code, String message) {
        super(message);
        this.code = code;
        this.errorCode = null;
        this.details = null;
    }

    public BizException(String message) {
        super(message);
        this.code = 500;
        this.errorCode = null;
        this.details = null;
    }

    public BizException(int code, String errorCode, String message) {
        super(message);
        this.code = code;
        this.errorCode = errorCode;
        this.details = null;
    }

    public BizException(int code, String errorCode, String message, Object details) {
        super(message);
        this.code = code;
        this.errorCode = errorCode;
        this.details = details;
    }

    public BizException(String errorCode, String message) {
        super(message);
        this.code = 400;
        this.errorCode = errorCode;
        this.details = null;
    }

    public BizException(String errorCode, String message, Object details) {
        super(message);
        this.code = 400;
        this.errorCode = errorCode;
        this.details = details;
    }

    public int getCode() {
        return code;
    }

    public String getErrorCode() {
        return errorCode;
    }

    public Object getDetails() {
        return details;
    }
}

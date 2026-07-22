package com.qqai.exception;

public class UnauthorizedException extends BizException {
    public UnauthorizedException(String message) {
        super(401, message);
    }
}

package com.qqai.exception;

public final class CreditErrorCode {

    public static final String INSUFFICIENT_CREDITS = "INSUFFICIENT_CREDITS";
    public static final String ALREADY_SIGNED_IN = "ALREADY_SIGNED_IN";
    public static final String ORDER_NOT_FOUND = "ORDER_NOT_FOUND";
    public static final String ORDER_STATUS_INVALID = "ORDER_STATUS_INVALID";
    public static final String ALREADY_REFUNDED = "ALREADY_REFUNDED";
    public static final String FORBIDDEN_ORDER = "FORBIDDEN_ORDER";
    public static final String PLAN_NOT_FOUND = "PLAN_NOT_FOUND";
    public static final String ADMIN_REQUIRED = "ADMIN_REQUIRED";

    private CreditErrorCode() {}
}

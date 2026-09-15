package com.qqai.exception;

/**
 * 全局错误码枚举
 * 格式：模块前缀 + 三位数
 * - AUTH: 认证/授权
 * - VALID: 参数校验
 * - CREDIT: 积分
 * - ORDER: 订单
 * - MEDIA: 媒体
 * - SYSTEM: 系统
 */
public final class ErrorCode {

    // ========== 认证授权 (1xx) ==========
    public static final String UNAUTHORIZED = "AUTH_401";
    public static final String FORBIDDEN = "AUTH_403";
    public static final String TOKEN_EXPIRED = "AUTH_401_TOKEN_EXPIRED";
    public static final String TOKEN_INVALID = "AUTH_401_TOKEN_INVALID";
    public static final String ADMIN_REQUIRED = "AUTH_403_ADMIN_REQUIRED";

    // ========== 参数校验 (4xx) ==========
    public static final String VALIDATION_ERROR = "VALID_400";
    public static final String TYPE_MISMATCH = "VALID_400_TYPE";
    public static final String MISSING_PARAMETER = "VALID_400_MISSING";
    public static final String BAD_ARGUMENT = "VALID_400_ARG";
    public static final String NOT_FOUND = "VALID_404";
    public static final String METHOD_NOT_ALLOWED = "VALID_405";

    // ========== 积分模块 ==========
    public static final String INSUFFICIENT_CREDITS = "CREDIT_001";
    public static final String ALREADY_SIGNED_IN = "CREDIT_002";
    public static final String CREDIT_RULE_NOT_CONFIGURED = "CREDIT_003";
    public static final String SIGN_IN_DISABLED = "CREDIT_004";

    // ========== 订单模块 ==========
    public static final String ORDER_NOT_FOUND = "ORDER_001";
    public static final String ORDER_STATUS_INVALID = "ORDER_002";
    public static final String ALREADY_REFUNDED = "ORDER_003";
    public static final String FORBIDDEN_ORDER = "ORDER_004";
    public static final String PLAN_NOT_FOUND = "ORDER_005";
    public static final String SAME_TIER_ALREADY_ACTIVE = "ORDER_006";
    public static final String ALL_TIER_BLOCKS_MONTHLY = "ORDER_007";
    public static final String ORDER_EXPIRED = "ORDER_008";
    public static final String ORDER_DISPUTED = "ORDER_009";
    public static final String DUPLICATE_ORDER = "ORDER_010";

    // ========== 媒体模块 ==========
    public static final String FILE_TOO_LARGE = "MEDIA_413";
    public static final String UPLOAD_FAILED = "MEDIA_400";

    // ========== 系统 (5xx) ==========
    public static final String INTERNAL_ERROR = "SYSTEM_500";
    public static final String DATABASE_ERROR = "SYSTEM_500_DB";
    public static final String EXTERNAL_SERVICE_ERROR = "SYSTEM_502";

    private ErrorCode() {}
}

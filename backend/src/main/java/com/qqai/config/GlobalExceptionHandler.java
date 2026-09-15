package com.qqai.config;

import com.qqai.dto.common.ApiResponse;
import com.qqai.exception.BizException;
import com.qqai.exception.ForbiddenException;
import com.qqai.exception.NotFoundException;
import com.qqai.exception.UnauthorizedException;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwtException;
import jakarta.persistence.EntityNotFoundException;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.validation.BindException;
import org.springframework.validation.FieldError;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.MultipartException;

import java.util.NoSuchElementException;
import java.util.UUID;
import java.util.stream.Collectors;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);
    private static final String TRACE_ID_KEY = "traceId";

    private String ensureTraceId() {
        String traceId = MDC.get(TRACE_ID_KEY);
        if (traceId == null) {
            traceId = UUID.randomUUID().toString().replace("-", "").substring(0, 16);
            MDC.put(TRACE_ID_KEY, traceId);
        }
        return traceId;
    }

    // ==================== 400 Bad Request ====================

    @ExceptionHandler({MethodArgumentNotValidException.class, BindException.class})
    public ResponseEntity<ApiResponse<Void>> handleValidationException(Exception e,
                                                                      HttpServletRequest request) {
        String traceId = ensureTraceId();
        String message;
        if (e instanceof MethodArgumentNotValidException ex) {
            message = ex.getBindingResult().getFieldErrors().stream()
                    .map(FieldError::getDefaultMessage)
                    .collect(Collectors.joining(", "));
        } else {
            BindException ex = (BindException) e;
            message = ex.getBindingResult().getFieldErrors().stream()
                    .map(FieldError::getDefaultMessage)
                    .collect(Collectors.joining(", "));
        }
        if (message.isEmpty()) {
            message = "请求参数校验失败";
        }
        log.warn("[traceId={}] Validation error: {}", traceId, message);
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ApiResponse.errorWithTrace(400, "VALIDATION_ERROR", message, traceId));
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ApiResponse<Void>> handleTypeMismatch(MethodArgumentTypeMismatchException e,
                                                                 HttpServletRequest request) {
        String traceId = ensureTraceId();
        String msg = String.format("参数 '%s' 类型错误", e.getName());
        log.warn("[traceId={}] Type mismatch: {}", traceId, msg);
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ApiResponse.errorWithTrace(400, "TYPE_MISMATCH", msg, traceId));
    }

    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<ApiResponse<Void>> handleMissingParam(MissingServletRequestParameterException e,
                                                                 HttpServletRequest request) {
        String traceId = ensureTraceId();
        String msg = String.format("缺少必要参数: %s", e.getParameterName());
        log.warn("[traceId={}] Missing parameter: {}", traceId, msg);
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ApiResponse.errorWithTrace(400, "MISSING_PARAMETER", msg, traceId));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ApiResponse<Void>> handleIllegalArgumentException(IllegalArgumentException e,
                                                                           HttpServletRequest request) {
        String traceId = ensureTraceId();
        log.warn("[traceId={}] Illegal argument: {}", traceId, e.getMessage());
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ApiResponse.errorWithTrace(400, "BAD_ARGUMENT", e.getMessage(), traceId));
    }

    // ==================== 401 / 403 ====================

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ApiResponse<Void>> handleAccessDeniedException(AccessDeniedException e,
                                                                        HttpServletRequest request) {
        String traceId = ensureTraceId();
        log.warn("[traceId={}] Access denied: {}", traceId, e.getMessage());
        String path = request.getRequestURI();
        String errorCode = (path != null && path.startsWith("/api/credits/admin"))
                ? com.qqai.exception.CreditErrorCode.ADMIN_REQUIRED : "ACCESS_DENIED";
        String msg = errorCode.equals(com.qqai.exception.CreditErrorCode.ADMIN_REQUIRED)
                ? "需要管理员权限" : "访问被拒绝";
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(ApiResponse.errorWithTrace(403, errorCode, msg, traceId));
    }

    @ExceptionHandler({ExpiredJwtException.class, JwtException.class})
    public ResponseEntity<ApiResponse<Void>> handleJwtException(Exception e,
                                                               HttpServletRequest request) {
        String traceId = ensureTraceId();
        log.warn("[traceId={}] JWT error: {}", traceId, e.getMessage());
        String message = e instanceof ExpiredJwtException ? "Token已过期" : "无效的Token";
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(ApiResponse.errorWithTrace(401, "INVALID_TOKEN", message, traceId));
    }

    @ExceptionHandler(UnauthorizedException.class)
    public ResponseEntity<ApiResponse<Void>> handleUnauthorizedException(UnauthorizedException e,
                                                                        HttpServletRequest request) {
        String traceId = ensureTraceId();
        log.warn("[traceId={}] Unauthorized: {}", traceId, e.getMessage());
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(ApiResponse.errorWithTrace(401, "UNAUTHORIZED", e.getMessage(), traceId));
    }

    @ExceptionHandler(ForbiddenException.class)
    public ResponseEntity<ApiResponse<Void>> handleForbiddenException(ForbiddenException e,
                                                                     HttpServletRequest request) {
        String traceId = ensureTraceId();
        log.warn("[traceId={}] Forbidden: {}", traceId, e.getMessage());
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(ApiResponse.errorWithTrace(403, "FORBIDDEN", e.getMessage(), traceId));
    }

    // ==================== 404 / 405 ====================

    @ExceptionHandler({EntityNotFoundException.class, NoSuchElementException.class, NotFoundException.class})
    public ResponseEntity<ApiResponse<Void>> handleNotFoundException(Exception e,
                                                                    HttpServletRequest request) {
        String traceId = ensureTraceId();
        log.warn("[traceId={}] Not found: {}", traceId, e.getMessage());
        String msg = e.getMessage() != null ? e.getMessage() : "资源不存在";
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(ApiResponse.errorWithTrace(404, "NOT_FOUND", msg, traceId));
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ApiResponse<Void>> handleMethodNotSupported(HttpRequestMethodNotSupportedException e,
                                                                       HttpServletRequest request) {
        String traceId = ensureTraceId();
        String msg = String.format("不支持的请求方法: %s", e.getMethod());
        log.warn("[traceId={}] Method not supported: {}", traceId, msg);
        return ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED)
                .body(ApiResponse.errorWithTrace(405, "METHOD_NOT_ALLOWED", msg, traceId));
    }

    // ==================== 413 文件上传 ====================

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<ApiResponse<Void>> handleMaxUploadSizeExceeded(MaxUploadSizeExceededException e,
                                                                        HttpServletRequest request) {
        String traceId = ensureTraceId();
        log.warn("[traceId={}] Upload size exceeded: {}", traceId, e.getMessage());
        return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE)
                .body(ApiResponse.errorWithTrace(413, "FILE_TOO_LARGE", "文件大小超过限制", traceId));
    }

    @ExceptionHandler(MultipartException.class)
    public ResponseEntity<ApiResponse<Void>> handleMultipartException(MultipartException e,
                                                                     HttpServletRequest request) {
        String traceId = ensureTraceId();
        log.warn("[traceId={}] Multipart error: {}", traceId, e.getMessage());
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ApiResponse.errorWithTrace(400, "UPLOAD_FAILED", "文件上传失败", traceId));
    }

    // ==================== 业务异常 ====================

    @ExceptionHandler(BizException.class)
    public ResponseEntity<ApiResponse<Void>> handleBizException(BizException e,
                                                               HttpServletRequest request) {
        String traceId = ensureTraceId();
        log.warn("[traceId={}] Business exception [code={}, errorCode={}]: {}",
                traceId, e.getCode(), e.getErrorCode(), e.getMessage());
        HttpStatus status = HttpStatus.resolve(e.getCode());
        if (status == null) {
            status = HttpStatus.BAD_REQUEST;
        }
        if (e.getErrorCode() != null) {
            return ResponseEntity.status(status)
                    .body(ApiResponse.error(e.getCode(), e.getErrorCode(), e.getMessage(), e.getDetails()));
        }
        return ResponseEntity.status(status)
                .body(ApiResponse.errorWithTrace(e.getCode(), e.getMessage(), traceId));
    }

    // ==================== 数据库/系统异常 ====================

    @ExceptionHandler(DataAccessException.class)
    public ResponseEntity<ApiResponse<Void>> handleDataAccessException(DataAccessException e,
                                                                      HttpServletRequest request) {
        String traceId = ensureTraceId();
        log.error("[traceId={}] Data access exception", traceId, e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ApiResponse.errorWithTrace(500, "DATABASE_ERROR", "数据库操作异常", traceId));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> handleGenericException(Exception e,
                                                                   HttpServletRequest request) {
        String traceId = ensureTraceId();
        log.error("[traceId={}] Unhandled exception", traceId, e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ApiResponse.errorWithTrace(500, "INTERNAL_ERROR", "服务器内部错误", traceId));
    }
}

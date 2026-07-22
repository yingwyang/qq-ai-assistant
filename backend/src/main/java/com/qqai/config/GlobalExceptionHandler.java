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
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.validation.BindException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.MultipartException;

import java.util.NoSuchElementException;
import java.util.stream.Collectors;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);
    private static final String TRACE_ID_KEY = "traceId";

    @ExceptionHandler({MethodArgumentNotValidException.class, BindException.class})
    public ResponseEntity<ApiResponse<Void>> handleValidationException(Exception e,
                                                                      HttpServletRequest request) {
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
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(ApiResponse.error(400, message));
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ApiResponse<Void>> handleAccessDeniedException(AccessDeniedException e,
                                                                        HttpServletRequest request) {
        log.warn("Access denied: {}", e.getMessage());
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(ApiResponse.error(403, "访问被拒绝"));
    }

    @ExceptionHandler({ExpiredJwtException.class, JwtException.class})
    public ResponseEntity<ApiResponse<Void>> handleJwtException(Exception e,
                                                               HttpServletRequest request) {
        log.warn("JWT error: {}", e.getMessage());
        String message = e instanceof ExpiredJwtException ? "Token已过期" : "无效的Token";
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(ApiResponse.error(401, message));
    }

    @ExceptionHandler({EntityNotFoundException.class, NoSuchElementException.class})
    public ResponseEntity<ApiResponse<Void>> handleNotFoundException(Exception e,
                                                                    HttpServletRequest request) {
        log.warn("Not found: {}", e.getMessage());
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(ApiResponse.error(404, "资源不存在"));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ApiResponse<Void>> handleIllegalArgumentException(IllegalArgumentException e,
                                                                           HttpServletRequest request) {
        log.warn("Illegal argument: {}", e.getMessage());
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(ApiResponse.error(400, e.getMessage()));
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<ApiResponse<Void>> handleMaxUploadSizeExceeded(MaxUploadSizeExceededException e,
                                                                        HttpServletRequest request) {
        log.warn("Upload size exceeded: {}", e.getMessage());
        return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE).body(ApiResponse.error(413, "文件大小超过限制"));
    }

    @ExceptionHandler(MultipartException.class)
    public ResponseEntity<ApiResponse<Void>> handleMultipartException(MultipartException e,
                                                                     HttpServletRequest request) {
        log.warn("Multipart error: {}", e.getMessage());
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(ApiResponse.error(400, "文件上传失败"));
    }

    @ExceptionHandler(BizException.class)
    public ResponseEntity<ApiResponse<Void>> handleBizException(BizException e,
                                                               HttpServletRequest request) {
        log.warn("Business exception [code={}]: {}", e.getCode(), e.getMessage());
        HttpStatus status = HttpStatus.resolve(e.getCode());
        if (status == null) {
            status = HttpStatus.BAD_REQUEST;
        }
        return ResponseEntity.status(status).body(ApiResponse.error(e.getCode(), e.getMessage()));
    }

    @ExceptionHandler(NotFoundException.class)
    public ResponseEntity<ApiResponse<Void>> handleNotFoundException(NotFoundException e,
                                                                    HttpServletRequest request) {
        log.warn("Not found: {}", e.getMessage());
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(ApiResponse.error(404, e.getMessage()));
    }

    @ExceptionHandler(ForbiddenException.class)
    public ResponseEntity<ApiResponse<Void>> handleForbiddenException(ForbiddenException e,
                                                                     HttpServletRequest request) {
        log.warn("Forbidden: {}", e.getMessage());
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(ApiResponse.error(403, e.getMessage()));
    }

    @ExceptionHandler(UnauthorizedException.class)
    public ResponseEntity<ApiResponse<Void>> handleUnauthorizedException(UnauthorizedException e,
                                                                        HttpServletRequest request) {
        log.warn("Unauthorized: {}", e.getMessage());
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(ApiResponse.error(401, e.getMessage()));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> handleGenericException(Exception e,
                                                                   HttpServletRequest request) {
        String traceId = MDC.get(TRACE_ID_KEY);
        if (traceId == null) {
            traceId = java.util.UUID.randomUUID().toString();
        }
        log.error("Unhandled exception [traceId={}]", traceId, e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(ApiResponse.error(500, "服务器内部错误"));
    }
}

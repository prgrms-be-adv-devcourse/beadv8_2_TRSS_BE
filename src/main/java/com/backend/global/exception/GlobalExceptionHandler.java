package com.backend.global.exception;

import com.backend.global.rsData.RsData;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.util.List;
import java.util.Map;
import java.util.Objects;

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(DomainException.class)
    public ResponseEntity<RsData<Object>> handleDomain(DomainException e, HttpServletRequest req) {
        if (e.statusCode() >= 500) log.error("[{}] {} {}", e.getResultCode(), req.getMethod(), req.getRequestURI(), e);
        else log.info("[{}] {} {}", e.getResultCode(), req.getMethod(), req.getRequestURI());
        return toResponse(e.getResultCode(), e.getMsg(), e.getData());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<RsData<Object>> handleValidation(MethodArgumentNotValidException e) {
        List<Map<String, String>> fieldErrors = e.getBindingResult().getFieldErrors().stream()
                .map(f -> Map.of(
                        "field", f.getField(),
                        "rejectedValue", String.valueOf(f.getRejectedValue()),
                        "reason", Objects.requireNonNullElse(f.getDefaultMessage(), "")))
                .toList();
        return toResponse(GlobalErrorCode.VALIDATION_FAILED, Map.of("fieldErrors", fieldErrors));
    }

    @ExceptionHandler(HandlerMethodValidationException.class)   // @RequestParam·@PathVariable 검증
    public ResponseEntity<RsData<Object>> handleMethodValidation(HandlerMethodValidationException e) {
        return toResponse(GlobalErrorCode.VALIDATION_FAILED, null);
    }

    @ExceptionHandler({HttpMessageNotReadableException.class, MethodArgumentTypeMismatchException.class,
            MissingServletRequestParameterException.class, HttpMediaTypeNotSupportedException.class})
    public ResponseEntity<RsData<Object>> handleBadRequest(Exception e) {
        return toResponse(GlobalErrorCode.INVALID_REQUEST, null);
    }

    @ExceptionHandler(MissingRequestHeaderException.class)
    public ResponseEntity<RsData<Object>> handleMissingHeader(MissingRequestHeaderException e) {
        return toResponse(GlobalErrorCode.HEADER_REQUIRED, Map.of("header", e.getHeaderName()));
    }

    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<RsData<Object>> handleNoResource(NoResourceFoundException e) {
        return toResponse(GlobalErrorCode.RESOURCE_NOT_FOUND, null);
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<RsData<Object>> handleMethodNotAllowed(HttpRequestMethodNotSupportedException e) {
        return toResponse(GlobalErrorCode.METHOD_NOT_ALLOWED, null);
    }

    @ExceptionHandler(ObjectOptimisticLockingFailureException.class)
    public ResponseEntity<RsData<Object>> handleOptimisticLock(Exception e) {
        return toResponse(GlobalErrorCode.CONCURRENT_UPDATE, null);
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<RsData<Object>> handleIntegrity(DataIntegrityViolationException e, HttpServletRequest req) {
        // 서비스에서 먼저 중복을 검사하지 못한 경우의 안전망. 원인 SQL은 로그에만 남긴다.
        log.warn("DataIntegrityViolation {} {}", req.getRequestURI(), e.getMostSpecificCause().getMessage());
        return toResponse(GlobalErrorCode.DATA_CONFLICT, null);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<RsData<Object>> handleUnknown(Exception e, HttpServletRequest req) {
        log.error("Unhandled {} {}", req.getMethod(), req.getRequestURI(), e);
        return toResponse(GlobalErrorCode.INTERNAL_ERROR, null);
    }

    private ResponseEntity<RsData<Object>> toResponse(ErrorCode ec, Object data) {
        return toResponse(ec.resultCode(), ec.msg(), data);
    }

    private ResponseEntity<RsData<Object>> toResponse(String resultCode, String msg, Object data) {
        RsData<Object> body = RsData.of(resultCode, msg, data);
        return ResponseEntity.status(body.statusCode()).body(body);
    }
}

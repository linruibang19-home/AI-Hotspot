package com.aihotspot.core.api;

import com.aihotspot.core.config.CorrelationIdFilter;
import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class ApiExceptionHandler {

    @ExceptionHandler(ApiException.class)
    ProblemDetail apiException(ApiException exception, HttpServletRequest request) {
        return problem(exception.status(), exception.code(), exception.getMessage(), request);
    }

    @ExceptionHandler(DuplicateKeyException.class)
    ProblemDetail duplicate(DuplicateKeyException exception, HttpServletRequest request) {
        return problem(HttpStatus.CONFLICT, "DUPLICATE_RESOURCE", "对象已存在，请刷新后重试", request);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ProblemDetail validation(MethodArgumentNotValidException exception, HttpServletRequest request) {
        ProblemDetail detail = problem(HttpStatus.UNPROCESSABLE_ENTITY, "VALIDATION_FAILED", "提交内容不符合要求", request);
        Map<String, String> fields = new LinkedHashMap<>();
        exception.getBindingResult().getFieldErrors().forEach(error -> fields.putIfAbsent(error.getField(), error.getDefaultMessage()));
        detail.setProperty("fieldErrors", fields);
        return detail;
    }

    private ProblemDetail problem(HttpStatus status, String code, String message, HttpServletRequest request) {
        ProblemDetail detail = ProblemDetail.forStatusAndDetail(status, message);
        detail.setTitle(message);
        detail.setType(URI.create("https://aihotspot.local/problems/" + code.toLowerCase().replace('_', '-')));
        detail.setProperty("code", code);
        detail.setProperty("requestId", request.getAttribute(CorrelationIdFilter.REQUEST_ATTRIBUTE));
        return detail;
    }
}

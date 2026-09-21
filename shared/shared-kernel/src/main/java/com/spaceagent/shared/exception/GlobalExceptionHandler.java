package com.spaceagent.shared.exception;

import com.spaceagent.shared.api.ApiError;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.method.annotation.HandlerMethodValidationException;

import java.time.Instant;
import java.util.List;

@RestControllerAdvice
@Slf4j
public class GlobalExceptionHandler {

    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<ApiError> handleBusinessException(BusinessException exception) {
        ApiError error = new ApiError(
                exception.getCode(),
                exception.getMessage(),
                List.of(),
                Instant.now()
        );
        return ResponseEntity.status(exception.getStatus()).body(error);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiError> handleValidationException(MethodArgumentNotValidException exception) {
        List<String> details = exception.getBindingResult()
                .getFieldErrors()
                .stream()
                .map(this::formatFieldError)
                .toList();

        ApiError error = new ApiError(
                "VALIDATION_ERROR",
                "Request validation failed",
                details,
                Instant.now()
        );
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(error);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiError> handleMalformedJson(HttpMessageNotReadableException exception) {
        ApiError error = new ApiError(
                "MALFORMED_JSON",
                "Malformed JSON request",
                List.of(),
                Instant.now()
        );
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(error);
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ApiError> handleTypeMismatch(MethodArgumentTypeMismatchException exception) {
        ApiError error = new ApiError(
                "INVALID_PARAMETER",
                "Invalid parameter: " + exception.getName(),
                List.of(),
                Instant.now()
        );
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(error);
    }

    @ExceptionHandler(MissingRequestHeaderException.class)
    public ResponseEntity<ApiError> handleMissingHeader(MissingRequestHeaderException exception) {
        ApiError error = new ApiError(
                "INVALID_PARAMETER",
                "Missing required header: " + exception.getHeaderName(),
                List.of(),
                Instant.now()
        );
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(error);
    }

    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<ApiError> handleMissingParameter(MissingServletRequestParameterException exception) {
        return ResponseEntity.badRequest().body(new ApiError("INVALID_PARAMETER",
                "Missing required parameter: " + exception.getParameterName(), List.of(), Instant.now()));
    }

    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    public ResponseEntity<ApiError> handleUnsupportedMediaType(HttpMediaTypeNotSupportedException exception) {
        return ResponseEntity.status(HttpStatus.UNSUPPORTED_MEDIA_TYPE).body(new ApiError("UNSUPPORTED_MEDIA_TYPE",
                "Request content type is not supported", List.of(), Instant.now()));
    }

    @ExceptionHandler({ConstraintViolationException.class, HandlerMethodValidationException.class})
    public ResponseEntity<ApiError> handleConstraintViolation(Exception exception) {
        ApiError error = new ApiError(
                "VALIDATION_ERROR",
                "Request validation failed",
                List.of(),
                Instant.now()
        );
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(error);
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<ApiError> handleMaxUploadSizeExceeded(MaxUploadSizeExceededException exception) {
        ApiError error = new ApiError(
                "PAYLOAD_TOO_LARGE",
                "Uploaded file exceeds the configured size limit",
                List.of(),
                Instant.now()
        );
        return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE).body(error);
    }

    @ExceptionHandler(AuthenticationException.class)
    public ResponseEntity<ApiError> handleAuthentication(AuthenticationException exception) {
        ApiError error = new ApiError(
                "UNAUTHORIZED",
                exception.getMessage(),
                List.of(),
                Instant.now()
        );
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(error);
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ApiError> handleAccessDenied(AccessDeniedException exception) {
        ApiError error = new ApiError(
                "FORBIDDEN",
                exception.getMessage(),
                List.of(),
                Instant.now()
        );
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(error);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiError> handleUnexpected(
            Exception exception,
            HttpServletRequest request) {
        log.error(
                "Unexpected error: exceptionClass={}, correlationId={}",
                exception.getClass().getName(),
                correlationId(request));
        ApiError error = new ApiError(
                "INTERNAL_ERROR",
                "An unexpected error occurred",
                List.of(),
                Instant.now()
        );
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(error);
    }

    private String correlationId(HttpServletRequest request) {
        String value = request == null ? null : firstNonBlank(
                request.getHeader("X-Request-Id"),
                request.getHeader("X-Correlation-Id"));
        value = firstNonBlank(value, MDC.get("traceId"), MDC.get("requestId"));
        if (value == null) {
            return "unavailable";
        }
        String sanitized = value.replaceAll("[^A-Za-z0-9._:-]", "_");
        return sanitized.length() <= 120 ? sanitized : sanitized.substring(0, 120);
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }

    private String formatFieldError(FieldError error) {
        return error.getField() + ": " + error.getDefaultMessage();
    }
}

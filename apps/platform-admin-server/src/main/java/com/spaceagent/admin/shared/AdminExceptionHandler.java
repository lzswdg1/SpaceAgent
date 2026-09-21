package com.spaceagent.admin.shared;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class AdminExceptionHandler {
    @ExceptionHandler(AdminApiException.class)
    ResponseEntity<AdminApiResponse<Void>> handle(AdminApiException error) {
        return ResponseEntity.status(error.status()).body(AdminApiResponse.error(error.code(), error.getMessage()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<AdminApiResponse<Void>> validation(MethodArgumentNotValidException error) {
        return ResponseEntity.badRequest().body(AdminApiResponse.error(
                "ADMIN_VALIDATION_FAILED", "Administrator request validation failed"));
    }
}

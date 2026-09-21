package com.spaceagent.shared.exception;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import static org.junit.jupiter.api.Assertions.*;

class BusinessExceptionTest {

    @Test
    void defaultConstructor_usesBadRequest() {
        BusinessException ex = new BusinessException("test error");
        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatus());
        assertEquals("BUSINESS_ERROR", ex.getCode());
        assertEquals("test error", ex.getMessage());
    }

    @Test
    void constructorWithStatus() {
        BusinessException ex = new BusinessException("not found", HttpStatus.NOT_FOUND);
        assertEquals(HttpStatus.NOT_FOUND, ex.getStatus());
        assertEquals(404, ex.getStatus().value());
    }

    @Test
    void constructorWithStatusAndCode() {
        BusinessException ex = new BusinessException("forbidden", HttpStatus.FORBIDDEN, "ACCESS_DENIED");
        assertEquals(HttpStatus.FORBIDDEN, ex.getStatus());
        assertEquals("ACCESS_DENIED", ex.getCode());
    }
}

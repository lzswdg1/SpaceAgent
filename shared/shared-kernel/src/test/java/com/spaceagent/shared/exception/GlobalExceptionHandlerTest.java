package com.spaceagent.shared.exception;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.spaceagent.shared.api.ApiError;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.core.MethodParameter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class GlobalExceptionHandlerTest {

    @Test void missingQueryParameterAndUnsupportedContentTypeAreClientErrors() {
        var handler=new GlobalExceptionHandler();
        var missing=handler.handleMissingParameter(new org.springframework.web.bind.MissingServletRequestParameterException("spaceId","String"));
        assertEquals(HttpStatus.BAD_REQUEST,missing.getStatusCode());
        assertEquals("INVALID_PARAMETER",missing.getBody().code());
        var media=handler.handleUnsupportedMediaType(mock(org.springframework.web.HttpMediaTypeNotSupportedException.class));
        assertEquals(HttpStatus.UNSUPPORTED_MEDIA_TYPE,media.getStatusCode());
        assertEquals("UNSUPPORTED_MEDIA_TYPE",media.getBody().code());
    }

    @Test
    void maxUploadSizeReturnsActionablePayloadTooLargeResponse() {
        GlobalExceptionHandler handler = new GlobalExceptionHandler();

        ResponseEntity<ApiError> response = handler.handleMaxUploadSizeExceeded(
                new MaxUploadSizeExceededException(20L * 1024 * 1024)
        );

        assertEquals(HttpStatus.PAYLOAD_TOO_LARGE, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals("PAYLOAD_TOO_LARGE", response.getBody().code());
    }

    @Test
    void malformedJsonReturnsStableBadRequestWithoutParserDetails() {
        GlobalExceptionHandler handler = new GlobalExceptionHandler();

        ResponseEntity<ApiError> response = handler.handleMalformedJson(
                mock(HttpMessageNotReadableException.class));

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals("MALFORMED_JSON", response.getBody().code());
        assertEquals("Malformed JSON request", response.getBody().message());
    }

    @Test
    void missingRequiredHeaderReturnsStableBadRequest() {
        GlobalExceptionHandler handler = new GlobalExceptionHandler();

        ResponseEntity<ApiError> response = handler.handleMissingHeader(
                new MissingRequestHeaderException(
                        "Idempotency-Key", mock(MethodParameter.class)));

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals("INVALID_PARAMETER", response.getBody().code());
        assertEquals("Missing required header: Idempotency-Key", response.getBody().message());
    }

    @Test
    void unexpectedErrorsLogOnlyExceptionClassAndCorrelationId() {
        GlobalExceptionHandler handler = new GlobalExceptionHandler();
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getHeader("X-Request-Id")).thenReturn("request-correlation-123");
        String sensitiveMessage = "jdbc:postgresql://db/app?password=jdbc-password "
                + "apiSecret=provider-api-secret token=token-like-value-123456 "
                + "providerUrl=https://provider.internal/v1";
        Logger logger = (Logger) LoggerFactory.getLogger(GlobalExceptionHandler.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);

        ResponseEntity<ApiError> response;
        try {
            response = handler.handleUnexpected(
                    new IllegalStateException(sensitiveMessage), request);
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }

        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals("An unexpected error occurred", response.getBody().message());
        assertEquals(1, appender.list.size());
        ILoggingEvent event = appender.list.get(0);
        String logged = event.getFormattedMessage();
        assertTrue(logged.contains(IllegalStateException.class.getName()));
        assertTrue(logged.contains("request-correlation-123"));
        assertFalse(logged.contains("jdbc-password"));
        assertFalse(logged.contains("provider-api-secret"));
        assertFalse(logged.contains("token-like-value-123456"));
        assertFalse(logged.contains("jdbc:postgresql"));
        assertFalse(logged.contains("provider.internal"));
        assertNull(event.getThrowableProxy());
    }
}

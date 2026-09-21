package com.spaceagent.platform.integration.infrastructure;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

/** Rejects oversized JSON before Jackson can materialize it in heap. */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class PlatformJsonBodyLimitFilter extends OncePerRequestFilter {
    static final long MAX_JSON_BYTES = 2_000_000;

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String contentType = request.getContentType();
        return contentType == null || !contentType.toLowerCase(java.util.Locale.ROOT)
                .startsWith("application/json");
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {
        if (request.getContentLengthLong() > MAX_JSON_BYTES) {
            reject(response);
            return;
        }
        try {
            filterChain.doFilter(new LimitedRequest(request), response);
        } catch (PayloadLimitException error) {
            if (!response.isCommitted()) reject(response);
        }
    }

    private static void reject(HttpServletResponse response) throws IOException {
        response.resetBuffer();
        response.setStatus(HttpServletResponse.SC_REQUEST_ENTITY_TOO_LARGE);
        response.setContentType("application/json");
        response.getWriter().write(
                "{\"success\":false,\"code\":\"PAYLOAD_TOO_LARGE\","
                        + "\"message\":\"JSON request exceeds 2000000 bytes\"}");
    }

    private static final class LimitedRequest extends HttpServletRequestWrapper {
        private LimitedRequest(HttpServletRequest request) {
            super(request);
        }

        @Override
        public ServletInputStream getInputStream() throws IOException {
            ServletInputStream delegate = super.getInputStream();
            return new ServletInputStream() {
                private long consumed;

                @Override
                public int read() throws IOException {
                    int value = delegate.read();
                    if (value >= 0 && ++consumed > MAX_JSON_BYTES) throw new PayloadLimitException();
                    return value;
                }

                @Override
                public int read(byte[] bytes, int offset, int length) throws IOException {
                    int value = delegate.read(bytes, offset, length);
                    if (value > 0 && (consumed += value) > MAX_JSON_BYTES) {
                        throw new PayloadLimitException();
                    }
                    return value;
                }

                @Override
                public boolean isFinished() {
                    return delegate.isFinished();
                }

                @Override
                public boolean isReady() {
                    return delegate.isReady();
                }

                @Override
                public void setReadListener(ReadListener readListener) {
                    delegate.setReadListener(readListener);
                }
            };
        }

        @Override
        public BufferedReader getReader() throws IOException {
            return new BufferedReader(new InputStreamReader(getInputStream(), StandardCharsets.UTF_8));
        }
    }

    private static final class PayloadLimitException extends IOException {
    }
}

package com.spaceagent.platform.tooling.application;

import com.spaceagent.platform.tooling.api.SecureUrlFetchApplicationApi;
import com.spaceagent.platform.tooling.infrastructure.BoundedPublicHttpClient;
import com.spaceagent.shared.exception.BusinessException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

@Service
public class SecureUrlFetchApplicationService implements SecureUrlFetchApplicationApi {
    private static final Set<String> APPLICATION_TYPES = Set.of(
            "json", "xml", "xhtml+xml", "rss+xml", "atom+xml", "pdf");
    private final BoundedPublicHttpClient http;

    public SecureUrlFetchApplicationService(BoundedPublicHttpClient http) {
        this.http = http;
    }

    @Override
    public FetchResult fetch(FetchCommand command) {
        validate(command);
        URI current = uri(command.normalizedUrl());
        Map<String, String> headers = conditionalHeaders(command);
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(45);
        for (int redirects = 0; redirects <= command.maximumRedirects(); redirects++) {
            BoundedPublicHttpClient.Response response;
            try {
                response = http.getRaw(current, headers, command.maximumBytes(), java.time.Duration.ofNanos(deadline - System.nanoTime()));
            } catch (RuntimeException error) {
                throw failed("KNOWLEDGE_URL_FETCH_FAILED", HttpStatus.BAD_GATEWAY);
            }
            int status = response.status();
            if (redirect(status)) {
                if (redirects == command.maximumRedirects()) {
                    throw failed("KNOWLEDGE_URL_REDIRECT_LIMIT", HttpStatus.BAD_GATEWAY);
                }
                String location = response.headers().getFirst(HttpHeaders.LOCATION);
                if (location == null || location.isBlank() || location.length() > 2048) {
                    throw failed("KNOWLEDGE_URL_REDIRECT_INVALID", HttpStatus.BAD_GATEWAY);
                }
                current = uri(current.resolve(location).normalize().toString());
                headers = Map.of();
                continue;
            }
            if (status == 304) {
                if (response.body().length != 0) {
                    throw failed("KNOWLEDGE_URL_NOT_MODIFIED_INVALID", HttpStatus.BAD_GATEWAY);
                }
                return result(response, null, StandardCharsets.UTF_8, redirects);
            }
            if (status < 200 || status >= 300) {
                throw failed("KNOWLEDGE_URL_HTTP_STATUS", HttpStatus.BAD_GATEWAY);
            }
            MediaType mediaType = response.mediaType();
            if (!supported(mediaType)) {
                throw failed("KNOWLEDGE_URL_MIME_UNSUPPORTED", HttpStatus.UNSUPPORTED_MEDIA_TYPE);
            }
            Charset charset = mediaType.getCharset() == null
                    ? StandardCharsets.UTF_8 : mediaType.getCharset();
            if (!Set.of(StandardCharsets.UTF_8, StandardCharsets.ISO_8859_1,
                    StandardCharsets.UTF_16, StandardCharsets.UTF_16BE,
                    StandardCharsets.UTF_16LE).contains(charset)) {
                throw failed("KNOWLEDGE_URL_CHARSET_UNSUPPORTED", HttpStatus.UNSUPPORTED_MEDIA_TYPE);
            }
            return result(response, mediaType, charset, redirects);
        }
        throw failed("KNOWLEDGE_URL_REDIRECT_LIMIT", HttpStatus.BAD_GATEWAY);
    }

    private static FetchResult result(
            BoundedPublicHttpClient.Response response,
            MediaType mediaType,
            Charset charset,
            int redirects) {
        return new FetchResult(response.uri().toASCIIString(), response.status(),
                mediaType == null ? null : mediaType.toString(), charset.name(),
                bounded(response.headers().getFirst(HttpHeaders.ETAG), 500),
                bounded(response.headers().getFirst(HttpHeaders.LAST_MODIFIED), 200),
                response.body(), redirects);
    }

    private static Map<String, String> conditionalHeaders(FetchCommand command) {
        Map<String, String> result = new LinkedHashMap<>();
        if (validHeader(command.etag(), 500)) result.put(HttpHeaders.IF_NONE_MATCH, command.etag());
        if (validHeader(command.lastModified(), 200)) {
            result.put(HttpHeaders.IF_MODIFIED_SINCE, command.lastModified());
        }
        return Map.copyOf(result);
    }

    private static boolean supported(MediaType value) {
        if (value == null) return false;
        String type = value.getType().toLowerCase(Locale.ROOT);
        String subtype = value.getSubtype().toLowerCase(Locale.ROOT);
        return "text".equals(type) && Set.of("plain", "html", "markdown", "csv").contains(subtype)
                || "application".equals(type) && APPLICATION_TYPES.contains(subtype);
    }

    private static boolean redirect(int status) {
        return status == 301 || status == 302 || status == 303
                || status == 307 || status == 308;
    }

    private static URI uri(String value) {
        try {
            URI uri = URI.create(value);
            if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null
                    || uri.getUserInfo() != null || uri.getFragment() != null
                    || value.length() > 2048) throw new IllegalArgumentException();
            return uri;
        } catch (RuntimeException error) {
            throw failed("KNOWLEDGE_URL_INVALID", HttpStatus.BAD_REQUEST);
        }
    }

    private static void validate(FetchCommand value) {
        if (value == null || value.maximumRedirects() < 0 || value.maximumRedirects() > 5
                || value.maximumBytes() < 1 || value.maximumBytes() > 10_000_000
                || value.etag() != null && !validHeader(value.etag(), 500)
                || value.lastModified() != null && !validHeader(value.lastModified(), 200)) {
            throw failed("KNOWLEDGE_URL_FETCH_INPUT_INVALID", HttpStatus.BAD_REQUEST);
        }
    }

    private static boolean validHeader(String value, int maximum) {
        return value != null && !value.isBlank() && value.length() <= maximum
                && !value.contains("\r") && !value.contains("\n");
    }

    private static String bounded(String value, int maximum) {
        return validHeader(value, maximum) ? value : null;
    }

    private static BusinessException failed(String code, HttpStatus status) {
        return new BusinessException("Knowledge URL fetch was rejected", status, code);
    }
}

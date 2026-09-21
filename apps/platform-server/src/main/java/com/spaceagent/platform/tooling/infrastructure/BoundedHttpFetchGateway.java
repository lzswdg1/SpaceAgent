package com.spaceagent.platform.tooling.infrastructure;

import com.spaceagent.platform.tooling.domain.HttpFetchGateway;
import org.jsoup.Jsoup;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Set;

@Component
public class BoundedHttpFetchGateway implements HttpFetchGateway {
    private static final Set<String> APPLICATION_TYPES = Set.of(
            "json", "xml", "xhtml+xml", "rss+xml", "atom+xml");
    private final BoundedPublicHttpClient http;

    public BoundedHttpFetchGateway(BoundedPublicHttpClient http) {
        this.http = http;
    }

    @Override
    public FetchResult fetch(String url, int maxCharacters) {
        int characterLimit = Math.max(1_000, Math.min(maxCharacters, 200_000));
        BoundedPublicHttpClient.Response response = http.get(
                URI.create(url), Map.of(), 1_000_000);
        if (response.status() < 200 || response.status() >= 300) {
            throw new IllegalStateException("HTTP Fetch returned status " + response.status());
        }
        MediaType mediaType = response.mediaType();
        if (!supported(mediaType)) {
            throw new IllegalStateException("HTTP Fetch content type is not supported");
        }
        Charset charset = mediaType != null && mediaType.getCharset() != null
                ? mediaType.getCharset() : StandardCharsets.UTF_8;
        String raw = new String(response.body(), charset);
        boolean html = mediaType != null && (mediaType.isCompatibleWith(MediaType.TEXT_HTML)
                || "xhtml+xml".equalsIgnoreCase(mediaType.getSubtype()));
        String title = html ? Jsoup.parse(raw).title() : "";
        String content = html ? Jsoup.parse(raw).text() : raw;
        content = controls(content);
        boolean truncated = content.length() > characterLimit;
        if (truncated) content = content.substring(0, characterLimit);
        return new FetchResult(
                response.uri().toString(), response.status(),
                mediaType == null ? "application/octet-stream" : mediaType.toString(),
                bound(title, 500), content, truncated);
    }

    private static boolean supported(MediaType mediaType) {
        if (mediaType == null) return false;
        return "text".equalsIgnoreCase(mediaType.getType())
                || ("application".equalsIgnoreCase(mediaType.getType())
                && APPLICATION_TYPES.contains(mediaType.getSubtype().toLowerCase()));
    }

    private static String controls(String value) {
        return value.replaceAll("[\\p{Cntrl}&&[^\\r\\n\\t]]", " ").trim();
    }

    private static String bound(String value, int max) {
        String normalized = controls(value == null ? "" : value);
        return normalized.substring(0, Math.min(normalized.length(), max));
    }
}

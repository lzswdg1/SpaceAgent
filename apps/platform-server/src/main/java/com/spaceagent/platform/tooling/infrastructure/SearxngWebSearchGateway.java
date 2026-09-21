package com.spaceagent.platform.tooling.infrastructure;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.spaceagent.platform.tooling.domain.McpRemoteEndpointPolicy;
import com.spaceagent.platform.tooling.domain.WebSearchGateway;
import org.jsoup.Jsoup;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

@Component
@ConditionalOnProperty(
        prefix = "platform.tooling.web-search",
        name = "mode",
        havingValue = "searxng")
public class SearxngWebSearchGateway implements WebSearchGateway {
    private final URI baseUri;
    private final BoundedPublicHttpClient http;
    private final Function<String, URI> endpointValidator;
    private final ObjectMapper json;

    public SearxngWebSearchGateway(
            WebSearchProperties properties,
            BoundedPublicHttpClient http,
            McpRemoteEndpointPolicy endpointPolicy,
            ObjectMapper json) {
        this.baseUri = endpointPolicy.validate(required(properties.getBaseUrl()));
        this.http = http;
        this.endpointValidator = endpointPolicy::validate;
        this.json = json;
    }

    @Autowired
    public SearxngWebSearchGateway(
            WebSearchProperties properties,
            BoundedPublicHttpClient http,
            PublicEndpointResolver endpointResolver,
            ObjectMapper json) {
        this.baseUri = endpointResolver.resolve(required(properties.getBaseUrl())).uri();
        this.http = http;
        this.endpointValidator = value -> endpointResolver.resolve(value).uri();
        this.json = json;
    }

    @Override
    public boolean available() {
        return true;
    }

    @Override
    public SearchResult search(SearchRequest request) {
        int limit = Math.max(1, Math.min(request.maxResults(), 10));
        UriComponentsBuilder uri = UriComponentsBuilder.fromUri(baseUri)
                .path(isSearchEndpoint(baseUri.getPath())
                        ? "" : "/search")
                .replaceQueryParam("q", request.query())
                .queryParam("format", "json")
                .queryParam("pageno", 1)
                .queryParam("safesearch", 1);
        if (request.language() != null && !request.language().isBlank()) {
            uri.queryParam("language", request.language());
        }
        if (request.categories() != null && !request.categories().isBlank()) {
            uri.queryParam("categories", request.categories());
        }
        if (request.timeRange() != null && !request.timeRange().isBlank()) {
            uri.queryParam("time_range", request.timeRange());
        }
        BoundedPublicHttpClient.Response response = http.get(
                uri.build().encode().toUri(), Map.of("Accept", "application/json"), 1_000_000);
        if (response.status() != 200) {
            throw new IllegalStateException("SearXNG returned status " + response.status());
        }
        try {
            JsonNode root = json.readTree(response.body());
            JsonNode results = root.path("results");
            if (!results.isArray() || results.size() > 500) {
                throw new IllegalStateException("SearXNG response is invalid");
            }
            List<SearchHit> hits = new ArrayList<>();
            int candidates = Math.min(results.size(), 50);
            for (int index = 0; index < candidates; index++) {
                if (hits.size() >= limit) break;
                JsonNode result = results.get(index);
                try {
                    URI resultUri = endpointValidator.apply(result.path("url").asText());
                    hits.add(new SearchHit(
                            text(result.path("title").asText(), 500),
                            resultUri.toString(),
                            text(Jsoup.parse(result.path("content").asText()).text(), 2_000),
                            text(result.path("engine").asText(), 100),
                            result.path("score").asDouble(0.0),
                            text(result.path("publishedDate").asText(), 100)));
                } catch (RuntimeException ignored) {
                    // Invalid or private-network result URLs are omitted.
                }
            }
            return new SearchResult(request.query(), hits);
        } catch (RuntimeException error) {
            throw error;
        } catch (Exception error) {
            throw new IllegalStateException("SearXNG response is not valid JSON");
        }
    }

    private static String required(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("SearXNG base URL is required");
        }
        return value.trim();
    }

    private static boolean isSearchEndpoint(String path) {
        if (path == null) return false;
        String normalized = path.endsWith("/")
                ? path.substring(0, path.length() - 1) : path;
        return normalized.endsWith("/search");
    }

    private static String text(String value, int max) {
        String normalized = value == null ? "" : value
                .replaceAll("[\\p{Cntrl}&&[^\\r\\n\\t]]", " ").trim();
        return normalized.substring(0, Math.min(normalized.length(), max));
    }
}

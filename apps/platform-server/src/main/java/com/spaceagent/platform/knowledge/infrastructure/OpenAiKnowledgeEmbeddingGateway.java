package com.spaceagent.platform.knowledge.infrastructure;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.spaceagent.platform.knowledge.domain.KnowledgeEmbeddingGateway;
import com.spaceagent.shared.exception.BusinessException;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.client.JdkClientHttpRequestFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.io.InputStream;
import java.net.http.HttpClient;
import java.time.Duration;

@Component
@ConditionalOnProperty(
        prefix = "platform.knowledge",
        name = "embedding-mode",
        havingValue = "http",
        matchIfMissing = true)
public class OpenAiKnowledgeEmbeddingGateway implements KnowledgeEmbeddingGateway {

    private final KnowledgeProperties properties;
    private final ObjectMapper objectMapper;
    private final RestClient client;
    @org.springframework.beans.factory.annotation.Value("${platform.knowledge.legacy-mode:compatibility}")
    private String legacyMode="compatibility";

    @Autowired
    public OpenAiKnowledgeEmbeddingGateway(
            KnowledgeProperties properties,
            ObjectMapper objectMapper,
            RestClient.Builder builder) {
        this(properties, objectMapper, bounded(properties, builder));
    }

    public OpenAiKnowledgeEmbeddingGateway(
            KnowledgeProperties properties,
            ObjectMapper objectMapper,
            RestClient client) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.client = client;
    }

    @Override
    public EmbeddingBatch embed(List<String> inputs) {
        if(!"compatibility".equals(legacyMode))throw new BusinessException("Legacy global embedding is disabled",HttpStatus.CONFLICT,"KNOWLEDGE_LEGACY_MIGRATION_REQUIRED");
        if (properties.getEmbeddingApiKey() == null || properties.getEmbeddingApiKey().isBlank()) {
            throw new BusinessException(
                    "Knowledge embedding API key is not configured",
                    HttpStatus.SERVICE_UNAVAILABLE,
                    "KNOWLEDGE_EMBEDDING_NOT_CONFIGURED");
        }
        try {
            byte[] response = client.post()
                    .uri("embeddings")
                    .body(Map.of("model", properties.getEmbeddingModel(), "input", inputs))
                    .exchange((request, result) -> {
                        if (!result.getStatusCode().is2xxSuccessful()) {
                            throw new IllegalStateException("Embedding provider returned non-success status");
                        }
                        try (InputStream input = result.getBody()) {
                            byte[] body = input.readNBytes(4_000_001);
                            if (body.length > 4_000_000) {
                                throw new IllegalStateException("Embedding response exceeds limit");
                            }
                            return body;
                        }
                    });
            JsonNode root = objectMapper.readTree(response == null ? new byte[0] : response);
            List<List<Double>> vectors = new ArrayList<>();
            for (JsonNode item : root.path("data")) {
                List<Double> vector = new ArrayList<>();
                item.path("embedding").forEach(value -> vector.add(value.asDouble()));
                vectors.add(vector);
            }
            if (vectors.size() != inputs.size()) {
                throw new IllegalStateException("Embedding response size mismatch");
            }
            return new EmbeddingBatch(properties.getEmbeddingModel(), vectors);
        } catch (RestClientException exception) {
            throw new BusinessException(
                    "Knowledge embedding request failed",
                    HttpStatus.BAD_GATEWAY,
                    "KNOWLEDGE_EMBEDDING_UNAVAILABLE");
        } catch (BusinessException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new BusinessException(
                    "Knowledge embedding response is invalid",
                    HttpStatus.BAD_GATEWAY,
                    "KNOWLEDGE_EMBEDDING_RESPONSE_INVALID");
        }
    }

    private static RestClient bounded(KnowledgeProperties properties, RestClient.Builder builder) {
        HttpClient http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(http);
        factory.setReadTimeout(Duration.ofSeconds(30));
        return builder.clone()
                .baseUrl(normalizeBase(properties.getEmbeddingBaseUrl()))
                .defaultHeader(HttpHeaders.AUTHORIZATION,
                        "Bearer " + properties.getEmbeddingApiKey())
                .requestFactory(factory)
                .build();
    }

    private static String normalizeBase(String baseUrl) {
        return baseUrl.endsWith("/") ? baseUrl : baseUrl + "/";
    }
}

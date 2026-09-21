package com.spaceagent.platform.inference.infrastructure;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.spaceagent.platform.inference.domain.ModelProvider;
import com.spaceagent.platform.inference.domain.ModelProviderConnectionTester;
import com.spaceagent.platform.inference.domain.ModelProviderSecretCipher;
import com.spaceagent.platform.inference.domain.ProviderConnectionProbeResult;
import org.springframework.http.HttpHeaders;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Bounded OpenAI-compatible GET /models connectivity probe. */
@Component
public class OpenAiCompatibleProviderConnectionTester implements ModelProviderConnectionTester {

    private final ModelProviderSecretCipher secretCipher;
    private final ObjectMapper objectMapper;
    private final InferenceProperties properties;

    public OpenAiCompatibleProviderConnectionTester(
            ModelProviderSecretCipher secretCipher,
            ObjectMapper objectMapper,
            InferenceProperties properties) {
        this.secretCipher = secretCipher;
        this.objectMapper = objectMapper;
        this.properties = properties;
    }

    @Override
    public ProviderConnectionProbeResult test(ModelProvider provider) {
        long started = System.nanoTime();
        RestClient client;
        try {
            client = createClient(provider);
        } catch (IllegalArgumentException exception) {
            return failure(started, "PROVIDER_AUTH_TYPE_UNSUPPORTED");
        } catch (RuntimeException exception) {
            return failure(started, "PROVIDER_CLIENT_CONFIGURATION_FAILED");
        }
        try {
            String response = client.get().uri("models").retrieve().body(String.class);
            JsonNode root = objectMapper.readTree(response == null ? "{}" : response);
            if (!root.path("data").isArray()) {
                return failure(started, "PROVIDER_RESPONSE_INVALID");
            }
            List<String> modelIds = new ArrayList<>();
            root.path("data").forEach(model -> {
                String id = model.path("id").asText("").trim();
                if (!id.isEmpty() && !modelIds.contains(id)) {
                    modelIds.add(id);
                }
            });
            return new ProviderConnectionProbeResult(
                    true, latencyMs(started), List.copyOf(modelIds), null);
        } catch (RestClientResponseException exception) {
            int status = exception.getStatusCode().value();
            if (status == 401 || status == 403) {
                return failure(started, "PROVIDER_AUTH_FAILED");
            }
            if (status == 404) {
                return failure(started, "PROVIDER_MODELS_ENDPOINT_NOT_FOUND");
            }
            return failure(started, status >= 500
                    ? "PROVIDER_UNAVAILABLE"
                    : "PROVIDER_REQUEST_REJECTED");
        } catch (RestClientException exception) {
            return failure(started, "PROVIDER_CONNECTION_FAILED");
        } catch (Exception exception) {
            return failure(started, "PROVIDER_RESPONSE_INVALID");
        }
    }

    private RestClient createClient(ModelProvider provider) {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(Duration.ofSeconds(properties.getConnectTimeoutSeconds()));
        requestFactory.setReadTimeout(Duration.ofSeconds(properties.getConnectionTestTimeoutSeconds()));
        RestClient.Builder builder = RestClient.builder()
                .baseUrl(normalizeBaseUrl(provider.baseUrl()))
                .requestFactory(requestFactory);
        String authType = provider.authType() == null
                ? "bearer"
                : provider.authType().trim().toLowerCase(Locale.ROOT);
        if ("none".equals(authType)) {
            return builder.build();
        }
        String apiKey = secretCipher.decrypt(provider.encryptedApiKey());
        if ("bearer".equals(authType)) {
            return builder.defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey).build();
        }
        if ("x-api-key".equals(authType) || "api-key".equals(authType)) {
            return builder.defaultHeader("X-API-Key", apiKey).build();
        }
        throw new IllegalArgumentException("unsupported auth type");
    }

    private static ProviderConnectionProbeResult failure(long started, String errorCode) {
        return new ProviderConnectionProbeResult(
                false, latencyMs(started), List.of(), errorCode);
    }

    private static int latencyMs(long started) {
        long elapsed = Math.max(0L, (System.nanoTime() - started) / 1_000_000L);
        return (int) Math.min(Integer.MAX_VALUE, elapsed);
    }

    private static String normalizeBaseUrl(String value) {
        return value.endsWith("/") ? value : value + "/";
    }
}

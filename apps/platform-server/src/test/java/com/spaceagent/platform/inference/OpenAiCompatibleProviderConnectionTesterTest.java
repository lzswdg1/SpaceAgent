package com.spaceagent.platform.inference;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.spaceagent.platform.inference.domain.ModelProvider;
import com.spaceagent.platform.inference.domain.ModelProviderSecretCipher;
import com.spaceagent.platform.inference.infrastructure.InferenceProperties;
import com.spaceagent.platform.inference.infrastructure.OpenAiCompatibleProviderConnectionTester;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OpenAiCompatibleProviderConnectionTesterTest {

    @Test
    void probeUsesConfiguredSecretParsesModelsAndReturnsSafeFailureCodes() throws Exception {
        AtomicInteger status = new AtomicInteger(200);
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/models", exchange -> {
            boolean authorized = "Bearer provider-secret".equals(
                    exchange.getRequestHeaders().getFirst("Authorization"));
            int responseStatus = authorized ? status.get() : 401;
            byte[] body = (responseStatus == 200
                    ? "{\"data\":[{\"id\":\"model-a\"},{\"id\":\"model-b\"}]}"
                    : "{\"error\":\"secret details must not escape\"}")
                    .getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(responseStatus, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        try {
            InferenceProperties properties = new InferenceProperties();
            properties.setConnectTimeoutSeconds(2);
            properties.setConnectionTestTimeoutSeconds(2);
            OpenAiCompatibleProviderConnectionTester tester =
                    new OpenAiCompatibleProviderConnectionTester(
                            new IdentityCipher(), new ObjectMapper(), properties);
            ModelProvider provider = new ModelProvider(
                    "provider-1", "tenant-1", "owner-1", "local", "openai-compatible",
                    "http://127.0.0.1:" + server.getAddress().getPort() + "/v1",
                    "encrypted:provider-secret", "bearer", true, false,
                    Instant.EPOCH, Instant.EPOCH);

            var success = tester.test(provider);
            assertTrue(success.success());
            assertEquals(java.util.List.of("model-a", "model-b"), success.discoveredModelIds());

            status.set(401);
            var failure = tester.test(provider);
            assertFalse(failure.success());
            assertEquals("PROVIDER_AUTH_FAILED", failure.errorCode());
        } finally {
            server.stop(0);
        }
    }

    private static final class IdentityCipher implements ModelProviderSecretCipher {
        @Override
        public String encrypt(String plaintext) {
            return "encrypted:" + plaintext;
        }

        @Override
        public String decrypt(String encoded) {
            return encoded.substring("encrypted:".length());
        }
    }
}

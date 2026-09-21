package com.spaceagent.platform.knowledge.infrastructure;

import com.spaceagent.platform.knowledge.domain.KnowledgeEmbeddingGateway;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;

/**
 * Deterministic test/local embedding adapter. Platform production defaults to HTTP.
 */
@Component
@ConditionalOnProperty(
        prefix = "platform.knowledge",
        name = "embedding-mode",
        havingValue = "deterministic",
        matchIfMissing = false)
public class DeterministicKnowledgeEmbeddingGateway implements KnowledgeEmbeddingGateway {

    private static final int DIMENSIONS = 16;

    @Override
    public EmbeddingBatch embed(List<String> inputs) {
        return new EmbeddingBatch(
                "deterministic-sha256-v1",
                inputs.stream().map(this::vector).toList());
    }

    private List<Double> vector(String input) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(input.getBytes(StandardCharsets.UTF_8));
            List<Double> values = new ArrayList<>(DIMENSIONS);
            for (int index = 0; index < DIMENSIONS; index++) {
                values.add(((digest[index] & 0xff) / 127.5) - 1.0);
            }
            return values;
        } catch (Exception exception) {
            throw new IllegalStateException("Unable to create deterministic embedding", exception);
        }
    }
}

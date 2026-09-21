package com.spaceagent.platform.inference;

import com.spaceagent.platform.inference.api.CreateModelProviderCommand;
import com.spaceagent.platform.inference.api.InferenceExecutionApi;
import com.spaceagent.platform.inference.api.TestProviderModelCommand;
import com.spaceagent.platform.inference.application.InferenceApplicationService;
import com.spaceagent.platform.inference.application.ProviderConnectionApplicationService;
import com.spaceagent.platform.inference.infrastructure.InferenceProperties;
import com.spaceagent.platform.inference.infrastructure.memory.InMemoryInferenceProviderRepository;
import com.spaceagent.platform.inference.infrastructure.memory.InMemoryModelPoolRepository;
import com.spaceagent.platform.inference.infrastructure.memory.InMemoryProviderHealthProbeRepository;
import com.spaceagent.platform.inference.domain.ModelProviderSecretCipher;
import com.spaceagent.shared.id.UuidGenerator;
import com.spaceagent.shared.time.TimeProvider;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProviderModelTestApplicationTest {

    @Test
    void executesBoundedHiProbeThroughInferenceBoundary() {
        Instant now = Instant.parse("2026-08-25T00:00:00Z");
        TimeProvider time = () -> now;
        var providers = new InMemoryInferenceProviderRepository();
        var pools = new InMemoryModelPoolRepository();
        var health = new InMemoryProviderHealthProbeRepository(providers, time);
        ModelProviderSecretCipher cipher = new ModelProviderSecretCipher() {
            @Override public String encrypt(String plaintext) { return plaintext; }
            @Override public String decrypt(String encoded) { return encoded; }
        };
        var inference = new InferenceApplicationService(
                providers, pools, cipher, value -> value,
                new UuidGenerator(), time, health);
        var provider = inference.createProvider(new CreateModelProviderCommand(
                "tenant-1", "owner-1", "Qwen", "openai-compatible",
                "https://example.com/v1", "secret", "bearer", true, true,
                List.of(new CreateModelProviderCommand.ProviderModelDraft(
                        "qwen-plus", "Qwen Plus", 32768))));
        InferenceExecutionApi execution = command -> {
            assertEquals("hi", command.messages().getFirst().content());
            assertEquals(128, command.parameters().get("maxOutputTokens"));
            return new InferenceExecutionApi.InferenceExecutionResult("Hello!", 2, 3);
        };
        var service = new ProviderConnectionApplicationService(
                providers, value -> null, time, health, new UuidGenerator(),
                new InferenceProperties(), execution);

        var result = service.testModel(new TestProviderModelCommand(
                "tenant-1", provider.id(), "qwen-plus"));

        assertTrue(result.success());
        assertEquals("Hello!", result.responsePreview());
        assertEquals(2, result.inputTokens());
        assertEquals(3, result.outputTokens());
    }
}

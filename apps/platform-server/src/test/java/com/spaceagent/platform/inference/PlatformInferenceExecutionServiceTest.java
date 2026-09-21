package com.spaceagent.platform.inference;

import com.spaceagent.platform.inference.api.InferenceExecutionApi;
import com.spaceagent.platform.inference.application.InferenceExecutionService;
import com.spaceagent.platform.inference.domain.InferenceExecutor;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PlatformInferenceExecutionServiceTest {

    @Test
    void executeReturnsModelIndependentResultWithoutProviderSdkTypes() {
        InferenceExecutor executor = request -> new InferenceExecutor.InferenceExecution(
                "response",
                7,
                2);
        InferenceExecutionApi api = new InferenceExecutionService(executor);

        InferenceExecutionApi.InferenceExecutionResult result = api.execute(
                new InferenceExecutionApi.InferenceExecutionCommand(
                        "openai-compatible",
                        "model-1",
                        List.of(new InferenceExecutionApi.InferenceMessage("user", "hello")),
                        Map.of()));

        assertEquals("response", result.content());
        assertEquals(7, result.inputTokens());
        assertEquals(2, result.outputTokens());
    }
}

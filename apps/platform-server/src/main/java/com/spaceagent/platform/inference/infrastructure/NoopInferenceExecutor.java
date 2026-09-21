package com.spaceagent.platform.inference.infrastructure;

import com.spaceagent.platform.inference.domain.InferenceExecutor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Deterministic no-op inference executor used before a real provider adapter is wired.
 * It keeps public contracts provider-neutral and is replaceable.
 */
@Component
@ConditionalOnProperty(
        prefix = "platform.inference",
        name = "execution-mode",
        havingValue = "noop",
        matchIfMissing = false)
public class NoopInferenceExecutor implements InferenceExecutor {

    @Override
    public InferenceExecution execute(InferenceExecutionRequest request) {
        String joined = request.messages().stream()
                .map(message -> message.role() + ": " + message.content())
                .reduce((left, right) -> left + "\n" + right)
                .orElse("");
        int inputTokens = Math.max(1, joined.length() / 4);
        String latest = request.messages().isEmpty()
                ? ""
                : request.messages().get(request.messages().size() - 1).content();
        if (latest.startsWith("tool:echo ")) {
            String value = latest.substring("tool:echo ".length());
            return new InferenceExecution(
                    "",
                    inputTokens,
                    0,
                    List.of(new InferenceToolCall(
                            "call-echo-" + Integer.toHexString(value.hashCode()),
                            "echo",
                            "{\"text\":\"" + escape(value) + "\"}")));
        }
        String content = "noop-inference:" + request.modelId();
        return new InferenceExecution(content, inputTokens, content.length() / 4);
    }

    private String escape(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}

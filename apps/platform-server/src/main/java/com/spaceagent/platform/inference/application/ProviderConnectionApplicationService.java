package com.spaceagent.platform.inference.application;

import com.spaceagent.platform.inference.api.ProviderConnectionApplicationApi;
import com.spaceagent.platform.inference.api.ProviderConnectionTestView;
import com.spaceagent.platform.inference.api.ProviderModelTestView;
import com.spaceagent.platform.inference.api.InferenceExecutionApi;
import com.spaceagent.platform.inference.api.TestProviderModelCommand;
import com.spaceagent.platform.inference.api.TestProviderConnectionCommand;
import com.spaceagent.platform.inference.domain.InferenceProviderRepository;
import com.spaceagent.platform.inference.domain.ModelProvider;
import com.spaceagent.platform.inference.domain.ModelProviderConnectionTester;
import com.spaceagent.platform.inference.domain.ProviderConnectionProbeResult;
import com.spaceagent.shared.exception.BusinessException;
import com.spaceagent.shared.time.TimeProvider;
import com.spaceagent.shared.id.IdGenerator;
import com.spaceagent.platform.inference.domain.ProviderHealthProbeRepository;
import com.spaceagent.platform.inference.infrastructure.InferenceProperties;
import org.springframework.http.HttpStatus;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/** Coordinates bounded Provider connection probes and durable eligibility state. */
@Service
public class ProviderConnectionApplicationService implements ProviderConnectionApplicationApi {

    private final InferenceProviderRepository repository;
    private final ModelProviderConnectionTester tester;
    private final TimeProvider timeProvider;
    private final ProviderHealthProbeRepository healthProbes;
    private final IdGenerator ids;
    private final InferenceProperties properties;
    private final InferenceExecutionApi executionApi;

    @Autowired
    public ProviderConnectionApplicationService(
            InferenceProviderRepository repository,
            ModelProviderConnectionTester tester,
            TimeProvider timeProvider,
            ProviderHealthProbeRepository healthProbes,
            IdGenerator ids,
            InferenceProperties properties,
            InferenceExecutionApi executionApi) {
        this.repository = repository;
        this.tester = tester;
        this.timeProvider = timeProvider;
        this.healthProbes = healthProbes;
        this.ids = ids;
        this.properties = properties;
        this.executionApi = executionApi;
    }

    public ProviderConnectionApplicationService(
            InferenceProviderRepository repository,
            ModelProviderConnectionTester tester,
            TimeProvider timeProvider,
            ProviderHealthProbeRepository healthProbes,
            IdGenerator ids,
            InferenceProperties properties) {
        this(repository, tester, timeProvider, healthProbes, ids, properties, null);
    }

    @Override
    public ProviderConnectionTestView testProvider(TestProviderConnectionCommand command) {
        ModelProvider provider = repository.findProviderByTenantAndId(
                        command.tenantId(), command.providerId())
                .orElseThrow(() -> new BusinessException(
                        "Model provider not found",
                        HttpStatus.NOT_FOUND,
                        "MODEL_PROVIDER_NOT_FOUND"));
        if (!provider.enabled()) {
            Instant testedAt = timeProvider.now();
            return new ProviderConnectionTestView(
                    provider.id(), false, provider.connectionStatus(), 0,
                    List.of(), "PROVIDER_DISABLED", testedAt);
        }
        ProviderConnectionProbeResult result;
        try {
            result = tester.test(provider);
        } catch (RuntimeException exception) {
            result = new ProviderConnectionProbeResult(
                    false, 0, List.of(), "PROVIDER_TEST_FAILED");
        }
        Instant testedAt = timeProvider.now();
        healthProbes.recordManual(new ProviderHealthProbeRepository.ManualObservation(
                provider, ids.nextId(), result, testedAt,
                properties.getHealthProbeIntervalSeconds()));
        ModelProvider updated = provider.recordConnectionTest(result, testedAt);
        return new ProviderConnectionTestView(
                updated.id(), result.success(), updated.connectionStatus(),
                result.latencyMs(), result.discoveredModelIds(),
                result.success() ? null : result.errorCode(), testedAt);
    }

    @Override
    public ProviderModelTestView testModel(TestProviderModelCommand command) {
        ModelProvider provider = repository.findProviderByTenantAndId(
                        command.tenantId(), command.providerId())
                .orElseThrow(() -> new BusinessException(
                        "Model provider not found", HttpStatus.NOT_FOUND,
                        "MODEL_PROVIDER_NOT_FOUND"));
        boolean configured = repository.findModelsByProviderId(provider.id()).stream()
                .anyMatch(model -> command.modelId().equals(model.modelId()));
        if (!configured) {
            throw new BusinessException(
                    "Provider model not found", HttpStatus.NOT_FOUND,
                    "PROVIDER_MODEL_NOT_FOUND");
        }
        Instant testedAt = timeProvider.now();
        if (!provider.enabled()) {
            return failure(command, 0, "PROVIDER_DISABLED", testedAt);
        }
        if (executionApi == null) {
            return failure(command, 0, "MODEL_TEST_UNAVAILABLE", testedAt);
        }
        long started = System.nanoTime();
        try {
            InferenceExecutionApi.InferenceExecutionResult result = executionApi.execute(
                    new InferenceExecutionApi.InferenceExecutionCommand(
                            provider.id(),
                            command.modelId(),
                            List.of(new InferenceExecutionApi.InferenceMessage("user", "hi")),
                            Map.of("temperature", 0.0, "maxOutputTokens", 128)));
            String preview = preview(result.content());
            if (preview.isBlank()) {
                return new ProviderModelTestView(
                        provider.id(), command.modelId(), false, latencyMs(started),
                        result.inputTokens(), result.outputTokens(), "",
                        "MODEL_TEST_EMPTY_RESPONSE", testedAt);
            }
            return new ProviderModelTestView(
                    provider.id(), command.modelId(), true, latencyMs(started),
                    result.inputTokens(), result.outputTokens(), preview, null, testedAt);
        } catch (BusinessException exception) {
            String code = exception.getCode() == null || exception.getCode().isBlank()
                    ? "MODEL_TEST_FAILED" : exception.getCode();
            return failure(command, latencyMs(started), code, testedAt);
        } catch (RuntimeException exception) {
            return failure(command, latencyMs(started), "MODEL_TEST_FAILED", testedAt);
        }
    }

    private static ProviderModelTestView failure(
            TestProviderModelCommand command,
            int latencyMs,
            String errorCode,
            Instant testedAt) {
        return new ProviderModelTestView(
                command.providerId(), command.modelId(), false, latencyMs,
                0, 0, "", errorCode, testedAt);
    }

    private static int latencyMs(long started) {
        long elapsed = Math.max(0L, (System.nanoTime() - started) / 1_000_000L);
        return (int) Math.min(Integer.MAX_VALUE, elapsed);
    }

    private static String preview(String value) {
        if (value == null) {
            return "";
        }
        String normalized = value.replaceAll("\\s+", " ").trim();
        return normalized.length() <= 200 ? normalized : normalized.substring(0, 200);
    }
}

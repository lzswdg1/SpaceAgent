package com.spaceagent.platform.inference;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.spaceagent.platform.inference.api.InferenceExecutionApi;
import com.spaceagent.platform.inference.api.ModelCallClaimView;
import com.spaceagent.platform.inference.api.ModelCallLedgerApplicationApi;
import com.spaceagent.platform.inference.api.ModelCallLedgerView;
import com.spaceagent.platform.inference.api.ModelCallTransitionView;
import com.spaceagent.platform.inference.application.InferenceExecutionService;
import com.spaceagent.platform.inference.application.ModelCallLedgerService;
import com.spaceagent.platform.inference.application.ModelCallPayloadCodec;
import com.spaceagent.platform.inference.application.ModelCallRequestHasher;
import com.spaceagent.platform.inference.domain.InferenceExecutor;
import com.spaceagent.platform.inference.domain.InferenceProviderException;
import com.spaceagent.platform.inference.domain.ModelCallLeasePolicy;
import com.spaceagent.platform.inference.domain.ModelCallStatus;
import com.spaceagent.platform.inference.infrastructure.memory.InMemoryModelCallLedgerRepository;
import com.spaceagent.platform.inference.infrastructure.memory.InMemoryInferenceBudgetRepository;
import com.spaceagent.platform.inference.application.InferenceBudgetApplicationService;
import com.spaceagent.platform.inference.api.InferenceBudgetApplicationApi;
import com.spaceagent.shared.id.UuidGenerator;
import com.spaceagent.platform.inference.infrastructure.InferenceProperties;
import com.spaceagent.shared.exception.BusinessException;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PlatformModelCallLedgerInferenceTest {

    private static final ModelCallLeasePolicy LEASE_POLICY = new ModelCallLeasePolicy() {
        @Override
        public long requestTimeoutSeconds() {
            return 3;
        }

        @Override
        public long claimLeaseSeconds() {
            return 5;
        }
    };

    @Test
    void successfulResponseAndUsageReplayWithoutSecondProviderCall() {
        AtomicInteger calls = new AtomicInteger();
        InferenceExecutor executor = request -> {
            calls.incrementAndGet();
            return new InferenceExecutor.InferenceExecution(
                    "durable response",
                    17,
                    5,
                    List.of(new InferenceExecutor.InferenceToolCall(
                            "call-1", "echo", "{\"text\":\"hello\"}")),
                    "tool_calls",
                    "provider-request-42",
                    Map.of("cached_tokens", 3));
        };
        Fixture fixture = fixture(executor, new AtomicReference<>(Instant.parse("2026-08-22T00:00:00Z")));

        InferenceExecutionApi.InferenceExecutionResult first = fixture.inference().execute(command("hello"));
        InferenceExecutionApi.InferenceExecutionResult replay = fixture.inference().execute(command("hello"));

        assertThat(calls).hasValue(1);
        assertThat(replay).isEqualTo(first);
        assertThat(replay.content()).isEqualTo("durable response");
        assertThat(replay.finishReason()).isEqualTo("tool_calls");
        assertThat(replay.providerRequestId()).isEqualTo("provider-request-42");
        assertThat(replay.toolCalls()).singleElement().satisfies(call -> {
            assertThat(call.id()).isEqualTo("call-1");
            assertThat(call.arguments()).isEqualTo("{\"text\":\"hello\"}");
        });
        assertThat(replay.usage()).containsEntry("cached_tokens", 3)
                .containsEntry("totalTokens", 22);
        assertThat(fixture.ledger().findByLogicalCall("run-1", "step-1:inference:0")
                .orElseThrow().status()).isEqualTo(ModelCallStatus.SUCCEEDED);
    }

    @Test
    void streamingPersistsFirstUsefulChunkOnceAndReplayDoesNotInventTiming() {
        AtomicReference<Instant> now = new AtomicReference<>(
                Instant.parse("2026-09-06T09:00:00Z"));
        AtomicInteger streams = new AtomicInteger();
        InferenceExecutor executor = new InferenceExecutor() {
            @Override
            public InferenceExecution execute(InferenceExecutionRequest request) {
                throw new AssertionError("streaming path required");
            }

            @Override
            public InferenceExecution executeStreaming(
                    InferenceExecutionRequest request, StreamObserver observer) {
                streams.incrementAndGet();
                now.set(now.get().plusMillis(125));
                observer.onFirstChunk();
                observer.onFirstChunk();
                observer.onContentDelta("hello");
                return new InferenceExecution("hello", 3, 1);
            }
        };
        Fixture fixture = fixture(executor, now);
        var observer = new InferenceExecutionApi.InferenceStreamObserver() {
            public void onReasoningDelta(String content) { }
            public void onContentDelta(String content) { }
        };

        fixture.inference().executeStreaming(command("stream"), observer);
        var first = fixture.ledger().findByLogicalCall(
                "run-1", "step-1:inference:0").orElseThrow();
        assertThat(first.firstChunkAt()).isEqualTo(now.get());
        assertThat(first.firstChunkMillis()).isNotNull().isGreaterThanOrEqualTo(0L);

        now.set(now.get().plusSeconds(5));
        fixture.inference().executeStreaming(command("stream"), observer);
        var replay = fixture.ledger().findByLogicalCall(
                "run-1", "step-1:inference:0").orElseThrow();
        assertThat(streams).hasValue(1);
        assertThat(replay.firstChunkAt()).isEqualTo(first.firstChunkAt());
        assertThat(replay.firstChunkMillis()).isEqualTo(first.firstChunkMillis());
    }

    @Test
    void changedRequestConflictsAndDoesNotCallProviderAgain() {
        AtomicInteger calls = new AtomicInteger();
        Fixture fixture = fixture(request -> {
            calls.incrementAndGet();
            return new InferenceExecutor.InferenceExecution("ok", 1, 1);
        }, new AtomicReference<>(Instant.parse("2026-08-22T00:00:00Z")));
        fixture.inference().execute(command("first"));

        assertThatThrownBy(() -> fixture.inference().execute(command("changed")))
                .isInstanceOfSatisfying(BusinessException.class, exception ->
                        assertThat(exception.getCode()).isEqualTo("MODEL_CALL_IDEMPOTENCY_CONFLICT"));
        assertThat(calls).hasValue(1);
    }

    @Test
    void transportAmbiguityBecomesUnknownAndNeverAutomaticallyRetries() {
        AtomicInteger calls = new AtomicInteger();
        Fixture fixture = fixture(request -> {
            calls.incrementAndGet();
            throw new InferenceProviderException(
                    ModelCallStatus.UNKNOWN,
                    "INFERENCE_TRANSPORT_AMBIGUOUS",
                    "Inference transport outcome is unknown",
                    null);
        }, new AtomicReference<>(Instant.parse("2026-08-22T00:00:00Z")));

        assertThatThrownBy(() -> fixture.inference().execute(command("ambiguous")))
                .isInstanceOfSatisfying(BusinessException.class, exception ->
                        assertThat(exception.getCode()).isEqualTo("INFERENCE_TRANSPORT_AMBIGUOUS"));
        assertThatThrownBy(() -> fixture.inference().execute(command("ambiguous")))
                .isInstanceOfSatisfying(BusinessException.class, exception ->
                        assertThat(exception.getCode()).isEqualTo("MODEL_CALL_UNKNOWN"));
        assertThat(calls).hasValue(1);
        assertThat(fixture.ledger().findByLogicalCall("run-1", "step-1:inference:0")
                .orElseThrow().status()).isEqualTo(ModelCallStatus.UNKNOWN);
    }

    @Test
    void providerReturnedButCompletionCrashedExpiresToUnknownWithoutSecondCall() {
        AtomicReference<Instant> now = new AtomicReference<>(Instant.parse("2026-08-22T00:00:00Z"));
        AtomicInteger calls = new AtomicInteger();
        Fixture base = fixture(request -> {
            calls.incrementAndGet();
            return new InferenceExecutor.InferenceExecution("already billed", 4, 2);
        }, now);
        ModelCallLedgerApplicationApi failCompletion = new CompletionCrashLedger(base.ledger());
        InferenceExecutionService crashing = inference(base.executor(), failCompletion);

        assertThatThrownBy(() -> crashing.execute(command("crash-window")))
                .isInstanceOfSatisfying(BusinessException.class, exception ->
                        assertThat(exception.getCode()).isEqualTo("MODEL_CALL_COMPLETION_UNAVAILABLE"));
        assertThat(base.ledger().findByLogicalCall("run-1", "step-1:inference:0")
                .orElseThrow().status()).isEqualTo(ModelCallStatus.RUNNING);

        now.set(now.get().plusSeconds(6));
        assertThatThrownBy(() -> base.inference().execute(command("crash-window")))
                .isInstanceOfSatisfying(BusinessException.class, exception ->
                        assertThat(exception.getCode()).isEqualTo("MODEL_CALL_UNKNOWN"));
        assertThat(calls).hasValue(1);
    }

    @Test
    void requestHashIsStableAcrossMapInsertionOrderAndCoversToolSchema() {
        ObjectMapper mapper = new ObjectMapper();
        ModelCallRequestHasher hasher = new ModelCallRequestHasher(mapper);
        Map<String, Object> first = new LinkedHashMap<>();
        first.put("temperature", 0.2);
        first.put("maxOutputTokens", 100);
        Map<String, Object> second = new LinkedHashMap<>();
        second.put("maxOutputTokens", 100);
        second.put("temperature", 0.2);
        InferenceExecutor.InferenceToolDefinition tool = new InferenceExecutor.InferenceToolDefinition(
                "echo", "Echo", Map.of("type", "object", "properties", Map.of()));

        String firstHash = hasher.hash(new InferenceExecutor.InferenceExecutionRequest(
                "provider", "model", List.of(), first, List.of(tool)));
        String secondHash = hasher.hash(new InferenceExecutor.InferenceExecutionRequest(
                "provider", "model", List.of(), second, List.of(tool)));
        String noToolHash = hasher.hash(new InferenceExecutor.InferenceExecutionRequest(
                "provider", "model", List.of(), second, List.of()));

        assertThat(firstHash).isEqualTo(secondHash).hasSize(64);
        assertThat(noToolHash).isNotEqualTo(firstHash);
    }

    @Test
    void explicitAgentToolIdsControlWhichDefinitionsReachTheProvider() {
        AtomicReference<List<String>> toolNames = new AtomicReference<>(List.of());
        InferenceExecutionService service = new InferenceExecutionService(request -> {
            toolNames.set(request.tools().stream()
                    .map(InferenceExecutor.InferenceToolDefinition::name)
                    .toList());
            return new InferenceExecutor.InferenceExecution("ok", 1, 1);
        });

        service.execute(new InferenceExecutionApi.InferenceExecutionCommand(
                "provider", "model",
                List.of(new InferenceExecutionApi.InferenceMessage("user", "no tools")),
                Map.of("enabledToolIds", List.of())));
        assertThat(toolNames.get()).isEmpty();

        service.execute(new InferenceExecutionApi.InferenceExecutionCommand(
                "provider", "model",
                List.of(new InferenceExecutionApi.InferenceMessage("user", "use echo")),
                Map.of("enabledToolIds", List.of("unknown", "tool:echo"))));
        assertThat(toolNames.get()).containsExactly("echo");
    }

    @Test
    void inferenceTimeoutAndLeaseArePositiveAndBounded() {
        InferenceProperties properties = new InferenceProperties();
        properties.setRequestTimeoutSeconds(600);
        properties.setClaimLeaseSafetySeconds(120);

        assertThat(properties.claimLeaseSeconds()).isEqualTo(720);
        assertThatThrownBy(() -> properties.setRequestTimeoutSeconds(601))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> properties.setClaimLeaseSafetySeconds(0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void knownFailureFallsBackAndEveryAttemptReplaysFromItsOwnLedger() {
        AtomicInteger calls=new AtomicInteger();Fixture fixture=fixture(request->{calls.incrementAndGet();if(request.providerType().equals("provider-1"))throw new InferenceProviderException(ModelCallStatus.FAILED,"PROVIDER_ONE_REJECTED","Provider one rejected",null);return new InferenceExecutor.InferenceExecution("fallback-ok",7,3);},new AtomicReference<>(Instant.parse("2026-08-22T00:00:00Z")));
        var first=fixture.inference().execute(routedCommand("fallback"));var replay=fixture.inference().execute(routedCommand("fallback"));
        assertThat(first.content()).isEqualTo("fallback-ok");assertThat(first.selectedProviderId()).isEqualTo("provider-2");assertThat(first.attemptCount()).isEqualTo(2);assertThat(replay).isEqualTo(first);assertThat(calls).hasValue(2);
        assertThat(fixture.ledger().findByRunId("run-1")).extracting(ModelCallLedgerView::logicalCallId).containsExactlyInAnyOrder("step-1:inference:0:attempt:0","step-1:inference:0:attempt:1");
    }

    @Test
    void unknownAttemptStopsWithoutFallback() {
        AtomicInteger calls=new AtomicInteger();Fixture fixture=fixture(request->{calls.incrementAndGet();throw new InferenceProviderException(ModelCallStatus.UNKNOWN,"INFERENCE_TRANSPORT_AMBIGUOUS","unknown",null);},new AtomicReference<>(Instant.parse("2026-08-22T00:00:00Z")));
        assertThatThrownBy(()->fixture.inference().execute(routedCommand("unknown"))).isInstanceOfSatisfying(BusinessException.class,e->assertThat(e.getCode()).isEqualTo("INFERENCE_TRANSPORT_AMBIGUOUS"));assertThat(calls).hasValue(1);assertThat(fixture.ledger().findByLogicalCall("run-1","step-1:inference:0:attempt:1")).isEmpty();
    }

    @Test
    void routedExecutionReservesAndSettlesOnlyWinningAttempt() {
        AtomicReference<Instant> now=new AtomicReference<>(Instant.parse("2026-08-22T00:00:00Z"));InMemoryModelCallLedgerRepository repository=new InMemoryModelCallLedgerRepository(now::get);ModelCallLedgerService ledger=new ModelCallLedgerService(repository,()->UUID.randomUUID().toString(),()->"worker");var budget=new InferenceBudgetApplicationService(new InMemoryInferenceBudgetRepository(now::get),new UuidGenerator(),now::get);budget.configure(new InferenceBudgetApplicationApi.ConfigurePolicyCommand("tenant-1","owner",true,2,1000,1000));ObjectMapper mapper=new ObjectMapper();AtomicInteger calls=new AtomicInteger();InferenceExecutor executor=request->{calls.incrementAndGet();if(request.providerType().equals("provider-1"))throw new InferenceProviderException(ModelCallStatus.FAILED,"PROVIDER_ONE_REJECTED","rejected",null);return new InferenceExecutor.InferenceExecution("ok",7,3);};var service=new InferenceExecutionService(executor,ledger,new ModelCallRequestHasher(mapper),new ModelCallPayloadCodec(mapper),LEASE_POLICY,budget, com.spaceagent.platform.inference.domain.InferenceTelemetry.noop(), (t, r) -> {});var command=routedCommand("budgeted");var priced=new InferenceExecutionApi.InferenceExecutionCommand(command.providerType(),command.modelId(),command.messages(),command.parameters(),command.agentRunId(),command.runStepId(),command.logicalCallId(),command.tenantId(),command.modelPoolId(),command.candidates().stream().map(v->new InferenceExecutionApi.InferenceCandidate(v.memberId(),v.providerId(),v.providerModelId(),v.modelId(),v.priority(),v.weight(),v.healthLatencyMs(),"price-"+v.memberId(),1_000_000L,1_000_000L)).toList(),command.routingStrategy(),command.candidateSnapshotHash(),true);assertThat(service.execute(priced).selectedProviderId()).isEqualTo("provider-2");var usage=budget.usage("tenant-1");assertThat(usage.consumedRequests()).isEqualTo(1);assertThat(usage.reservedRequests()).isZero();assertThat(usage.consumedCostMicros()).isEqualTo(10);assertThat(calls).hasValue(2);
    }

    private static Fixture fixture(InferenceExecutor executor, AtomicReference<Instant> now) {
        InMemoryModelCallLedgerRepository repository = new InMemoryModelCallLedgerRepository(now::get);
        ModelCallLedgerService ledger = new ModelCallLedgerService(
                repository,
                () -> UUID.randomUUID().toString(),
                () -> "test-instance");
        return new Fixture(executor, ledger, inference(executor, ledger));
    }

    private static InferenceExecutionService inference(
            InferenceExecutor executor,
            ModelCallLedgerApplicationApi ledger) {
        ObjectMapper mapper = new ObjectMapper();
        return new InferenceExecutionService(
                executor,
                ledger,
                new ModelCallRequestHasher(mapper),
                new ModelCallPayloadCodec(mapper),
                LEASE_POLICY, null, com.spaceagent.platform.inference.domain.InferenceTelemetry.noop(), (t, r) -> {});
    }

    private static InferenceExecutionApi.InferenceExecutionCommand command(String message) {
        return new InferenceExecutionApi.InferenceExecutionCommand(
                "provider-1",
                "model-1",
                List.of(
                        new InferenceExecutionApi.InferenceMessage("system", "system prompt"),
                        new InferenceExecutionApi.InferenceMessage("user", message)),
                Map.of("temperature", 0.2, "maxOutputTokens", 100, "enableTools", true),
                "run-1",
                "step-1",
                "step-1:inference:0");
    }

    private static InferenceExecutionApi.InferenceExecutionCommand routedCommand(String message){return new InferenceExecutionApi.InferenceExecutionCommand("provider-1","model-1",List.of(new InferenceExecutionApi.InferenceMessage("user",message)),Map.of("maxOutputTokens",100),"run-1","step-1","step-1:inference:0","tenant-1","pool-1",List.of(new InferenceExecutionApi.InferenceCandidate("member-1","provider-1","pm-1","model-1",10,1,10,null,null,null),new InferenceExecutionApi.InferenceCandidate("member-2","provider-2","pm-2","model-2",20,1,20,null,null,null)),"PRIORITY","sha256:snapshot",true);}

    private record Fixture(
            InferenceExecutor executor,
            ModelCallLedgerService ledger,
            InferenceExecutionService inference) {
    }

    private static final class CompletionCrashLedger implements ModelCallLedgerApplicationApi {
        private final ModelCallLedgerApplicationApi delegate;

        private CompletionCrashLedger(ModelCallLedgerApplicationApi delegate) {
            this.delegate = delegate;
        }

        @Override
        public ModelCallClaimView claim(com.spaceagent.platform.inference.api.ClaimModelCallCommand command) {
            return delegate.claim(command);
        }

        @Override
        public ModelCallTransitionView complete(
                com.spaceagent.platform.inference.api.CompleteModelCallCommand command) {
            throw new IllegalStateException("simulated crash before terminal write");
        }

        @Override
        public ModelCallTransitionView recordFirstChunk(
                com.spaceagent.platform.inference.api.RecordModelCallFirstChunkCommand command) {
            return delegate.recordFirstChunk(command);
        }

        @Override
        public ModelCallTransitionView markUnknown(
                com.spaceagent.platform.inference.api.MarkModelCallUnknownCommand command) {
            return delegate.markUnknown(command);
        }

        @Override
        public List<ModelCallLedgerView> findByRunId(String agentRunId) {
            return delegate.findByRunId(agentRunId);
        }

        @Override
        public Optional<ModelCallLedgerView> findByLogicalCall(String agentRunId, String logicalCallId) {
            return delegate.findByLogicalCall(agentRunId, logicalCallId);
        }
    }
}

package com.spaceagent.platform.inference.application;

import com.spaceagent.platform.inference.api.ClaimModelCallCommand;
import com.spaceagent.platform.inference.api.CompleteModelCallCommand;
import com.spaceagent.platform.inference.api.InferenceExecutionApi;
import com.spaceagent.platform.inference.api.MarkModelCallUnknownCommand;
import com.spaceagent.platform.inference.api.ModelCallClaimView;
import com.spaceagent.platform.inference.api.ModelCallLedgerApplicationApi;
import com.spaceagent.platform.inference.api.ModelCallLedgerView;
import com.spaceagent.platform.inference.api.ModelCallTransitionView;
import com.spaceagent.platform.inference.api.RecordModelCallFirstChunkCommand;
import com.spaceagent.platform.inference.api.InferenceBudgetApplicationApi;
import com.spaceagent.platform.inference.domain.InferenceExecutor;
import com.spaceagent.platform.inference.domain.InferenceProviderException;
import com.spaceagent.platform.inference.domain.InferenceTelemetry;
import com.spaceagent.platform.inference.domain.ModelCallLeasePolicy;
import com.spaceagent.platform.inference.domain.ModelCallStatus;
import com.spaceagent.platform.inference.domain.ModelCallTransitionType;
import com.spaceagent.shared.exception.BusinessException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Model-independent inference gateway with an optional durable call identity.
 * Each ledger mutation is a short transaction; provider execution is outside it.
 */
@Service
public class InferenceExecutionService implements InferenceExecutionApi {

    private static final Logger LOGGER = LoggerFactory.getLogger(InferenceExecutionService.class);

    private final InferenceExecutor executor;
    private final ModelCallLedgerApplicationApi ledgerApi;
    private final ModelCallRequestHasher requestHasher;
    private final ModelCallPayloadCodec payloadCodec;
    private final ModelCallLeasePolicy leasePolicy;
    private final InferenceBudgetApplicationApi budgetApi;
    private final InferenceTelemetry telemetry;
    private final com.spaceagent.platform.inference.domain.InferenceExecutionAdmissionPort admission;

    @Autowired
    public InferenceExecutionService(
            InferenceExecutor executor,
            ModelCallLedgerApplicationApi ledgerApi,
            ModelCallRequestHasher requestHasher,
            ModelCallPayloadCodec payloadCodec,
            ModelCallLeasePolicy leasePolicy,
            InferenceBudgetApplicationApi budgetApi,
            InferenceTelemetry telemetry,
            com.spaceagent.platform.inference.domain.InferenceExecutionAdmissionPort admission) {
        this.executor = executor;
        this.ledgerApi = ledgerApi;
        this.requestHasher = requestHasher;
        this.payloadCodec = payloadCodec;
        this.leasePolicy = leasePolicy;
        this.budgetApi = budgetApi;
        this.telemetry = telemetry;
        this.admission = java.util.Objects.requireNonNull(admission);
    }

    /** Without an explicit admission port only non-runtime diagnostics are supported. */
    public InferenceExecutionService(InferenceExecutor e, ModelCallLedgerApplicationApi l,
            ModelCallRequestHasher h, ModelCallPayloadCodec c, ModelCallLeasePolicy p,
            InferenceBudgetApplicationApi b, InferenceTelemetry t) {
        this(e, l, h, c, p, b, t, com.spaceagent.platform.inference.domain.InferenceExecutionAdmissionPort.unavailable());
    }

    public InferenceExecutionService(InferenceExecutor e,ModelCallLedgerApplicationApi l,ModelCallRequestHasher h,ModelCallPayloadCodec c,ModelCallLeasePolicy p){this(e,l,h,c,p,null,InferenceTelemetry.noop());}
    public InferenceExecutionService(InferenceExecutor e,ModelCallLedgerApplicationApi l,
            ModelCallRequestHasher h,ModelCallPayloadCodec c,ModelCallLeasePolicy p,
            InferenceBudgetApplicationApi b){this(e,l,h,c,p,b,InferenceTelemetry.noop());}

    /** Compatibility constructor for focused, non-runtime inference tests. */
    public InferenceExecutionService(InferenceExecutor executor) {
        this.executor = executor;
        this.ledgerApi = null;
        this.requestHasher = null;
        this.payloadCodec = null;
        this.leasePolicy = null;
        this.budgetApi = null;
        this.admission = com.spaceagent.platform.inference.domain.InferenceExecutionAdmissionPort.unavailable();
        this.telemetry = InferenceTelemetry.noop();
    }

    @Override
    public InferenceExecutionResult execute(InferenceExecutionCommand command) {
        return executeRouted(command, null);
    }

    @Override
    public InferenceExecutionResult executeStreaming(
            InferenceExecutionCommand command,
            InferenceStreamObserver observer) {
        return executeRouted(command, observer);
    }

    private InferenceExecutionResult executeRouted(
            InferenceExecutionCommand command,
            InferenceStreamObserver observer) {
        List<InferenceExecutionApi.InferenceCandidate> candidates = command.candidates().isEmpty()
                ? List.of(new InferenceExecutionApi.InferenceCandidate(
                        null,command.providerType(),null,command.modelId(),0,1,null,
                        null,null,null)) : command.candidates();
        if(candidates.size()>1&&!command.durable())throw modelCallError(
                "MODEL_ROUTING_REQUIRES_DURABLE_CALL","Fallback requires durable call identity",
                HttpStatus.BAD_REQUEST);
        BusinessException last=null;
        for(int index=0;index<candidates.size();index++){
            InferenceExecutionApi.InferenceCandidate candidate=candidates.get(index);
            InferenceExecutionCommand attempt=attempt(command,candidate,index,candidates.size());
            if (attempt.durable()) admission.requireDispatch(attempt.tenantId(), attempt.agentRunId());
            InferenceBudgetApplicationApi.ReservationView reservation=reserve(attempt,candidate);
            try{InferenceExecutionResult result=executeSingle(attempt,observer);if(reservation!=null)budgetApi.settle(reservation.id(),result.inputTokens(),result.outputTokens());return routed(result,candidate,index+1,command.candidateSnapshotHash());}
            catch(BusinessException error){last=error;if(reservation!=null&&reservation.created()){if(unknownOutcome(error.getCode()))budgetApi.markUnknown(reservation.id());else if(!"MODEL_CALL_IN_PROGRESS".equals(error.getCode()))budgetApi.release(reservation.id());}if(index+1>=candidates.size()
                    ||!command.fallbackEnabled()||!fallbackSafe(error.getCode()))throw error;}
        }
        throw last==null?modelCallError("MODEL_ROUTING_FAILED","No model candidate",HttpStatus.BAD_GATEWAY):last;
    }

    private InferenceBudgetApplicationApi.ReservationView reserve(InferenceExecutionCommand c,InferenceExecutionApi.InferenceCandidate candidate){if(budgetApi==null||!c.durable()||c.tenantId()==null||c.tenantId().isBlank())return null;long input=Math.max(1,c.messages().stream().mapToLong(v->v.content().length()).sum()/4);Object max=c.parameters().get("maxOutputTokens");long output=max instanceof Number n?Math.max(0,n.longValue()):0;return budgetApi.reserve(new InferenceBudgetApplicationApi.ReserveCommand(c.tenantId(),c.agentRunId(),c.logicalCallId(),candidate.providerId(),candidate.modelId(),candidate.priceId(),candidate.inputMicrosPerMillionTokens(),candidate.outputMicrosPerMillionTokens(),input,output));}
    private static boolean unknownOutcome(String code){return java.util.Set.of("MODEL_CALL_UNKNOWN","MODEL_CALL_COMPLETION_UNAVAILABLE","INFERENCE_PROVIDER_RESPONSE_AMBIGUOUS","INFERENCE_TRANSPORT_AMBIGUOUS","INFERENCE_TIMEOUT_AMBIGUOUS","INFERENCE_RESPONSE_AMBIGUOUS","INFERENCE_TIMED_OUT").contains(code);}

    private InferenceExecutionResult executeSingle(
            InferenceExecutionCommand command,
            InferenceStreamObserver observer) {
        InferenceExecutor.InferenceExecutionRequest request = toRequest(command);
        if (!command.durable()) {
            long started = System.nanoTime();
            InferenceTelemetry.CallSpan span = telemetry.start(command.modelId(), observer != null);
            try {
                InferenceExecutor.InferenceExecution result = executeProvider(request, observer,
                        () -> span.firstChunk(Math.max(0,
                                (System.nanoTime() - started) / 1_000_000)));
                span.success(result.inputTokens(), result.outputTokens());
                return toApi(result);
            } catch (RuntimeException error) {
                span.error(error instanceof BusinessException business
                        ? controlledCode(business.getCode(), "INFERENCE_FAILED")
                        : error.getClass().getSimpleName());
                throw error;
            } finally {
                span.close();
            }
        }
        requireDurableDependencies();

        String requestHash = requestHasher.hash(request);
        ModelCallClaimView claim = ledgerApi.claim(new ClaimModelCallCommand(
                command.agentRunId(),
                command.runStepId(),
                command.logicalCallId(),
                requestHash,
                command.providerType(),
                command.modelId(),
                leasePolicy.claimLeaseSeconds()));
        return switch (claim.decision()) {
            case CLAIMED -> executeClaimed(command, request, claim, observer);
            case REPLAY -> emit(replay(claim.ledger()), observer);
            case BUSY -> throw modelCallError(
                    "MODEL_CALL_IN_PROGRESS",
                    "The logical model call already has an active claim",
                    HttpStatus.CONFLICT);
            case UNKNOWN -> throw modelCallError(
                    "MODEL_CALL_UNKNOWN",
                    "The logical model call outcome is unknown and requires reconciliation",
                    HttpStatus.CONFLICT);
            case CONFLICT -> throw modelCallError(
                    "MODEL_CALL_IDEMPOTENCY_CONFLICT",
                    "The logical model call was reused with a different request",
                    HttpStatus.CONFLICT);
        };
    }

    private static InferenceExecutionCommand attempt(InferenceExecutionCommand root,
            InferenceExecutionApi.InferenceCandidate candidate,int index,int count){
        Map<String,Object> parameters=new java.util.LinkedHashMap<>(root.parameters());
        if(root.candidateSnapshotHash()!=null)parameters.put("routingSnapshotHash",root.candidateSnapshotHash());
        parameters.put("routingAttemptOrdinal",index);parameters.put("routingCandidateCount",count);
        String logical=count==1?root.logicalCallId():root.logicalCallId()+":attempt:"+index;
        return new InferenceExecutionCommand(candidate.providerId(),candidate.modelId(),root.messages(),
                parameters,root.agentRunId(),root.runStepId(),logical,root.tenantId(),root.modelPoolId(),
                List.of(candidate),root.routingStrategy(),root.candidateSnapshotHash(),false);
    }

    private static InferenceExecutionResult routed(InferenceExecutionResult result,
            InferenceExecutionApi.InferenceCandidate candidate,int attempts,String snapshot){
        return new InferenceExecutionResult(result.content(),result.inputTokens(),result.outputTokens(),
                result.toolCalls(),result.finishReason(),result.providerRequestId(),result.usage(),
                candidate.providerId(),candidate.modelId(),attempts,snapshot,result.reasoningContent());
    }

    private static boolean fallbackSafe(String code){return !java.util.Set.of(
            "MODEL_CALL_UNKNOWN","MODEL_CALL_IN_PROGRESS","MODEL_CALL_IDEMPOTENCY_CONFLICT",
            "MODEL_CALL_CLAIM_LOST","MODEL_CALL_COMPLETION_UNAVAILABLE",
            "INFERENCE_PROVIDER_RESPONSE_AMBIGUOUS","INFERENCE_TRANSPORT_AMBIGUOUS",
            "INFERENCE_TIMEOUT_AMBIGUOUS",
            "INFERENCE_RESPONSE_AMBIGUOUS","INFERENCE_TIMED_OUT",
            "INFERENCE_CANCELLED").contains(code);}

    private InferenceExecutionResult executeClaimed(
            InferenceExecutionCommand command,
            InferenceExecutor.InferenceExecutionRequest request,
            ModelCallClaimView claim,
        InferenceStreamObserver observer) {
        InferenceExecutor.InferenceExecution result;
        InferenceTelemetry.CallSpan span = telemetry.start(command.modelId(), observer != null);
        try {
            AtomicBoolean firstChunkRecorded = new AtomicBoolean();
            long providerStarted = System.nanoTime();
            result = executeProvider(request, observer, () -> {
                if (firstChunkRecorded.compareAndSet(false, true)) {
                    long elapsedMillis = Math.max(0,
                            (System.nanoTime() - providerStarted) / 1_000_000);
                    Long firstChunkMillis = recordFirstChunk(command, claim, elapsedMillis);
                    if (firstChunkMillis != null) span.firstChunk(firstChunkMillis);
                }
            });
            span.success(result.inputTokens(), result.outputTokens());
        } catch (InferenceProviderException exception) {
            span.error(exception.errorCode());
            recordProviderFailure(command, claim, exception);
            throw modelCallError(
                    exception.errorCode(),
                    exception.safeSummary(),
                    statusFor(exception.disposition()));
        } catch (CancellationException exception) {
            span.error("INFERENCE_CANCELLED");
            InferenceProviderException cancelled = new InferenceProviderException(
                    ModelCallStatus.UNKNOWN,
                    "MODEL_CALL_UNKNOWN",
                    "Inference cancelled after dispatch; provider usage is not confirmed",
                    exception);
            recordProviderFailure(command, claim, cancelled);
            throw modelCallError(cancelled.errorCode(), cancelled.safeSummary(), HttpStatus.CONFLICT);
        } catch (BusinessException exception) {
            span.error(controlledCode(exception.getCode(), "INFERENCE_VALIDATION_FAILED"));
            recordSafeFailure(
                    command,
                    claim,
                    controlledCode(exception.getCode(), "INFERENCE_VALIDATION_FAILED"),
                    controlledSummary(exception.getMessage(), "Inference validation failed"));
            throw exception;
        } finally {
            span.close();
        }

        ModelCallPayloadCodec.EncodedPayload payload;
        try {
            payload = payloadCodec.encode(result);
        } catch (ModelCallPayloadCodec.ModelCallPayloadException exception) {
            markUnknown(
                    command,
                    claim,
                    "INFERENCE_RESPONSE_PERSISTENCE_AMBIGUOUS",
                    "Provider returned successfully but the standardized response could not be persisted");
            throw modelCallError(
                    "MODEL_CALL_UNKNOWN",
                    "Provider returned but the durable model result is unavailable",
                    HttpStatus.SERVICE_UNAVAILABLE);
        }

        ModelCallTransitionView completion;
        try {
            completion = ledgerApi.complete(new CompleteModelCallCommand(
                    command.agentRunId(),
                    command.logicalCallId(),
                    claim.claimToken(),
                    claim.revision(),
                    ModelCallStatus.SUCCEEDED,
                    result.providerRequestId(),
                    payload.responsePayload(),
                    payload.usagePayload(),
                    null,
                    null));
        } catch (RuntimeException exception) {
            LOGGER.error(
                    "Model call completion persistence failed; runId={}, logicalCallId={}",
                    command.agentRunId(),
                    command.logicalCallId());
            throw modelCallError(
                    "MODEL_CALL_COMPLETION_UNAVAILABLE",
                    "Provider returned but the durable model result could not be committed",
                    HttpStatus.SERVICE_UNAVAILABLE);
        }
        if (completion.transition() == ModelCallTransitionType.APPLIED) {
            return toApi(result);
        }
        if (completion.transition() == ModelCallTransitionType.CURRENT_TERMINAL) {
            return replay(completion.ledger());
        }
        throw modelCallError(
                "MODEL_CALL_CLAIM_LOST",
                "The model call result was rejected by completion fencing",
                HttpStatus.CONFLICT);
    }

    private InferenceExecutor.InferenceExecution executeProvider(
            InferenceExecutor.InferenceExecutionRequest request,
            InferenceStreamObserver observer,
            Runnable firstChunk) {
        if (observer == null) return executor.execute(request);
        return executor.executeStreaming(request, new InferenceExecutor.StreamObserver() {
            @Override
            public void onFirstChunk() {
                firstChunk.run();
            }

            @Override
            public void onReasoningDelta(String content) {
                observer.onReasoningDelta(content);
            }

            @Override
            public void onContentDelta(String content) {
                observer.onContentDelta(content);
            }
        });
    }

    private Long recordFirstChunk(
            InferenceExecutionCommand command,
            ModelCallClaimView claim,
            long elapsedMillis) {
        try {
            ModelCallTransitionView transition = ledgerApi.recordFirstChunk(
                    new RecordModelCallFirstChunkCommand(
                            command.agentRunId(), command.logicalCallId(), claim.claimToken(),
                            claim.revision(), elapsedMillis));
            if (transition.transition() != ModelCallTransitionType.APPLIED) {
                LOGGER.warn("Model first-chunk evidence lost claim; runId={}, logicalCallId={}",
                        command.agentRunId(), command.logicalCallId());
            }
            return transition.transition() == ModelCallTransitionType.APPLIED
                    ? transition.ledger().firstChunkMillis() : null;
        } catch (RuntimeException error) {
            LOGGER.warn("Model first-chunk evidence persistence failed; runId={}, logicalCallId={}",
                    command.agentRunId(), command.logicalCallId());
            return null;
        }
    }

    private static InferenceExecutionResult emit(
            InferenceExecutionResult result,
            InferenceStreamObserver observer) {
        if (observer == null) return result;
        if (!result.reasoningContent().isBlank()) {
            observer.onReasoningDelta(result.reasoningContent());
        }
        if (!result.content().isBlank()) {
            observer.onContentDelta(result.content());
        }
        return result;
    }

    private void recordProviderFailure(
            InferenceExecutionCommand command,
            ModelCallClaimView claim,
            InferenceProviderException exception) {
        if (exception.disposition() == ModelCallStatus.UNKNOWN) {
            markUnknown(command, claim, exception.errorCode(), exception.safeSummary());
            return;
        }
        recordSafeFailure(
                command,
                claim,
                exception.disposition(),
                exception.errorCode(),
                exception.safeSummary());
    }

    private void recordSafeFailure(
            InferenceExecutionCommand command,
            ModelCallClaimView claim,
            String errorCode,
            String errorSummary) {
        recordSafeFailure(command, claim, ModelCallStatus.FAILED, errorCode, errorSummary);
    }

    private void recordSafeFailure(
            InferenceExecutionCommand command,
            ModelCallClaimView claim,
            ModelCallStatus status,
            String errorCode,
            String errorSummary) {
        try {
            ModelCallTransitionView transition = ledgerApi.complete(new CompleteModelCallCommand(
                    command.agentRunId(),
                    command.logicalCallId(),
                    claim.claimToken(),
                    claim.revision(),
                    status,
                    null,
                    null,
                    null,
                    errorCode,
                    errorSummary));
            logRejectedFailureTransition(command, transition);
        } catch (RuntimeException exception) {
            LOGGER.error(
                    "Model call failure persistence failed; runId={}, logicalCallId={}",
                    command.agentRunId(),
                    command.logicalCallId());
        }
    }

    private void markUnknown(
            InferenceExecutionCommand command,
            ModelCallClaimView claim,
            String errorCode,
            String errorSummary) {
        try {
            ModelCallTransitionView transition = ledgerApi.markUnknown(new MarkModelCallUnknownCommand(
                    command.agentRunId(),
                    command.logicalCallId(),
                    claim.claimToken(),
                    claim.revision(),
                    errorCode,
                    errorSummary));
            logRejectedFailureTransition(command, transition);
        } catch (RuntimeException exception) {
            LOGGER.error(
                    "Model call UNKNOWN persistence failed; runId={}, logicalCallId={}",
                    command.agentRunId(),
                    command.logicalCallId());
        }
    }

    private void logRejectedFailureTransition(
            InferenceExecutionCommand command,
            ModelCallTransitionView transition) {
        if (transition.transition() == ModelCallTransitionType.CLAIM_LOST) {
            LOGGER.warn(
                    "Model call failure transition lost fencing claim; runId={}, logicalCallId={}",
                    command.agentRunId(),
                    command.logicalCallId());
        }
    }

    private InferenceExecutionResult replay(ModelCallLedgerView ledger) {
        return switch (ledger.status()) {
            case SUCCEEDED -> payloadCodec.decode(
                    ledger.responsePayload(), ledger.usagePayload(), ledger.providerRequestId());
            case FAILED -> throw modelCallError(
                    controlledCode(ledger.errorCode(), "INFERENCE_PROVIDER_FAILED"),
                    controlledSummary(ledger.errorSummary(), "Inference provider call failed"),
                    HttpStatus.BAD_GATEWAY);
            case TIMED_OUT -> throw modelCallError(
                    controlledCode(ledger.errorCode(), "INFERENCE_TIMED_OUT"),
                    controlledSummary(ledger.errorSummary(), "Inference provider call timed out"),
                    HttpStatus.GATEWAY_TIMEOUT);
            case CANCELLED -> throw modelCallError(
                    controlledCode(ledger.errorCode(), "INFERENCE_CANCELLED"),
                    controlledSummary(ledger.errorSummary(), "Inference provider call was cancelled"),
                    HttpStatus.CONFLICT);
            case UNKNOWN -> throw modelCallError(
                    "MODEL_CALL_UNKNOWN",
                    "The logical model call outcome is unknown and requires reconciliation",
                    HttpStatus.CONFLICT);
            case RUNNING -> throw modelCallError(
                    "MODEL_CALL_IN_PROGRESS",
                    "The logical model call already has an active claim",
                    HttpStatus.CONFLICT);
        };
    }

    private static InferenceExecutor.InferenceExecutionRequest toRequest(InferenceExecutionCommand command) {
        List<com.spaceagent.platform.inference.domain.InferenceMessage> messages = command.messages().stream()
                .map(message -> new com.spaceagent.platform.inference.domain.InferenceMessage(
                        message.role(), message.content()))
                .toList();
        List<InferenceExecutor.InferenceToolDefinition> tools = authorizedTools(command.parameters());
        return new InferenceExecutor.InferenceExecutionRequest(
                command.providerType(), command.modelId(), messages, command.parameters(), tools);
    }

    private static List<InferenceExecutor.InferenceToolDefinition> authorizedTools(
            Map<String, Object> parameters) {
        Object definitions = parameters.get("toolDefinitions");
        if (definitions instanceof Iterable<?> values) {
            List<InferenceExecutor.InferenceToolDefinition> result = new java.util.ArrayList<>();
            for (Object value : values) {
                if (!(value instanceof Map<?, ?> definition) || result.size() >= 32) continue;
                String name = string(definition.get("name"), 200);
                String description = string(definition.get("description"), 2_000);
                Object schema = definition.get("parameters");
                if (name == null || description == null || !(schema instanceof Map<?, ?> raw)) {
                    continue;
                }
                Map<String, Object> normalizedSchema = new java.util.LinkedHashMap<>();
                raw.forEach((key, item) -> {
                    if (key != null) normalizedSchema.put(String.valueOf(key), item);
                });
                result.add(new InferenceExecutor.InferenceToolDefinition(
                        name, description, normalizedSchema));
            }
            return List.copyOf(result);
        }
        Object configured = parameters.get("enabledToolIds");
        if (configured instanceof Iterable<?> values) {
            Set<String> ids = new java.util.LinkedHashSet<>();
            values.forEach(value -> {
                if (value != null) {
                    ids.add(value.toString().trim().toLowerCase(Locale.ROOT));
                }
            });
            return ids.contains("echo") || ids.contains("tool:echo")
                    ? List.of(echoTool())
                    : List.of();
        }
        return Boolean.TRUE.equals(parameters.get("enableTools"))
                ? List.of(echoTool())
                : List.of();
    }

    private static String string(Object value, int maxLength) {
        if (value == null) return null;
        String normalized = String.valueOf(value).trim();
        return normalized.isEmpty() || normalized.length() > maxLength ? null : normalized;
    }

    private static InferenceExecutor.InferenceToolDefinition echoTool() {
        return new InferenceExecutor.InferenceToolDefinition(
                "echo",
                "Echo a short value through the isolated tooling boundary",
                Map.of(
                        "type", "object",
                        "properties", Map.of("text", Map.of("type", "string")),
                        "required", List.of("text")));
    }

    private static InferenceExecutionResult toApi(InferenceExecutor.InferenceExecution result) {
        Map<String, Object> usage = new java.util.LinkedHashMap<>(result.usage());
        usage.put("inputTokens", result.inputTokens());
        usage.put("outputTokens", result.outputTokens());
        usage.put("totalTokens", result.inputTokens() + result.outputTokens());
        return new InferenceExecutionResult(
                result.content(),
                result.inputTokens(),
                result.outputTokens(),
                result.toolCalls().stream()
                        .map(call -> new InferenceToolCall(call.id(), call.name(), call.arguments()))
                        .toList(),
                result.finishReason(),
                result.providerRequestId(),
                usage,
                null,
                null,
                1,
                null,
                result.reasoningContent());
    }

    private void requireDurableDependencies() {
        if (ledgerApi == null || requestHasher == null || payloadCodec == null || leasePolicy == null) {
            throw new IllegalStateException("durable inference execution is not configured");
        }
    }

    private static HttpStatus statusFor(ModelCallStatus status) {
        return switch (status) {
            case TIMED_OUT -> HttpStatus.GATEWAY_TIMEOUT;
            case CANCELLED -> HttpStatus.CONFLICT;
            case UNKNOWN, FAILED -> HttpStatus.BAD_GATEWAY;
            default -> HttpStatus.INTERNAL_SERVER_ERROR;
        };
    }

    private static String controlledCode(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private static String controlledSummary(String value, String fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        return value.length() <= 1000 ? value : value.substring(0, 1000);
    }

    private static BusinessException modelCallError(String code, String summary, HttpStatus status) {
        return new BusinessException(summary, status, code);
    }
}

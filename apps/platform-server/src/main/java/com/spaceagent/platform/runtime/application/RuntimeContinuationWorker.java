package com.spaceagent.platform.runtime.application;

import com.spaceagent.platform.runtime.api.ResumeAgentRunCommand;
import com.spaceagent.platform.runtime.api.CompleteAgentRunFencedCommand;
import com.spaceagent.platform.runtime.api.FailAgentRunFencedCommand;
import com.spaceagent.platform.runtime.api.RuntimeApplicationApi;
import com.spaceagent.platform.runtime.api.RuntimeCoordinationApplicationApi;
import com.spaceagent.platform.runtime.api.RuntimeContinuationHandler;
import com.spaceagent.platform.runtime.domain.RuntimeContinuationType;
import com.spaceagent.platform.runtime.infrastructure.RuntimeCoordinationProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/** Polling worker safe for multiple replicas because every claim is PostgreSQL fenced. */
@Component
@ConditionalOnProperty(
        prefix = "platform.runtime.coordination",
        name = "enabled",
        havingValue = "true",
        matchIfMissing = true)
public class RuntimeContinuationWorker {

    private static final Logger log = LoggerFactory.getLogger(RuntimeContinuationWorker.class);

    private final RuntimeCoordinationApplicationApi coordination;
    private final RuntimeApplicationApi runtime;
    private final RuntimeCoordinationProperties properties;
    private final Map<RuntimeContinuationType, RuntimeContinuationHandler> handlers;
    private final String workerId;

    @Autowired
    public RuntimeContinuationWorker(
            RuntimeCoordinationApplicationApi coordination,
            RuntimeApplicationApi runtime,
            RuntimeCoordinationProperties properties,
            List<RuntimeContinuationHandler> handlers) {
        this.coordination = coordination;
        this.runtime = runtime;
        this.properties = properties;
        this.handlers = handlers.stream().collect(Collectors.toUnmodifiableMap(
                RuntimeContinuationHandler::type, Function.identity()));
        this.workerId = properties.getWorkerId() == null || properties.getWorkerId().isBlank()
                ? "platform-" + UUID.randomUUID()
                : properties.getWorkerId().trim();
    }

    /** Backward-compatible constructor for focused Runtime tests. */
    public RuntimeContinuationWorker(
            RuntimeCoordinationApplicationApi coordination,
            RuntimeApplicationApi runtime,
            RuntimeCoordinationProperties properties) {
        this(coordination, runtime, properties, List.of());
    }

    @Scheduled(fixedDelayString = "${platform.runtime.coordination.poll-delay-ms:500}")
    public void poll() {
        int batch = Math.max(1, Math.min(properties.getBatchSize(), 100));
        for (int index = 0; index < batch && processOne(); index++) {
            // Drain one bounded batch; another replica may claim other rows concurrently.
        }
    }

    public boolean processOne() {
        var claim = coordination.claimNext(
                new RuntimeCoordinationApplicationApi.ClaimNextContinuationCommand(
                        workerId, properties.getLeaseSeconds()));
        if (claim.isEmpty()) {
            return false;
        }
        var continuation = claim.get().continuation();
        var lease = claim.get().lease();
        try {
            if (continuation.type() == RuntimeContinuationType.RESUME_RUN) {
                runtime.resumeFenced(new ResumeAgentRunCommand(
                        continuation.agentRunId(), lease.leaseToken(), lease.fencingToken()));
            } else {
                RuntimeContinuationHandler handler = handlers.get(continuation.type());
                if (handler == null) {
                    throw new IllegalStateException(
                            "No handler for continuation type: " + continuation.type());
                }
                runtime.resumeFenced(new ResumeAgentRunCommand(
                        continuation.agentRunId(), lease.leaseToken(), lease.fencingToken()));
                handler.handle(new RuntimeContinuationHandler.ContinuationHandlerContext(
                        continuation, lease));
                if (handler.completesRun()) {
                    runtime.completeFenced(new CompleteAgentRunFencedCommand(
                            continuation.agentRunId(), lease.leaseToken(), lease.fencingToken()));
                }
            }
            coordination.complete(new RuntimeCoordinationApplicationApi.CompleteContinuationCommand(
                    continuation.id(), workerId, lease.leaseToken(), lease.fencingToken()));
        } catch (RuntimeException error) {
            if (continuation.type() != RuntimeContinuationType.RESUME_RUN) {
                try {
                    failRunIfActive(continuation.agentRunId(), lease, error);
                } catch (RuntimeException stale) {
                    log.warn("Unable to fail continuation Run after lease loss: agentRunId={}",
                            continuation.agentRunId(), stale);
                }
            }
            try {
                coordination.fail(new RuntimeCoordinationApplicationApi.FailContinuationCommand(
                        continuation.id(), workerId, lease.leaseToken(), lease.fencingToken(),
                        abbreviate(error.getMessage(), 1000), properties.getRetryDelaySeconds()));
            } catch (RuntimeException stale) {
                log.warn(
                        "Unable to fail Runtime continuation after lease loss: continuationId={}",
                        continuation.id(), stale);
            }
        }
        return true;
    }

    private void failRunIfActive(
            String agentRunId,
            RuntimeCoordinationApplicationApi.LeaseView lease,
            RuntimeException error) {
        runtime.findRun(agentRunId)
                .filter(run -> run.state()
                        != com.spaceagent.platform.runtime.domain.AgentRunState.COMPLETED)
                .filter(run -> run.state()
                        != com.spaceagent.platform.runtime.domain.AgentRunState.FAILED)
                .filter(run -> run.state()
                        != com.spaceagent.platform.runtime.domain.AgentRunState.CANCELLED)
                .ifPresent(run -> runtime.failFenced(new FailAgentRunFencedCommand(
                        agentRunId, lease.leaseToken(), lease.fencingToken(),
                        abbreviate(error.getMessage(), 1000))));
    }

    private static String abbreviate(String value, int limit) {
        String normalized = value == null || value.isBlank() ? "Continuation failed" : value;
        return normalized.length() <= limit ? normalized : normalized.substring(0, limit);
    }
}

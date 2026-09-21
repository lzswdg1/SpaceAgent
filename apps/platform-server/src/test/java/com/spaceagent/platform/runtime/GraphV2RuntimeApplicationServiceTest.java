package com.spaceagent.platform.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.spaceagent.platform.runtime.api.GraphV2OrchestrationApplicationApi;
import com.spaceagent.platform.runtime.api.GraphV2RuntimeApplicationApi;
import com.spaceagent.platform.runtime.application.GraphV2CommandHasher;
import com.spaceagent.platform.runtime.application.GraphV2OrchestrationApplicationService;
import com.spaceagent.platform.runtime.application.GraphV2RuntimeApplicationService;
import com.spaceagent.platform.runtime.domain.GraphSessionState;
import com.spaceagent.platform.runtime.domain.GraphV2OrchestrationPort;
import com.spaceagent.platform.runtime.domain.RuntimeGraphSession;
import com.spaceagent.platform.runtime.domain.multiagent.GraphV2Contract;
import com.spaceagent.platform.runtime.infrastructure.memory.InMemoryRuntimeGraphSessionRepository;
import com.spaceagent.shared.exception.BusinessException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GraphV2RuntimeApplicationServiceTest {
    private static final Instant NOW = Instant.parse("2026-09-08T00:00:00Z");
    private static final String BUNDLE = "sha256:" + "a".repeat(64);
    private final ObjectMapper json = new ObjectMapper();

    @Test
    void completedBoundaryRequiresCanonicalHashAndExactNextCursor() {
        var repository = new InMemoryRuntimeGraphSessionRepository();
        repository.insert(RuntimeGraphSession.start("session", "tenant", "owner", "run", BUNDLE, NOW));
        var service = new GraphV2RuntimeApplicationService(repository, () -> NOW);
        Map<String, Object> payload = Map.of("summary", "done");
        var result = proposal("session", 0, GraphV2Contract.Kind.COMPLETED, payload);

        assertThat(service.accept(new GraphV2RuntimeApplicationApi.AcceptCommand(
                "tenant", "owner", "session", result)).state())
                .isEqualTo(GraphSessionState.COMPLETED);
        assertThat(service.accept(new GraphV2RuntimeApplicationApi.AcceptCommand(
                "tenant", "owner", "session", result)).replayed()).isTrue();

        var other = new InMemoryRuntimeGraphSessionRepository();
        other.insert(RuntimeGraphSession.start("gap", "tenant", "owner", "run-2", BUNDLE, NOW));
        var gaps = new GraphV2RuntimeApplicationService(other, () -> NOW);
        var invalid = new GraphV2Contract.Result(GraphV2Contract.VERSION, "request", "gap", BUNDLE,
                new GraphV2Contract.Cursor(2, List.of(), "cmd"),
                new GraphV2Contract.Command("cmd", GraphV2Contract.Kind.COMPLETED,
                        GraphV2CommandHasher.hash(json, "gap", 0,
                                GraphV2Contract.Kind.COMPLETED, payload), payload), true);
        assertThatThrownBy(() -> gaps.accept(new GraphV2RuntimeApplicationApi.AcceptCommand(
                "tenant", "owner", "gap", invalid)))
                .isInstanceOfSatisfying(BusinessException.class, error ->
                        assertThat(error.getCode()).isEqualTo("GRAPH_CURSOR_NOT_CONTIGUOUS"));
    }

    @Test
    void effectCommandPersistsCanonicalInputInsteadOfBundleHash() {
        var repository = new InMemoryRuntimeGraphSessionRepository();
        repository.insert(RuntimeGraphSession.start("session", "tenant", "owner", "run", BUNDLE, NOW));
        Map<String, Object> payload = Map.of("toolId", "echo", "arguments", Map.of("value", "ok"));
        var result = proposal("session", 0, GraphV2Contract.Kind.TOOL_REQUESTED, payload);
        var accepted = new GraphV2RuntimeApplicationService(repository, () -> NOW).accept(
                new GraphV2RuntimeApplicationApi.AcceptCommand("tenant", "owner", "session", result));
        assertThat(accepted.state()).isEqualTo(GraphSessionState.WAITING_FOR_COMMAND);
        assertThat(repository.findCommand("session", "cmd").orElseThrow().inputHash())
                .isEqualTo(result.command().inputHash()).isNotEqualTo(BUNDLE);
    }

    @Test
    void startsOneRunBoundSessionAndCoordinatesCorrelatedProposal() {
        var repository = new InMemoryRuntimeGraphSessionRepository();
        var runtime = new GraphV2RuntimeApplicationService(repository, () -> NOW, () -> "session");
        GraphV2OrchestrationPort port = request -> {
            Map<String, Object> payload = Map.of("summary", "done");
            return new GraphV2Contract.Result(GraphV2Contract.VERSION, request.requestId(),
                    request.graphSessionId(), request.bundleHash(),
                    new GraphV2Contract.Cursor(1, List.of(), "cmd"),
                    new GraphV2Contract.Command("cmd", GraphV2Contract.Kind.COMPLETED,
                            GraphV2CommandHasher.hash(json, request.graphSessionId(),
                                    request.cursor().sequence(), GraphV2Contract.Kind.COMPLETED, payload),
                            payload), true);
        };
        var coordinator = new GraphV2OrchestrationApplicationService(runtime, port);
        assertThat(coordinator.transition(new GraphV2OrchestrationApplicationApi.TransitionCommand(
                "tenant", "owner", "run", BUNDLE, 2, 2, 100)).acceptance().state())
                .isEqualTo(GraphSessionState.COMPLETED);
        assertThatThrownBy(() -> runtime.start(new GraphV2RuntimeApplicationApi.StartCommand(
                "tenant", "owner", "run", "sha256:" + "b".repeat(64))))
                .isInstanceOf(BusinessException.class);
    }

    private GraphV2Contract.Result proposal(
            String session, int cursor, GraphV2Contract.Kind kind, Map<String, Object> payload) {
        String hash = GraphV2CommandHasher.hash(json, session, cursor, kind, payload);
        return new GraphV2Contract.Result(GraphV2Contract.VERSION, "request", session, BUNDLE,
                new GraphV2Contract.Cursor(cursor + 1, List.of(), "cmd"),
                new GraphV2Contract.Command("cmd", kind, hash, payload), true);
    }
}

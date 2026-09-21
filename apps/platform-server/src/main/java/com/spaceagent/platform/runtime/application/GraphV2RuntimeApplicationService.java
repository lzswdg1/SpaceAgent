package com.spaceagent.platform.runtime.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.spaceagent.platform.runtime.api.GraphV2RuntimeApplicationApi;
import com.spaceagent.platform.runtime.api.RuntimeCoordinationApplicationApi;
import com.spaceagent.platform.runtime.domain.GraphSessionState;
import com.spaceagent.platform.runtime.domain.RuntimeContinuationType;
import com.spaceagent.platform.runtime.domain.RuntimeGraphCommand;
import com.spaceagent.platform.runtime.domain.RuntimeGraphSession;
import com.spaceagent.platform.runtime.domain.RuntimeGraphSessionRepository;
import com.spaceagent.platform.runtime.domain.multiagent.GraphV2Contract;
import com.spaceagent.shared.exception.BusinessException;
import com.spaceagent.shared.id.IdGenerator;
import com.spaceagent.shared.id.UuidGenerator;
import com.spaceagent.shared.time.TimeProvider;
import java.time.Instant;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class GraphV2RuntimeApplicationService implements GraphV2RuntimeApplicationApi {
    private final RuntimeGraphSessionRepository sessions;
    private final TimeProvider time;
    private final IdGenerator ids;
    private final RuntimeCoordinationApplicationApi coordination;
    private final ObjectMapper json;

    public GraphV2RuntimeApplicationService(RuntimeGraphSessionRepository sessions, TimeProvider time) {
        this(sessions, time, new UuidGenerator(), null, new ObjectMapper());
    }

    public GraphV2RuntimeApplicationService(
            RuntimeGraphSessionRepository sessions, TimeProvider time, IdGenerator ids) {
        this(sessions, time, ids, null, new ObjectMapper());
    }

    @Autowired
    public GraphV2RuntimeApplicationService(
            RuntimeGraphSessionRepository sessions, TimeProvider time, IdGenerator ids,
            RuntimeCoordinationApplicationApi coordination, ObjectMapper json) {
        this.sessions = sessions;
        this.time = time;
        this.ids = ids;
        this.coordination = coordination;
        this.json = json;
    }

    @Override
    @Transactional
    public SessionView start(StartCommand command) {
        var existing = sessions.findByRunId(
                command.tenantId(), command.ownerUserId(), command.agentRunId()).orElse(null);
        if (existing != null) {
            if (!existing.bundleHash().equals(command.bundleHash())) {
                throw error("GRAPH_BUNDLE_CONFLICT", HttpStatus.CONFLICT);
            }
            return view(existing);
        }
        var created = RuntimeGraphSession.start(ids.nextId(), command.tenantId(),
                command.ownerUserId(), command.agentRunId(), command.bundleHash(), time.now());
        try {
            sessions.insert(created);
            return view(created);
        } catch (RuntimeException race) {
            var winner = sessions.findByRunId(
                            command.tenantId(), command.ownerUserId(), command.agentRunId())
                    .orElseThrow(() -> race);
            if (!winner.bundleHash().equals(command.bundleHash())) {
                throw error("GRAPH_BUNDLE_CONFLICT", HttpStatus.CONFLICT);
            }
            return view(winner);
        }
    }

    @Override
    @Transactional
    public Result accept(AcceptCommand command) {
        RuntimeGraphSession session = require(
                command.tenantId(), command.ownerUserId(), command.graphSessionId());
        GraphV2Contract.Result result = command.result();
        if (!session.bundleHash().equals(result.bundleHash())
                || !session.id().equals(result.graphSessionId())) {
            throw error("GRAPH_BUNDLE_OR_SESSION_MISMATCH", HttpStatus.CONFLICT);
        }
        GraphV2Contract.Command proposal = result.command();
        String canonicalPayload = GraphV2CommandHasher.payload(json, proposal.payload());
        var existing = sessions.findCommand(session.id(), proposal.commandId()).orElse(null);
        if (existing != null) {
            if (result.nextCursor().sequence() < 1
                    || !proposal.inputHash().equals(GraphV2CommandHasher.hash(
                    json, session.id(), result.nextCursor().sequence() - 1,
                    proposal.kind(), proposal.payload()))
                    || !same(existing, proposal, result.nextCursor().sequence(), canonicalPayload)) {
                throw error("GRAPH_COMMAND_INPUT_CONFLICT", HttpStatus.CONFLICT);
            }
            return new Result(session.id(), session.state(), session.revision(),
                    proposal.commandId(), true);
        }
        requireContinuousCursor(session, result);
        String inputHash = GraphV2CommandHasher.hash(json, session.id(),
                session.cursorSequence(), proposal.kind(), proposal.payload());
        if (!inputHash.equals(proposal.inputHash())) {
            throw error("GRAPH_COMMAND_INPUT_HASH_MISMATCH", HttpStatus.CONFLICT);
        }
        if (session.state() != GraphSessionState.ACTIVE) {
            throw error("GRAPH_SESSION_NOT_READY", HttpStatus.CONFLICT);
        }
        Instant now = time.now();
        RuntimeGraphSession pending = session.propose(proposal.commandId(), inputHash, now);
        if (!sessions.update(pending, session.revision(), session.state())) {
            throw error("GRAPH_SESSION_STALE", HttpStatus.CONFLICT);
        }
        RuntimeGraphCommand persisted = new RuntimeGraphCommand(
                session.id(), proposal.commandId(), inputHash, proposal.kind(),
                result.nextCursor().sequence(), canonicalPayload, RuntimeGraphCommand.State.PENDING,
                0, null, null, null, null, 1, now, now);
        sessions.insertCommandIfAbsent(persisted);
        if (proposal.kind() == GraphV2Contract.Kind.COMPLETED) {
            if (!sessions.updateCommand(persisted.confirmWithoutEffect(now),
                    persisted.revision(), persisted.state())) {
                throw error("GRAPH_COMMAND_STALE", HttpStatus.CONFLICT);
            }
            RuntimeGraphSession completed = pending.confirm(
                    proposal.commandId(), inputHash, now).complete(now);
            if (!sessions.update(completed, pending.revision(), pending.state())) {
                throw error("GRAPH_SESSION_STALE", HttpStatus.CONFLICT);
            }
            return new Result(completed.id(), completed.state(), completed.revision(),
                    proposal.commandId(), false);
        }
        enqueueExecution(session, persisted, command, now);
        return new Result(pending.id(), pending.state(), pending.revision(),
                proposal.commandId(), false);
    }

    @Override
    @Transactional
    public ExecutionView beginExecution(BeginExecutionCommand command) {
        RuntimeGraphSession session = require(
                command.tenantId(), command.ownerUserId(), command.graphSessionId());
        RuntimeGraphCommand current = requireCommand(session, command.commandId(), command.inputHash());
        if (current.state() == RuntimeGraphCommand.State.CONFIRMED) {
            return execution(session, current, false);
        }
        if (current.state() == RuntimeGraphCommand.State.EXECUTING) {
            RuntimeGraphCommand unknown = current.blockUnknown(time.now());
            updateCommand(unknown, current);
            blockSession(session);
            return execution(session, unknown, false);
        }
        if (current.state() != RuntimeGraphCommand.State.PENDING) {
            return execution(session, current, false);
        }
        RuntimeGraphCommand executing = current.begin(
                command.executionOwner(), command.executionToken(),
                command.executionFencingToken(), time.now());
        updateCommand(executing, current);
        return execution(session, executing, true);
    }

    @Override
    @Transactional
    public SessionView completeExecution(CompleteExecutionCommand command) {
        RuntimeGraphSession session = require(
                command.tenantId(), command.ownerUserId(), command.graphSessionId());
        RuntimeGraphCommand current = requireCommand(session, command.commandId(), command.inputHash());
        RuntimeGraphCommand confirmed;
        try {
            confirmed = current.confirm(command.executionOwner(), command.executionToken(),
                    command.executionFencingToken(), time.now());
        } catch (IllegalStateException stale) {
            throw error("GRAPH_COMMAND_EXECUTION_FENCE_MISMATCH", HttpStatus.CONFLICT);
        }
        updateCommand(confirmed, current);
        RuntimeGraphSession active = session.confirm(command.commandId(), command.inputHash(), time.now());
        if (!sessions.update(active, session.revision(), session.state())) {
            throw error("GRAPH_SESSION_STALE", HttpStatus.CONFLICT);
        }
        enqueueGraphContinuation(active, command, time.now());
        return view(active);
    }

    @Override
    @Transactional
    public SessionView blockExecutionUnknown(BlockExecutionCommand command) {
        RuntimeGraphSession session = require(
                command.tenantId(), command.ownerUserId(), command.graphSessionId());
        RuntimeGraphCommand current = requireCommand(session, command.commandId(), command.inputHash());
        if (current.state() == RuntimeGraphCommand.State.CONFIRMED) return view(session);
        if (current.state() == RuntimeGraphCommand.State.EXECUTING) {
            try {
                current.requireExecution(command.executionOwner(), command.executionToken(),
                        command.executionFencingToken());
            } catch (IllegalStateException stale) {
                throw error("GRAPH_COMMAND_EXECUTION_FENCE_MISMATCH", HttpStatus.CONFLICT);
            }
        }
        RuntimeGraphCommand blocked = current.blockUnknown(time.now());
        updateCommand(blocked, current);
        return view(blockSession(session));
    }

    private void enqueueExecution(RuntimeGraphSession session, RuntimeGraphCommand graphCommand,
                                  AcceptCommand command, Instant now) {
        if (coordination == null) return;
        try {
            var payload = new GraphV2CommandExecutionHandler.Payload(
                    session.tenantId(), session.ownerUserId(), session.agentRunId(), session.id(),
                    graphCommand.commandId(), graphCommand.inputHash(), command.maxDepth(),
                    command.maxAgents(), command.remainingTokenBudget());
            coordination.enqueue(new RuntimeCoordinationApplicationApi.EnqueueContinuationCommand(
                    session.agentRunId(), RuntimeContinuationType.GRAPH_COMMAND_EXECUTION,
                    "graph-command-execution:" + session.id() + ":" + graphCommand.commandId(),
                    json.writeValueAsString(payload), now, 5));
        } catch (BusinessException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new IllegalStateException("Unable to persist graph command execution", exception);
        }
    }

    private void enqueueGraphContinuation(
            RuntimeGraphSession session, CompleteExecutionCommand command, Instant now) {
        if (coordination == null) return;
        try {
            var payload = new GraphV2ContinuationHandler.Payload(
                    command.tenantId(), command.ownerUserId(), session.agentRunId(),
                    session.bundleHash(), command.maxDepth(), command.maxAgents(),
                    command.remainingTokenBudget());
            coordination.enqueue(new RuntimeCoordinationApplicationApi.EnqueueContinuationCommand(
                    session.agentRunId(), RuntimeContinuationType.GRAPH_COMMAND,
                    "graph-command:" + session.id() + ":" + command.commandId(),
                    json.writeValueAsString(payload), now, 5));
        } catch (BusinessException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new IllegalStateException("Unable to persist graph continuation", exception);
        }
    }

    private void requireContinuousCursor(RuntimeGraphSession session, GraphV2Contract.Result result) {
        if (result.nextCursor().sequence() != session.cursorSequence() + 1
                || !result.nextCursor().completedCommandIds().equals(session.completedCommandIds())
                || !result.command().commandId().equals(result.nextCursor().pendingCommandId())) {
            throw error("GRAPH_CURSOR_NOT_CONTIGUOUS", HttpStatus.CONFLICT);
        }
    }

    private static boolean same(RuntimeGraphCommand existing, GraphV2Contract.Command command,
                                long nextCursor, String payloadJson) {
        return existing.inputHash().equals(command.inputHash())
                && existing.kind() == command.kind()
                && existing.nextCursorSequence() == nextCursor
                && existing.payloadJson().equals(payloadJson);
    }

    private RuntimeGraphCommand requireCommand(
            RuntimeGraphSession session, String commandId, String inputHash) {
        if (session.state() != GraphSessionState.WAITING_FOR_COMMAND
                || !commandId.equals(session.pendingCommandId())
                || !inputHash.equals(session.pendingInputHash())) {
            throw error("GRAPH_COMMAND_BOUNDARY_MISMATCH", HttpStatus.CONFLICT);
        }
        return sessions.findCommand(session.id(), commandId)
                .filter(value -> value.inputHash().equals(inputHash))
                .orElseThrow(() -> error("GRAPH_COMMAND_NOT_PENDING", HttpStatus.CONFLICT));
    }

    private void updateCommand(RuntimeGraphCommand next, RuntimeGraphCommand current) {
        if (!sessions.updateCommand(next, current.revision(), current.state())) {
            throw error("GRAPH_COMMAND_STALE", HttpStatus.CONFLICT);
        }
    }

    private RuntimeGraphSession blockSession(RuntimeGraphSession session) {
        RuntimeGraphSession blocked = session.blockUnknown(time.now());
        if (blocked != session && !sessions.update(blocked, session.revision(), session.state())) {
            throw error("GRAPH_SESSION_STALE", HttpStatus.CONFLICT);
        }
        return blocked;
    }

    private RuntimeGraphSession require(String tenantId, String ownerId, String sessionId) {
        return sessions.findById(tenantId, ownerId, sessionId)
                .orElseThrow(() -> error("GRAPH_SESSION_NOT_FOUND", HttpStatus.NOT_FOUND));
    }

    private static ExecutionView execution(
            RuntimeGraphSession session, RuntimeGraphCommand command, boolean execute) {
        return new ExecutionView(session.id(), session.agentRunId(), session.tenantId(),
                session.ownerUserId(), command.commandId(), command.inputHash(), command.kind(),
                command.nextCursorSequence(), command.payloadJson(), execute);
    }

    private static SessionView view(RuntimeGraphSession session) {
        return new SessionView(session.id(), session.agentRunId(), session.bundleHash(),
                session.cursorSequence(), session.completedCommandIds(), session.pendingCommandId(),
                session.state(), session.revision());
    }

    private static BusinessException error(String code, HttpStatus status) {
        return new BusinessException("Graph v2 command rejected", status, code);
    }
}

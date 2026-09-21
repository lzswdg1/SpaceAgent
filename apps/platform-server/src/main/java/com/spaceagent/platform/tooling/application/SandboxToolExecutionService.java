package com.spaceagent.platform.tooling.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.spaceagent.platform.tooling.api.ClaimToolExecutionCommand;
import com.spaceagent.platform.tooling.api.CompleteToolExecutionCommand;
import com.spaceagent.platform.tooling.api.MarkToolExecutionUnknownCommand;
import com.spaceagent.platform.tooling.api.SandboxToolExecutionApplicationApi;
import com.spaceagent.platform.tooling.api.SandboxToolExecutionCommand;
import com.spaceagent.platform.tooling.api.SandboxToolExecutionUnavailableException;
import com.spaceagent.platform.tooling.api.SandboxToolExecutionView;
import com.spaceagent.platform.tooling.api.ToolExecutionClaimView;
import com.spaceagent.platform.tooling.api.ToolExecutionLedgerApplicationApi;
import com.spaceagent.platform.tooling.api.ToolExecutionLedgerView;
import com.spaceagent.platform.tooling.api.ToolExecutionTransitionView;
import com.spaceagent.platform.tooling.domain.SandboxExecutionGateway;
import com.spaceagent.platform.tooling.domain.SandboxExecutionRequest;
import com.spaceagent.platform.tooling.domain.SandboxExecutionResponse;
import com.spaceagent.platform.tooling.domain.SandboxExecutionStatus;
import com.spaceagent.platform.tooling.domain.SandboxExecutionUnavailableException;
import com.spaceagent.platform.tooling.domain.SandboxResourcePolicy;
import com.spaceagent.platform.tooling.domain.ToolExecutionStatus;
import com.spaceagent.platform.tooling.domain.ToolExecutionTransitionType;
import com.spaceagent.shared.id.IdGenerator;
import com.spaceagent.shared.exception.BusinessException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Durable sandbox tool execution coordinator.
 *
 * <p>The ToolExecutionLedger remains authoritative. Every execution opens a ledger entry
 * keyed by {@code (agentRunId, toolCallId)}; an already terminal entry is replayed, and
 * a worker failure leaves an UNKNOWN entry rather than blindly re-executing the side
 * effect.
 */
@Service
public class SandboxToolExecutionService implements SandboxToolExecutionApplicationApi {

    private static final int DEFAULT_MAX_OUTPUT_BYTES = 65_536;
    private static final int DEFAULT_MAX_CPU_SECONDS = 5;
    private static final long DEFAULT_MAX_MEMORY_BYTES = 256L * 1024 * 1024;
    private static final int COMPLETION_PERSISTENCE_SAFETY_SECONDS = 30;

    private final ToolExecutionLedgerApplicationApi ledgerApi;
    private final SandboxExecutionGateway gateway;
    private final IdGenerator idGenerator;
    private final ObjectMapper objectMapper;

    public SandboxToolExecutionService(
            ToolExecutionLedgerApplicationApi ledgerApi,
            SandboxExecutionGateway gateway,
            IdGenerator idGenerator,
            ObjectMapper objectMapper) {
        this.ledgerApi = ledgerApi;
        this.gateway = gateway;
        this.idGenerator = idGenerator;
        this.objectMapper = objectMapper;
    }

    @Override
    public SandboxToolExecutionView execute(SandboxToolExecutionCommand command) {
        String arguments = serializeArguments(command.command(), command.arguments(), command.inputBase64());
        String inputHash = "sha256:" + sha256Hex(arguments);

        ToolExecutionClaimView claim = ledgerApi.claim(new ClaimToolExecutionCommand(
                command.agentRunId(),
                command.runStepId(),
                command.toolName(),
                command.toolCallId(),
                command.idempotencyKey(),
                arguments,
                inputHash,
                leaseSeconds(command.timeoutSeconds())));

        switch (claim.type()) {
            case REPLAY -> {
                return toView(claim.ledger());
            }
            case BUSY -> throw businessConflict(
                    "Tool execution is already in progress",
                    "TOOL_EXECUTION_IN_PROGRESS");
            case UNKNOWN -> throw businessConflict(
                    "Tool execution outcome is unknown and requires reconciliation",
                    "TOOL_EXECUTION_AMBIGUOUS");
            case CONFLICT -> throw businessConflict(
                    "Tool idempotency conflict",
                    "TOOL_IDEMPOTENCY_CONFLICT");
            case CLAIMED -> {
                // The short claim transaction has committed before this external call.
            }
        }

        SandboxExecutionResponse result;
        try {
            result = gateway.execute(toRequest(command));
        } catch (SandboxExecutionUnavailableException error) {
            return handleIndeterminateGatewayFailure(command, claim, error);
        } catch (RuntimeException error) {
            return handleIndeterminateGatewayFailure(command, claim, error);
        }

        ToolExecutionStatus status = mapStatus(result.status());
        String resultPayload = status == ToolExecutionStatus.SUCCEEDED ? result.stdout() : null;
        String resultRef = result.artifactRefs().isEmpty() ? null : result.artifactRefs().get(0);
        ToolExecutionTransitionView completion = ledgerApi.complete(new CompleteToolExecutionCommand(
                command.agentRunId(),
                command.toolCallId(),
                claim.claimToken(),
                claim.revision(),
                status,
                resultPayload,
                resultRef,
                result.error()));
        return terminalResultOrThrow(completion);
    }

    private SandboxToolExecutionView handleIndeterminateGatewayFailure(
            SandboxToolExecutionCommand command,
            ToolExecutionClaimView claim,
            RuntimeException cause) {
        ToolExecutionTransitionView transition = ledgerApi.markUnknown(
                new MarkToolExecutionUnknownCommand(
                        command.agentRunId(),
                        command.toolCallId(),
                        claim.claimToken(),
                        claim.revision(),
                        "sandbox gateway returned no trustworthy terminal outcome"));
        if (transition.type() == ToolExecutionTransitionType.CURRENT_TERMINAL) {
            return toView(transition.ledger());
        }
        if (transition.type() == ToolExecutionTransitionType.CLAIM_LOST) {
            throw businessConflict(
                    "Tool execution claim was lost before ambiguity could be recorded",
                    "TOOL_EXECUTION_CLAIM_LOST");
        }
        throw new SandboxToolExecutionUnavailableException(
                "Sandbox execution outcome is unknown and requires reconciliation",
                cause);
    }

    private SandboxToolExecutionView terminalResultOrThrow(ToolExecutionTransitionView transition) {
        return switch (transition.type()) {
            case APPLIED, CURRENT_TERMINAL -> toView(transition.ledger());
            case CURRENT_UNKNOWN -> throw businessConflict(
                    "Tool execution outcome is unknown and requires reconciliation",
                    "TOOL_EXECUTION_AMBIGUOUS");
            case CLAIM_LOST -> throw businessConflict(
                    "Tool execution claim was lost before completion",
                    "TOOL_EXECUTION_CLAIM_LOST");
            case CONFLICT -> throw businessConflict(
                    "Tool idempotency conflict",
                    "TOOL_IDEMPOTENCY_CONFLICT");
        };
    }

    private long leaseSeconds(int timeoutSeconds) {
        return Math.addExact((long) timeoutSeconds, COMPLETION_PERSISTENCE_SAFETY_SECONDS);
    }

    private SandboxExecutionRequest toRequest(SandboxToolExecutionCommand command) {
        return new SandboxExecutionRequest(
                idGenerator.nextId(),
                command.agentRunId(),
                command.toolCallId(),
                command.workspaceRef(),
                command.taskRef(),
                command.toolName(),
                command.command(),
                command.arguments(),
                command.timeoutSeconds(),
                new SandboxResourcePolicy(
                        DEFAULT_MAX_OUTPUT_BYTES,
                        DEFAULT_MAX_CPU_SECONDS,
                        DEFAULT_MAX_MEMORY_BYTES,
                        false,
                        List.of(".")),
                "{}", command.inputBase64());
    }

    private String serializeArguments(String command, List<String> arguments, String inputBase64) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("command", command);
        payload.put("args", arguments);
        if (inputBase64 != null) payload.put("inputSha256", sha256Hex(inputBase64));
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (Exception error) {
            throw new IllegalStateException("Unable to serialize sandbox tool arguments", error);
        }
    }

    private static String sha256Hex(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException("SHA-256 is not available", error);
        }
    }

    private static ToolExecutionStatus mapStatus(SandboxExecutionStatus status) {
        return switch (status) {
            case SUCCEEDED -> ToolExecutionStatus.SUCCEEDED;
            case TIMED_OUT -> ToolExecutionStatus.TIMED_OUT;
            case FAILED, OUTPUT_LIMIT_EXCEEDED, REJECTED -> ToolExecutionStatus.FAILED;
        };
    }

    private static BusinessException businessConflict(String message, String code) {
        return new BusinessException(message, HttpStatus.CONFLICT, code);
    }

    private static SandboxToolExecutionView toView(ToolExecutionLedgerView entry) {
        return new SandboxToolExecutionView(
                entry.toolCallId(),
                entry.status().name(),
                entry.result(),
                entry.resultRef(),
                entry.error());
    }
}

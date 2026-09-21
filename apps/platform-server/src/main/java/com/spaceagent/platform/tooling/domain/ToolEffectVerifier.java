package com.spaceagent.platform.tooling.domain;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

/** Read-only, locally reviewed proof contract for capability-specific UNKNOWN effects. */
public interface ToolEffectVerifier {
    int MAX_INPUT_BYTES = 200_000;
    int MAX_EVIDENCE_BYTES = 32_000;
    int MAX_TIMEOUT_MILLIS = 30_000;
    Pattern IDENTIFIER = Pattern.compile("[A-Za-z0-9_.:-]{1,160}");
    Pattern SCHEMA_VERSION = Pattern.compile("[A-Za-z0-9_.:/-]{1,160}");
    Pattern SAFE_CODE = Pattern.compile("[A-Z][A-Z0-9_]{2,99}");
    Pattern SHA256 = Pattern.compile("sha256:[0-9a-f]{64}");

    Definition definition();

    Evidence verify(Request request);

    enum EffectKind {
        MCP_MUTATION,
        SANDBOX_COMMAND
    }

    enum Trust {
        LOCAL_REVIEWED,
        REMOTE_DECLARED,
        UNTRUSTED
    }

    enum Verdict {
        PROVEN_APPLIED,
        PROVEN_NOT_APPLIED,
        INCONCLUSIVE
    }

    record Definition(
            String verifierId,
            int verifierVersion,
            EffectKind effectKind,
            String platformToolName,
            String effectSelector,
            Trust trust,
            String evidenceSchemaVersion,
            int maxInputBytes,
            int maxEvidenceBytes,
            int timeoutMillis,
            boolean readOnly) {
        public Definition {
            identifier(verifierId, "verifierId");
            if (verifierVersion <= 0) throw invalid("verifierVersion must be positive");
            Objects.requireNonNull(effectKind, "effectKind");
            identifier(platformToolName, "platformToolName");
            identifier(effectSelector, "effectSelector");
            Objects.requireNonNull(trust, "trust");
            schemaVersion(evidenceSchemaVersion);
            bounded(maxInputBytes, MAX_INPUT_BYTES, "maxInputBytes");
            bounded(maxEvidenceBytes, MAX_EVIDENCE_BYTES, "maxEvidenceBytes");
            bounded(timeoutMillis, MAX_TIMEOUT_MILLIS, "timeoutMillis");
        }

        public String registryKey() {
            return effectKind.name() + ":" + platformToolName + ":" + effectSelector;
        }
    }

    sealed interface Scope permits McpScope, SandboxCommandScope {
        EffectKind effectKind();

        String selector();

        String canonicalValue();
    }

    record McpScope(
            String connectionId,
            long connectionRevision,
            String capabilitySnapshotId,
            String snapshotSha256,
            String remoteToolName) implements Scope {
        public McpScope {
            identifier(connectionId, "connectionId");
            if (connectionRevision <= 0) {
                throw invalid("connectionRevision must be positive");
            }
            identifier(capabilitySnapshotId, "capabilitySnapshotId");
            hash(snapshotSha256, "snapshotSha256");
            identifier(remoteToolName, "remoteToolName");
        }

        @Override
        public EffectKind effectKind() {
            return EffectKind.MCP_MUTATION;
        }

        @Override
        public String selector() {
            return remoteToolName;
        }

        @Override
        public String canonicalValue() {
            return String.join("\n", connectionId, Long.toString(connectionRevision),
                    capabilitySnapshotId, snapshotSha256, remoteToolName);
        }
    }

    record SandboxCommandScope(
            String workspaceId,
            String workspaceRef,
            String taskRef,
            String commandDigest,
            String executable) implements Scope {
        public SandboxCommandScope {
            identifier(workspaceId, "workspaceId");
            if (workspaceRef == null || !workspaceRef.equals("workspaces/" + workspaceId)) {
                throw invalid("workspaceRef is invalid");
            }
            identifier(taskRef, "taskRef");
            hash(commandDigest, "commandDigest");
            if (executable == null || !executable.matches("[A-Za-z0-9._-]{1,100}")) {
                throw invalid("executable is invalid");
            }
        }

        @Override
        public EffectKind effectKind() {
            return EffectKind.SANDBOX_COMMAND;
        }

        @Override
        public String selector() {
            return executable;
        }

        @Override
        public String canonicalValue() {
            return String.join("\n", workspaceId, workspaceRef, taskRef,
                    commandDigest, executable);
        }
    }

    record Request(
            String tenantId,
            String ownerUserId,
            String agentRunId,
            String runStepId,
            String toolExecutionId,
            String toolCallId,
            String platformToolName,
            String ledgerInputHash,
            long expectedLedgerRevision,
            String canonicalInput,
            String canonicalInputSha256,
            Scope scope,
            Instant requestedAt) {
        public Request {
            identifier(tenantId, "tenantId");
            identifier(ownerUserId, "ownerUserId");
            identifier(agentRunId, "agentRunId");
            identifier(runStepId, "runStepId");
            identifier(toolExecutionId, "toolExecutionId");
            identifier(toolCallId, "toolCallId");
            identifier(platformToolName, "platformToolName");
            hash(ledgerInputHash, "ledgerInputHash");
            if (expectedLedgerRevision <= 0) {
                throw invalid("expectedLedgerRevision must be positive");
            }
            canonicalInput = Objects.requireNonNull(canonicalInput, "canonicalInput");
            if (canonicalInput.getBytes(StandardCharsets.UTF_8).length > MAX_INPUT_BYTES) {
                throw invalid("canonicalInput exceeds global limit");
            }
            hash(canonicalInputSha256, "canonicalInputSha256");
            if (!sha256(canonicalInput).equals(canonicalInputSha256)) {
                throw invalid("canonicalInputSha256 does not match canonicalInput");
            }
            Objects.requireNonNull(scope, "scope");
            Objects.requireNonNull(requestedAt, "requestedAt");
        }

        public String subjectSha256() {
            return sha256(String.join("\n", tenantId, ownerUserId, agentRunId,
                    runStepId, toolExecutionId, toolCallId, platformToolName,
                    ledgerInputHash, Long.toString(expectedLedgerRevision),
                    canonicalInputSha256, scope.effectKind().name(), scope.canonicalValue()));
        }

        public void validateFor(Definition definition) {
            if (scope.effectKind() != definition.effectKind()
                    || !platformToolName.equals(definition.platformToolName())
                    || !scope.selector().equals(definition.effectSelector())
                    || canonicalInput.getBytes(StandardCharsets.UTF_8).length
                            > definition.maxInputBytes()) {
                throw invalid("request does not match verifier definition");
            }
        }
    }

    record Evidence(
            String verifierId,
            int verifierVersion,
            String evidenceSchemaVersion,
            Verdict verdict,
            String subjectSha256,
            String observationSha256,
            int observedBytes,
            String safeCode,
            Instant startedAt,
            Instant completedAt) {
        public Evidence {
            identifier(verifierId, "verifierId");
            if (verifierVersion <= 0) throw invalid("verifierVersion must be positive");
            schemaVersion(evidenceSchemaVersion);
            Objects.requireNonNull(verdict, "verdict");
            hash(subjectSha256, "subjectSha256");
            if (observationSha256 != null) hash(observationSha256, "observationSha256");
            if (verdict != Verdict.INCONCLUSIVE && observationSha256 == null) {
                throw invalid("conclusive evidence requires observationSha256");
            }
            if (observedBytes < 0 || observedBytes > MAX_EVIDENCE_BYTES) {
                throw invalid("observedBytes is invalid");
            }
            if (safeCode == null || !SAFE_CODE.matcher(safeCode).matches()) {
                throw invalid("safeCode is invalid");
            }
            Objects.requireNonNull(startedAt, "startedAt");
            Objects.requireNonNull(completedAt, "completedAt");
            if (completedAt.isBefore(startedAt)) {
                throw invalid("completedAt precedes startedAt");
            }
        }

        public void validateFor(Definition definition, Request request) {
            if (!verifierId.equals(definition.verifierId())
                    || verifierVersion != definition.verifierVersion()
                    || !evidenceSchemaVersion.equals(definition.evidenceSchemaVersion())
                    || !subjectSha256.equals(request.subjectSha256())
                    || observedBytes > definition.maxEvidenceBytes()
                    || Duration.between(startedAt, completedAt).toMillis()
                            > definition.timeoutMillis()) {
                throw invalid("evidence does not match verifier definition or request");
            }
        }
    }

    static String sha256(String value) {
        try {
            return "sha256:" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception error) {
            throw new IllegalStateException("SHA-256 unavailable", error);
        }
    }

    static String commandDigest(String executable, List<String> arguments) {
        return sha256(executable + "\0" + String.join("\0", arguments));
    }

    private static void identifier(String value, String field) {
        if (value == null || !IDENTIFIER.matcher(value).matches()) {
            throw invalid(field + " is invalid");
        }
    }

    private static void hash(String value, String field) {
        if (value == null || !SHA256.matcher(value).matches()) {
            throw invalid(field + " is invalid");
        }
    }

    private static void schemaVersion(String value) {
        if (value == null || !SCHEMA_VERSION.matcher(value).matches()) {
            throw invalid("evidenceSchemaVersion is invalid");
        }
    }

    private static void bounded(int value, int maximum, String field) {
        if (value <= 0 || value > maximum) throw invalid(field + " is invalid");
    }

    private static IllegalArgumentException invalid(String message) {
        return new IllegalArgumentException(message);
    }
}

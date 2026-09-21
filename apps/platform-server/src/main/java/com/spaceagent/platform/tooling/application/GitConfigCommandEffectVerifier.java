package com.spaceagent.platform.tooling.application;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.spaceagent.platform.tooling.api.SandboxComputeApplicationApi;
import com.spaceagent.platform.tooling.domain.ToolEffectVerifier;
import com.spaceagent.shared.exception.BusinessException;
import com.spaceagent.shared.time.TimeProvider;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

@Component
public class GitConfigCommandEffectVerifier implements ToolEffectVerifier {
    private static final Set<String> INPUT_KEYS = Set.of(
            "type", "path", "content", "executable", "arguments");
    private static final Set<String> CONFIG_KEYS = Set.of("user.name", "user.email");
    private static final int MAX_OBSERVATION_BYTES = 4_096;
    private static final Definition DEFINITION = new Definition(
            "sandbox.git.config.postcondition", 1, EffectKind.SANDBOX_COMMAND,
            "workspace-run_command", "git", Trust.LOCAL_REVIEWED,
            "git-config-postcondition/v1", 10_000, MAX_OBSERVATION_BYTES,
            10_000, true);

    private final SandboxComputeApplicationApi sandbox;
    private final ObjectMapper json;
    private final TimeProvider time;

    public GitConfigCommandEffectVerifier(
            SandboxComputeApplicationApi sandbox, ObjectMapper json, TimeProvider time) {
        this.sandbox = sandbox;
        this.json = json;
        this.time = time;
    }

    @Override
    public Definition definition() {
        return DEFINITION;
    }

    @Override
    public Evidence verify(Request request) {
        request.validateFor(DEFINITION);
        SandboxCommandScope scope = (SandboxCommandScope) request.scope();
        ExpectedConfig expected = expected(request.canonicalInput(), scope);
        Instant started = time.now();
        SandboxComputeApplicationApi.ComputeResult result;
        try {
            result = sandbox.execute(new SandboxComputeApplicationApi.ComputeCommand(
                    request.agentRunId(),
                    "verify-" + request.subjectSha256().substring(7, 27),
                    scope.workspaceRef(), scope.taskRef(),
                    "workspace-read-command-postcondition", "git",
                    List.of("config", "--get", expected.key()), 10));
        } catch (RuntimeException error) {
            return inconclusive(request, started, "COMMAND_POSTCONDITION_UNAVAILABLE");
        }
        Instant completed = time.now();
        if (result.timedOut() || "UNKNOWN".equals(result.status())) {
            return inconclusive(request, started, "COMMAND_POSTCONDITION_AMBIGUOUS");
        }
        String stdout = result.stdout() == null ? "" : result.stdout();
        String stderr = result.stderr() == null ? "" : result.stderr();
        int bytes = stdout.getBytes(StandardCharsets.UTF_8).length
                + stderr.getBytes(StandardCharsets.UTF_8).length;
        if (bytes > MAX_OBSERVATION_BYTES || stdout.indexOf('\0') >= 0
                || stderr.indexOf('\0') >= 0) {
            throw evidenceInvalid();
        }
        if (result.exitStatus() != 0 && result.exitStatus() != 1) {
            return inconclusive(request, started, "COMMAND_POSTCONDITION_READ_FAILED");
        }
        String observed = stripLineEnding(stdout);
        boolean matches = result.exitStatus() == 0 && stderr.isBlank()
                && expected.value().equals(observed);
        String observation = result.exitStatus() + "\n" + observed + "\n" + stderr;
        return new Evidence(
                DEFINITION.verifierId(), DEFINITION.verifierVersion(),
                DEFINITION.evidenceSchemaVersion(),
                matches ? Verdict.PROVEN_APPLIED : Verdict.PROVEN_NOT_APPLIED,
                request.subjectSha256(), ToolEffectVerifier.sha256(observation), bytes,
                matches ? "POSTCONDITION_MATCHED" : "POSTCONDITION_MISMATCH",
                started, completed);
    }

    private ExpectedConfig expected(String canonicalInput, SandboxCommandScope scope) {
        try {
            Map<String, Object> input = json.readValue(
                    canonicalInput, new TypeReference<Map<String, Object>>() { });
            if (!INPUT_KEYS.containsAll(input.keySet())
                    || !"RUN_COMMAND".equals(string(input.get("type"), 40))
                    || !"git".equals(string(input.get("executable"), 20))) {
                throw unsupported();
            }
            List<String> arguments = json.convertValue(
                    input.get("arguments"), new TypeReference<List<String>>() { });
            if (arguments == null || arguments.size() != 3
                    || !"config".equals(arguments.get(0))
                    || !CONFIG_KEYS.contains(arguments.get(1))) {
                throw unsupported();
            }
            String value = string(arguments.get(2), 320);
            String digest = commandDigest("git", arguments);
            if (!digest.equals(scope.commandDigest())) throw scopeMismatch();
            return new ExpectedConfig(arguments.get(1), value);
        } catch (BusinessException error) {
            throw error;
        } catch (Exception error) {
            throw unsupported();
        }
    }

    public static String commandDigest(String executable, List<String> arguments) {
        return ToolEffectVerifier.commandDigest(executable, arguments);
    }

    private Evidence inconclusive(Request request, Instant started, String code) {
        return new Evidence(
                DEFINITION.verifierId(), DEFINITION.verifierVersion(),
                DEFINITION.evidenceSchemaVersion(), Verdict.INCONCLUSIVE,
                request.subjectSha256(), null, 0, code, started, time.now());
    }

    private static String string(Object value, int max) {
        if (!(value instanceof String text) || text.isBlank() || text.length() > max
                || text.indexOf('\0') >= 0 || text.contains("\r") || text.contains("\n")) {
            throw unsupported();
        }
        return text;
    }

    private static String stripLineEnding(String value) {
        if (value.endsWith("\r\n")) return value.substring(0, value.length() - 2);
        if (value.endsWith("\n")) return value.substring(0, value.length() - 1);
        return value;
    }

    private static BusinessException unsupported() {
        return new BusinessException(
                "Command has no supported exact postcondition", HttpStatus.CONFLICT,
                "TOOL_EFFECT_VERIFIER_UNSUPPORTED");
    }

    private static BusinessException scopeMismatch() {
        return new BusinessException(
                "Command verifier scope is stale or mismatched", HttpStatus.CONFLICT,
                "TOOL_EFFECT_VERIFIER_SCOPE_MISMATCH");
    }

    private static BusinessException evidenceInvalid() {
        return new BusinessException(
                "Command verifier returned unsafe evidence", HttpStatus.CONFLICT,
                "TOOL_EFFECT_VERIFIER_EVIDENCE_INVALID");
    }

    private record ExpectedConfig(String key, String value) {
    }
}

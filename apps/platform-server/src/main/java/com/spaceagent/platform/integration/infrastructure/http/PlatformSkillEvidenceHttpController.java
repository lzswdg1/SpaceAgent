package com.spaceagent.platform.integration.infrastructure.http;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.spaceagent.platform.runtime.api.RuntimeApplicationApi;
import com.spaceagent.shared.api.ApiResponse;
import com.spaceagent.shared.exception.BusinessException;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Exposes a bounded hash-only projection, never the private recovery checkpoint. */
@RestController
public class PlatformSkillEvidenceHttpController {
    private final RuntimeApplicationApi runtime;
    private final ObjectMapper json;

    public PlatformSkillEvidenceHttpController(RuntimeApplicationApi runtime, ObjectMapper json) {
        this.runtime = runtime;
        this.json = json;
    }

    @GetMapping("/api/v1/chat/runs/{runId}/skill-evidence")
    public ApiResponse<Evidence> evidence(@PathVariable UUID runId, Authentication authentication) {
        runtime.findRun(runId.toString())
                .filter(run -> PlatformHttpSupport.tenantId(authentication).equals(run.tenantId())
                        && PlatformHttpSupport.userId(authentication).equals(run.ownerId()))
                .orElseThrow(() -> new BusinessException("Run not found", HttpStatus.NOT_FOUND, "RUN_NOT_FOUND"));
        var checkpoint = runtime.findLatestCheckpointByPhase(runId.toString(), "skill-context-bound");
        if (checkpoint.isEmpty()) return ApiResponse.ok(new Evidence("NO_EVIDENCE", null, List.of()));
        try {
            String raw = checkpoint.get().stateSnapshot();
            if (raw.length() > 32_768) throw new IllegalArgumentException();
            var entries = json.readTree(raw).path("skills");
            if (!entries.isArray() || entries.size() > 16) throw new IllegalArgumentException();
            List<Binding> bindings = new ArrayList<>();
            for (var entry : entries) {
                String id = UUID.fromString(entry.path("skillVersionId").asText()).toString();
                String hash = entry.path("configHash").asText();
                if (!hash.matches("[0-9a-f]{64}")) throw new IllegalArgumentException();
                bindings.add(new Binding(id, hash));
            }
            return ApiResponse.ok(new Evidence("CONTEXT_PREPARED", checkpoint.get().createdAt(), bindings));
        } catch (Exception error) {
            throw new BusinessException("Skill evidence is unavailable", HttpStatus.SERVICE_UNAVAILABLE,
                    "SKILL_EVIDENCE_INVALID");
        }
    }

    public record Binding(String skillVersionId, String configHash) { }
    public record Evidence(String state, Instant preparedAt, List<Binding> skills) { }
}

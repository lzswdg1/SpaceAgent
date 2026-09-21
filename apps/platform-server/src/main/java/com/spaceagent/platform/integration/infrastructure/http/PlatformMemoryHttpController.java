package com.spaceagent.platform.integration.infrastructure.http;

import com.spaceagent.platform.memory.api.CompleteTaskMemoryConsolidationCommand;
import com.spaceagent.platform.memory.api.MemoryApplicationApi;
import com.spaceagent.platform.memory.api.MemoryCandidateView;
import com.spaceagent.platform.memory.api.MemoryRecallCommand;
import com.spaceagent.platform.memory.api.ProposeMemoryCandidateCommand;
import com.spaceagent.platform.memory.api.ReviewMemoryCandidateCommand;
import com.spaceagent.platform.memory.api.ScopedMemoryView;
import com.spaceagent.platform.memory.api.TaskMemoryConsolidationView;
import com.spaceagent.platform.memory.domain.MemoryCandidateState;
import com.spaceagent.platform.memory.domain.MemoryKind;
import com.spaceagent.platform.memory.domain.MemoryScopeRef;
import com.spaceagent.shared.api.ApiResponse;
import com.spaceagent.shared.exception.BusinessException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Public memory read/manage HTTP adapter.
 *
 * <p>The platform memory module owns candidates and consolidated scoped memory;
 * this adapter exposes those application operations without introducing chat
 * side-effect memory writes.
 */
@RestController
@RequestMapping("/api/v1/memory")
public class PlatformMemoryHttpController {

    private final MemoryApplicationApi memoryApi;
    private final PlatformMemoryAuthorizationPolicy authorizationPolicy;

    public PlatformMemoryHttpController(
            MemoryApplicationApi memoryApi,
            PlatformMemoryAuthorizationPolicy authorizationPolicy) {
        this.memoryApi = memoryApi;
        this.authorizationPolicy = authorizationPolicy;
    }

    @GetMapping
    public ApiResponse<List<ScopedMemoryView>> recall(
            @RequestParam(defaultValue = "USER") String scope,
            @RequestParam(defaultValue = "") String scopeId,
            @RequestParam(defaultValue = "50") int limit,
            Authentication authentication) {
        MemoryScopeRef scopeRef = resolveScope(scope, scopeId, authentication);
        authorizationPolicy.requireScopeAccess(
                scopeRef, PlatformHttpSupport.tenantId(authentication), PlatformHttpSupport.userId(authentication));
        return ApiResponse.ok(memoryApi.recall(new MemoryRecallCommand(scopeRef, List.of(), limit)));
    }

    @PostMapping("/candidates")
    public ApiResponse<MemoryCandidateView> propose(
            @Valid @RequestBody ProposeRequest request,
            Authentication authentication) {
        PlatformHttpSupport.requireWrite(authentication);
        authorizationPolicy.requireScopeAccess(
                request.scope(), PlatformHttpSupport.tenantId(authentication), PlatformHttpSupport.userId(authentication));
        return ApiResponse.ok(memoryApi.propose(new ProposeMemoryCandidateCommand(
                request.scope(),
                request.kind(),
                request.sourceId(),
                request.sourceType(),
                request.content(),
                request.confidence(),
                request.dedupeKey())));
    }

    @GetMapping("/candidates")
    public ApiResponse<List<MemoryCandidateView>> candidates(
            @RequestParam(defaultValue = "USER") String scope,
            @RequestParam(defaultValue = "") String scopeId,
            @RequestParam(defaultValue = "50") @Min(1) @Max(100) int limit,
            Authentication authentication) {
        MemoryScopeRef scopeRef = resolveScope(scope, scopeId, authentication);
        authorizationPolicy.requireScopeAccess(
                scopeRef, PlatformHttpSupport.tenantId(authentication), PlatformHttpSupport.userId(authentication));
        return ApiResponse.ok(memoryApi.pending(scopeRef).stream().limit(limit).toList());
    }

    @GetMapping("/candidates/{candidateId}")
    public ApiResponse<MemoryCandidateView> candidate(
            @PathVariable String candidateId,
            Authentication authentication) {
        return ApiResponse.ok(requireCandidate(candidateId, authentication));
    }

    @PostMapping("/candidates/{candidateId}/review")
    public ApiResponse<MemoryCandidateView> review(
            @PathVariable String candidateId,
            @Valid @RequestBody ReviewRequest request,
            Authentication authentication) {
        PlatformHttpSupport.requireWrite(authentication);
        requireCandidate(candidateId, authentication);
        return ApiResponse.ok(memoryApi.review(new ReviewMemoryCandidateCommand(
                candidateId,
                request.decision(),
                request.reason())));
    }

    @PostMapping("/consolidate")
    public ApiResponse<TaskMemoryConsolidationView> consolidate(
            @Valid @RequestBody ConsolidateRequest request,
            Authentication authentication) {
        PlatformHttpSupport.requireWrite(authentication);
        String userId = PlatformHttpSupport.userId(authentication);
        if (request.userId() != null && !request.userId().isBlank()) {
            authorizationPolicy.requireScopeAccess(MemoryScopeRef.user(request.userId()), PlatformHttpSupport.tenantId(authentication), userId);
        }
        String projectId = authorizationPolicy.requireTaskProjectAccess(
                request.taskId(), request.projectId(), PlatformHttpSupport.tenantId(authentication), userId);
        return ApiResponse.ok(memoryApi.consolidateTask(new CompleteTaskMemoryConsolidationCommand(
                request.taskId(),
                projectId,
                userId,
                request.acceptThreshold(),
                request.promotionThreshold())));
    }

    private MemoryCandidateView requireCandidate(
            String candidateId,
            Authentication authentication) {
        MemoryCandidateView candidate = memoryApi.candidate(candidateId)
                .orElseThrow(() -> new BusinessException("Memory not found", HttpStatus.NOT_FOUND));
        authorizationPolicy.requireScopeAccess(
                candidate.scope(), PlatformHttpSupport.tenantId(authentication), PlatformHttpSupport.userId(authentication));
        return candidate;
    }

    private MemoryScopeRef resolveScope(String scope, String scopeId, Authentication authentication) {
        String normalizedScope = scope == null || scope.isBlank() ? "USER" : scope.trim().toUpperCase();
        String resolvedId = scopeId == null || scopeId.isBlank()
                ? PlatformHttpSupport.userId(authentication)
                : scopeId;
        return switch (normalizedScope) {
            case "USER" -> MemoryScopeRef.user(resolvedId);
            case "PROJECT" -> MemoryScopeRef.project(resolvedId);
            case "TASK" -> MemoryScopeRef.task(resolvedId);
            default -> throw new IllegalArgumentException("Unsupported memory scope: " + scope);
        };
    }

    public record ProposeRequest(
            @NotNull MemoryScopeRef scope,
            @NotNull MemoryKind kind,
            @NotBlank @Size(max = 160) String sourceId,
            @NotBlank @Size(max = 120) String sourceType,
            @NotBlank @Size(max = 32_000) String content,
            @DecimalMin("0.0") @DecimalMax("1.0") double confidence,
            @Size(max = 512) String dedupeKey) {
    }

    public record ReviewRequest(
            @NotNull MemoryCandidateState decision,
            @Size(max = 2_000) String reason) {
    }

    public record ConsolidateRequest(
            @NotBlank @Size(max = 160) String taskId,
            @Size(max = 160) String projectId,
            @Size(max = 160) String userId,
            @DecimalMin("0.0") @DecimalMax("1.0") double acceptThreshold,
            @DecimalMin("0.0") @DecimalMax("1.0") double promotionThreshold) {
    }
}

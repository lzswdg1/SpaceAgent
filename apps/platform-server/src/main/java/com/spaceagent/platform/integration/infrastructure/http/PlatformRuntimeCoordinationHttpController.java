package com.spaceagent.platform.integration.infrastructure.http;

import com.spaceagent.platform.runtime.api.RuntimeCoordinationApplicationApi;
import com.spaceagent.platform.runtime.domain.RuntimeContinuationType;
import com.spaceagent.shared.api.ApiResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;

/** Internal worker transport; security requires ROLE_INTERNAL before this adapter runs. */
@RestController
@RequestMapping("/api/v1/internal/runtime")
public class PlatformRuntimeCoordinationHttpController {

    private final RuntimeCoordinationApplicationApi api;

    public PlatformRuntimeCoordinationHttpController(RuntimeCoordinationApplicationApi api) {
        this.api = api;
    }

    @PostMapping("/leases/acquire")
    public ApiResponse<RuntimeCoordinationApplicationApi.LeaseClaimView> acquire(
            @Valid @RequestBody AcquireLeaseRequest request) {
        return ApiResponse.ok(api.acquireLease(
                new RuntimeCoordinationApplicationApi.AcquireLeaseCommand(
                        request.agentRunId(), request.leaseOwner(), request.leaseSeconds())));
    }

    @PostMapping("/leases/heartbeat")
    public ApiResponse<RuntimeCoordinationApplicationApi.LeaseView> heartbeatLease(
            @Valid @RequestBody LeaseIdentityRequest request) {
        return ApiResponse.ok(api.heartbeatLease(
                new RuntimeCoordinationApplicationApi.HeartbeatLeaseCommand(
                        request.agentRunId(), request.leaseOwner(), request.leaseToken(),
                        request.fencingToken(), request.leaseSeconds())));
    }

    @PostMapping("/leases/release")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void release(@Valid @RequestBody ReleaseLeaseRequest request) {
        api.releaseLease(new RuntimeCoordinationApplicationApi.ReleaseLeaseCommand(
                request.agentRunId(), request.leaseOwner(), request.leaseToken(),
                request.fencingToken()));
    }

    @PostMapping("/continuations")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<RuntimeCoordinationApplicationApi.ContinuationView> enqueue(
            @Valid @RequestBody EnqueueContinuationRequest request) {
        return ApiResponse.ok(api.enqueue(
                new RuntimeCoordinationApplicationApi.EnqueueContinuationCommand(
                        request.agentRunId(), request.type(), request.deduplicationKey(),
                        request.payload(), request.availableAt(), request.maxAttempts())));
    }

    @PostMapping("/continuations/claim")
    public ResponseEntity<ApiResponse<RuntimeCoordinationApplicationApi.ContinuationClaimView>> claim(
            @Valid @RequestBody ClaimRequest request) {
        return api.claimNext(new RuntimeCoordinationApplicationApi.ClaimNextContinuationCommand(
                        request.leaseOwner(), request.leaseSeconds()))
                .map(value -> ResponseEntity.ok(ApiResponse.ok(value)))
                .orElseGet(() -> ResponseEntity.noContent().build());
    }

    @PostMapping("/continuations/{continuationId}/heartbeat")
    public ApiResponse<RuntimeCoordinationApplicationApi.ContinuationClaimView> heartbeat(
            @PathVariable String continuationId,
            @Valid @RequestBody ContinuationClaimRequest request) {
        return ApiResponse.ok(api.heartbeat(
                new RuntimeCoordinationApplicationApi.HeartbeatContinuationCommand(
                        continuationId, request.leaseOwner(), request.claimToken(),
                        request.fencingToken(), request.leaseSeconds())));
    }

    @PostMapping("/continuations/{continuationId}/complete")
    public ApiResponse<RuntimeCoordinationApplicationApi.ContinuationView> complete(
            @PathVariable String continuationId,
            @Valid @RequestBody CompleteContinuationRequest request) {
        return ApiResponse.ok(api.complete(
                new RuntimeCoordinationApplicationApi.CompleteContinuationCommand(
                        continuationId, request.leaseOwner(), request.claimToken(),
                        request.fencingToken())));
    }

    @PostMapping("/continuations/{continuationId}/fail")
    public ApiResponse<RuntimeCoordinationApplicationApi.ContinuationView> fail(
            @PathVariable String continuationId,
            @Valid @RequestBody FailContinuationRequest request) {
        return ApiResponse.ok(api.fail(new RuntimeCoordinationApplicationApi.FailContinuationCommand(
                continuationId, request.leaseOwner(), request.claimToken(),
                request.fencingToken(), request.error(), request.retryDelaySeconds())));
    }

    @GetMapping("/continuations/{continuationId}")
    public ResponseEntity<ApiResponse<RuntimeCoordinationApplicationApi.ContinuationView>> find(
            @PathVariable String continuationId) {
        return api.find(continuationId)
                .map(value -> ResponseEntity.ok(ApiResponse.ok(value)))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    public record AcquireLeaseRequest(
            @NotBlank String agentRunId,
            @NotBlank String leaseOwner,
            @Min(1) @Max(300) int leaseSeconds) {}

    public record LeaseIdentityRequest(
            @NotBlank String agentRunId,
            @NotBlank String leaseOwner,
            @NotBlank String leaseToken,
            @Min(1) long fencingToken,
            @Min(1) @Max(300) int leaseSeconds) {}

    public record ReleaseLeaseRequest(
            @NotBlank String agentRunId,
            @NotBlank String leaseOwner,
            @NotBlank String leaseToken,
            @Min(1) long fencingToken) {}

    public record EnqueueContinuationRequest(
            @NotBlank String agentRunId,
            @NotNull RuntimeContinuationType type,
            @NotBlank String deduplicationKey,
            String payload,
            Instant availableAt,
            @Min(1) @Max(20) int maxAttempts) {}

    public record ClaimRequest(
            @NotBlank String leaseOwner,
            @Min(1) @Max(300) int leaseSeconds) {}

    public record ContinuationClaimRequest(
            @NotBlank String leaseOwner,
            @NotBlank String claimToken,
            @Min(1) long fencingToken,
            @Min(1) @Max(300) int leaseSeconds) {}

    public record CompleteContinuationRequest(
            @NotBlank String leaseOwner,
            @NotBlank String claimToken,
            @Min(1) long fencingToken) {}

    public record FailContinuationRequest(
            @NotBlank String leaseOwner,
            @NotBlank String claimToken,
            @Min(1) long fencingToken,
            @NotBlank String error,
            @Min(0) @Max(3600) int retryDelaySeconds) {}
}

package com.spaceagent.platform.integration.infrastructure.http;

import com.spaceagent.platform.runtime.api.ProjectRecoveryApplicationApi;
import com.spaceagent.platform.integration.application.PublicRecoveryProjection;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.spaceagent.shared.api.ApiResponse;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@Validated
@RestController
@RequestMapping("/api/v1/projects/{projectId}/runs/{agentRunId}/recovery-packages")
public class PlatformProjectRecoveryHttpController {

    private final ProjectRecoveryApplicationApi recovery;
    private final ObjectMapper mapper;

    public PlatformProjectRecoveryHttpController(ProjectRecoveryApplicationApi recovery,ObjectMapper mapper) {
        this.recovery = recovery;
        this.mapper = mapper;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<JsonNode> capture(
            @PathVariable String projectId,
            @PathVariable String agentRunId,
            @RequestHeader("Idempotency-Key")
            @NotBlank @Size(min = 8, max = 200) String idempotencyKey,
            Authentication authentication) {
        PlatformHttpSupport.requireWrite(authentication);
        return present(recovery.capture(new ProjectRecoveryApplicationApi.CaptureCommand(
                PlatformHttpSupport.tenantId(authentication),
                PlatformHttpSupport.userId(authentication), projectId, agentRunId,
                idempotencyKey)));
    }

    @GetMapping("/latest")
    public ApiResponse<JsonNode> latest(
            @PathVariable String projectId,
            @PathVariable String agentRunId,
            Authentication authentication) {
        return present(recovery.latest(new ProjectRecoveryApplicationApi.LatestQuery(
                PlatformHttpSupport.tenantId(authentication),
                PlatformHttpSupport.userId(authentication), projectId, agentRunId)));
    }

    @GetMapping("/{snapshotId}")
    public ApiResponse<JsonNode> get(
            @PathVariable String projectId,
            @PathVariable String agentRunId,
            @PathVariable String snapshotId,
            Authentication authentication) {
        return present(recovery.get(new ProjectRecoveryApplicationApi.Query(
                PlatformHttpSupport.tenantId(authentication),
                PlatformHttpSupport.userId(authentication), projectId, agentRunId,
                snapshotId)));
    }

    private ApiResponse<JsonNode> present(ProjectRecoveryApplicationApi.CodingRecoveryPackageView value){
        return ApiResponse.ok(PublicRecoveryProjection.redact(mapper.valueToTree(value)));
    }
}

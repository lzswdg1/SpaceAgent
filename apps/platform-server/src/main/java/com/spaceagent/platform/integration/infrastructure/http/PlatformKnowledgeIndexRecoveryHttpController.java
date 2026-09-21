package com.spaceagent.platform.integration.infrastructure.http;
import com.spaceagent.platform.knowledge.api.*;
import com.spaceagent.shared.api.ApiResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController @ConditionalOnProperty(prefix="platform",name="persistence",havingValue="postgres")
public class PlatformKnowledgeIndexRecoveryHttpController {
    private final KnowledgeIndexRecoveryApplicationApi api;
    public PlatformKnowledgeIndexRecoveryHttpController(KnowledgeIndexRecoveryApplicationApi api){this.api=api;}
    @PostMapping("/api/v1/knowledge/index-jobs/{jobId}/reconcile")
    public ApiResponse<KnowledgeIndexJobApplicationApi.JobView> reconcile(@PathVariable String jobId,@Valid @RequestBody Request r,Authentication a){return ApiResponse.ok(api.reconcile(new KnowledgeBaseApplicationApi.Actor(PlatformHttpSupport.userId(a),PlatformHttpSupport.tenantId(a)),jobId,r.expectedRevision()));}
    public record Request(@Min(1) long expectedRevision){}
}

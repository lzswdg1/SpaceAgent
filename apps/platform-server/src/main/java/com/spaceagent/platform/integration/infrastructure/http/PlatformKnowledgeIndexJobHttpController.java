package com.spaceagent.platform.integration.infrastructure.http;

import com.spaceagent.platform.knowledge.api.KnowledgeBaseApplicationApi;
import com.spaceagent.platform.knowledge.api.KnowledgeIndexJobApplicationApi;
import com.spaceagent.platform.knowledge.domain.KnowledgeIndexJob;
import com.spaceagent.shared.api.ApiResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import java.util.List;

@RestController
@RequestMapping("/api/v1/knowledge")
public class PlatformKnowledgeIndexJobHttpController {
    private final KnowledgeIndexJobApplicationApi api;
    public PlatformKnowledgeIndexJobHttpController(KnowledgeIndexJobApplicationApi api) { this.api=api; }
    @GetMapping("/bases/{baseId}/index-jobs")
    public ApiResponse<List<KnowledgeIndexJobApplicationApi.JobView>> list(@PathVariable String baseId,
            @RequestParam(defaultValue="0") int offset,@RequestParam(defaultValue="50") int limit,Authentication auth) {
        return ApiResponse.ok(api.list(actor(auth),baseId,offset,limit));
    }
    @GetMapping("/index-jobs/{jobId}")
    public ApiResponse<KnowledgeIndexJobApplicationApi.JobView> get(@PathVariable String jobId,Authentication auth) {
        return ApiResponse.ok(api.get(actor(auth),jobId));
    }
    @GetMapping("/index-jobs/{jobId}/batches")
    public ApiResponse<List<KnowledgeIndexJobApplicationApi.BatchView>> batches(@PathVariable String jobId,
            @RequestParam KnowledgeIndexJob.Stage stage,@RequestParam(defaultValue="0") int offset,
            @RequestParam(defaultValue="50") int limit,Authentication auth) {
        return ApiResponse.ok(api.batches(actor(auth),jobId,stage,offset,limit));
    }
    @PostMapping("/index-jobs/{jobId}/cancel")
    public ApiResponse<KnowledgeIndexJobApplicationApi.JobView> cancel(@PathVariable String jobId,
            @Valid @RequestBody RevisionRequest request,Authentication auth) {
        return ApiResponse.ok(api.cancel(actor(auth),jobId,request.expectedRevision()));
    }
    @PostMapping("/index-jobs/{jobId}/retry")
    public ApiResponse<KnowledgeIndexJobApplicationApi.JobView> retry(@PathVariable String jobId,
            @Valid @RequestBody RevisionRequest request,Authentication auth) {
        return ApiResponse.ok(api.retry(actor(auth),jobId,request.expectedRevision()));
    }
    private static KnowledgeBaseApplicationApi.Actor actor(Authentication auth) {
        return new KnowledgeBaseApplicationApi.Actor(PlatformHttpSupport.userId(auth),PlatformHttpSupport.tenantId(auth));
    }
    public record RevisionRequest(@Min(1) long expectedRevision) {}
}

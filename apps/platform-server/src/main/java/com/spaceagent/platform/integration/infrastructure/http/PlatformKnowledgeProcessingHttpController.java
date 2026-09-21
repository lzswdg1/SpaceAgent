package com.spaceagent.platform.integration.infrastructure.http;

import com.spaceagent.platform.knowledge.api.*;
import com.spaceagent.platform.knowledge.domain.*;
import com.spaceagent.shared.api.ApiResponse;
import com.spaceagent.shared.exception.BusinessException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import java.util.List;

@RestController @ConditionalOnProperty(prefix="platform",name="persistence",havingValue="postgres")
@RequestMapping("/api/v1/knowledge/bases/{baseId}")
public class PlatformKnowledgeProcessingHttpController {
    private final KnowledgeProcessingApplicationApi api;
    public PlatformKnowledgeProcessingHttpController(KnowledgeProcessingApplicationApi api){this.api=api;}
    @GetMapping("/processing-policy") public ApiResponse<KnowledgeProcessingApplicationApi.PolicyView> policy(@PathVariable String baseId,Authentication a){return ApiResponse.ok(api.policy(actor(a),baseId));}
    @PutMapping("/processing-policy") public ApiResponse<KnowledgeProcessingApplicationApi.PolicyView> configure(@PathVariable String baseId,@Valid @RequestBody Configure r,Authentication a){return ApiResponse.ok(api.configure(actor(a),baseId,r.expectedRevision(),policy(r.policy())));}
    @PostMapping("/chunk-preview") public ApiResponse<List<KnowledgeDocumentChunker.Segment>> preview(@PathVariable String baseId,@Valid @RequestBody Preview r,Authentication a){return ApiResponse.ok(api.preview(actor(a),baseId,r.content(),policy(r.policy())));}
    public record Policy(@NotNull KnowledgeProcessingPolicy.Strategy strategy,@Min(64) @Max(2048) int size,@Min(0) @Max(1024) int overlap){}
    public record Configure(@Min(0) long expectedRevision,@NotNull @Valid Policy policy){}
    public record Preview(@NotBlank @Size(max=64000) String content,@NotNull @Valid Policy policy){}
    private static KnowledgeProcessingPolicy policy(Policy p){try{return new KnowledgeProcessingPolicy(p.strategy(),p.size(),p.overlap());}catch(IllegalArgumentException e){throw new BusinessException("Invalid processing policy",HttpStatus.BAD_REQUEST,"KNOWLEDGE_POLICY_INVALID");}}
    private static KnowledgeBaseApplicationApi.Actor actor(Authentication a){return new KnowledgeBaseApplicationApi.Actor(PlatformHttpSupport.userId(a),PlatformHttpSupport.tenantId(a));}
}

package com.spaceagent.platform.integration.infrastructure.http;

import com.spaceagent.platform.knowledge.api.*;
import com.spaceagent.shared.api.ApiResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import java.util.List;

@RestController @ConditionalOnProperty(prefix="platform",name="persistence",havingValue="postgres")
@RequestMapping("/api/v1/knowledge")
public class PlatformKnowledgeCollectionRetrievalHttpController {
    private final KnowledgeCollectionRetrievalApi api;
    public PlatformKnowledgeCollectionRetrievalHttpController(KnowledgeCollectionRetrievalApi api){this.api=api;}
    @PostMapping("/collections/retrieve") public ApiResponse<KnowledgeCollectionRetrievalApi.Result> retrieve(
            @RequestHeader("Idempotency-Key") String key,@RequestBody @Valid Body body,Authentication a){
        return ApiResponse.ok(api.retrieve(new KnowledgeCollectionRetrievalApi.Query(new KnowledgeBaseApplicationApi.Actor(
                PlatformHttpSupport.userId(a),PlatformHttpSupport.tenantId(a)),body.baseIds(),body.query(),body.topK(),body.maxContextTokens(),key)));
    }
    public record Body(@NotEmpty @Size(max=8) List<@NotBlank String> baseIds,@NotBlank @Size(max=4000) String query,
                       @Min(1) @Max(20) int topK,@Min(64) @Max(16384) int maxContextTokens){}
}

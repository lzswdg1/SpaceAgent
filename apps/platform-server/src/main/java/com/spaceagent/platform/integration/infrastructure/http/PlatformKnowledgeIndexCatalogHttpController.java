package com.spaceagent.platform.integration.infrastructure.http;

import com.spaceagent.platform.knowledge.api.KnowledgeBaseApplicationApi;
import com.spaceagent.platform.knowledge.api.KnowledgeIndexCatalogApplicationApi;
import com.spaceagent.platform.knowledge.domain.KnowledgeDocumentScope;
import com.spaceagent.shared.api.ApiResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import java.util.List;

@RestController
@RequestMapping("/api/v1/knowledge/bases/{baseId}")
public class PlatformKnowledgeIndexCatalogHttpController {
    private final KnowledgeIndexCatalogApplicationApi api;
    public PlatformKnowledgeIndexCatalogHttpController(KnowledgeIndexCatalogApplicationApi api){this.api=api;}
    @PostMapping("/embedding-spaces") @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<KnowledgeIndexCatalogApplicationApi.SpaceView> register(@PathVariable String baseId,
            @Valid @RequestBody RegisterRequest r,Authentication auth) {
        return ApiResponse.ok(api.registerSpace(new KnowledgeIndexCatalogApplicationApi.RegisterSpaceCommand(actor(auth),
                baseId,r.providerId(),r.modelId(),r.modelRevision(),r.dimensions(),r.preprocessingHash())));
    }
    @GetMapping("/embedding-spaces")
    public ApiResponse<List<KnowledgeIndexCatalogApplicationApi.SpaceView>> spaces(@PathVariable String baseId,
            @RequestParam(defaultValue="0") int offset,@RequestParam(defaultValue="50") int limit,Authentication auth) {
        return ApiResponse.ok(api.spaces(actor(auth),baseId,offset,limit));
    }
    @GetMapping("/documents")
    public ApiResponse<List<KnowledgeDocumentScope>> documents(@PathVariable String baseId,
            @RequestParam(defaultValue="0") int offset,@RequestParam(defaultValue="50") int limit,Authentication auth) {
        return ApiResponse.ok(api.documents(actor(auth),baseId,offset,limit));
    }
    @GetMapping("/documents/{documentId}/index-generations")
    public ApiResponse<List<KnowledgeIndexCatalogApplicationApi.GenerationView>> generations(@PathVariable String baseId,
            @PathVariable String documentId,@RequestParam(defaultValue="0") int offset,
            @RequestParam(defaultValue="50") int limit,Authentication auth) {
        return ApiResponse.ok(api.generations(actor(auth),baseId,documentId,offset,limit));
    }
    private static KnowledgeBaseApplicationApi.Actor actor(Authentication a){return new KnowledgeBaseApplicationApi.Actor(
            PlatformHttpSupport.userId(a),PlatformHttpSupport.tenantId(a));}
    public record RegisterRequest(@NotBlank @Size(max=100) String providerId,@NotBlank @Size(max=160) String modelId,
            @NotBlank @Size(max=160) String modelRevision,@Min(1) @Max(32768) int dimensions,
            @NotBlank @Pattern(regexp="[0-9a-f]{64}") String preprocessingHash) {}
}

package com.spaceagent.platform.integration.infrastructure.http;
import com.spaceagent.platform.knowledge.api.*;
import com.spaceagent.shared.api.ApiResponse;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController @ConditionalOnProperty(prefix="platform",name="persistence",havingValue="postgres")
@RequestMapping("/api/v1/knowledge/bases/{baseId}/indexed-documents/{documentId}")
public class PlatformKnowledgeIndexDeletionHttpController {
    private final KnowledgeIndexDeletionApplicationApi api;
    public PlatformKnowledgeIndexDeletionHttpController(KnowledgeIndexDeletionApplicationApi api){this.api=api;}
    @DeleteMapping @ResponseStatus(HttpStatus.ACCEPTED)
    public ApiResponse<KnowledgeIndexDeletionApplicationApi.View> delete(@PathVariable String baseId,@PathVariable String documentId,@RequestParam long expectedRevision,Authentication a){return ApiResponse.ok(api.delete(actor(a),baseId,documentId,expectedRevision));}
    @GetMapping("/deletion")
    public ApiResponse<KnowledgeIndexDeletionApplicationApi.View> status(@PathVariable String baseId,@PathVariable String documentId,Authentication a){return ApiResponse.ok(api.status(actor(a),baseId,documentId));}
    private static KnowledgeBaseApplicationApi.Actor actor(Authentication a){return new KnowledgeBaseApplicationApi.Actor(PlatformHttpSupport.userId(a),PlatformHttpSupport.tenantId(a));}
}

package com.spaceagent.platform.integration.infrastructure.http;

import com.spaceagent.platform.knowledge.api.*;
import com.spaceagent.shared.api.ApiResponse;
import com.spaceagent.shared.exception.BusinessException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import java.io.IOException;

@RestController
@ConditionalOnProperty(prefix="platform",name="persistence",havingValue="postgres")
@RequestMapping("/api/v1/knowledge/bases/{baseId}/indexed-documents")
public class PlatformKnowledgeIndexIntakeHttpController {
    private final KnowledgeIndexIntakeApplicationApi api;
    public PlatformKnowledgeIndexIntakeHttpController(KnowledgeIndexIntakeApplicationApi api){this.api=api;}
    /** Raw file bytes, deliberately bounded before materializing the complete HTTP body. */
    @PostMapping(consumes={"text/plain","text/markdown","text/html","application/pdf","application/vnd.openxmlformats-officedocument.wordprocessingml.document"}) @ResponseStatus(HttpStatus.ACCEPTED)
    public ApiResponse<KnowledgeIndexIntakeApplicationApi.IntakeView> upload(@PathVariable String baseId,
            @RequestHeader("Idempotency-Key") String key,@RequestParam String name,@RequestParam String spaceId,
            @RequestParam(required=false) String documentId,@RequestParam(defaultValue="0") long expectedRevision,
            HttpServletRequest request,Authentication auth) throws IOException {
        byte[] bytes=request.getInputStream().readNBytes(8_000_001);
        if(bytes.length>8_000_000) throw new BusinessException("Knowledge file exceeds upload bound",HttpStatus.PAYLOAD_TOO_LARGE,"KNOWLEDGE_FILE_TOO_LARGE");
        String media=request.getContentType().split(";",2)[0].trim().toLowerCase(java.util.Locale.ROOT);
        return ApiResponse.ok(api.file(new KnowledgeIndexIntakeApplicationApi.FileCommand(actor(auth),baseId,documentId,expectedRevision,name,spaceId,media,bytes,key)));
    }
    @PostMapping("/from-url-snapshot") @ResponseStatus(HttpStatus.ACCEPTED)
    public ApiResponse<KnowledgeIndexIntakeApplicationApi.IntakeView> fromUrl(@PathVariable String baseId,
            @RequestHeader("Idempotency-Key") String key,@Valid @RequestBody UrlRequest r,Authentication auth) {
        return ApiResponse.ok(api.urlSnapshot(new KnowledgeIndexIntakeApplicationApi.UrlCommand(actor(auth),baseId,r.documentId(),r.expectedRevision(),
                r.name(),r.spaceId(),r.urlJobId(),r.versionId(),key)));
    }
    @GetMapping("/{documentId}")
    public ApiResponse<KnowledgeIndexIntakeApplicationApi.DocumentView> get(@PathVariable String baseId,@PathVariable String documentId,Authentication auth){
        return ApiResponse.ok(api.document(actor(auth),baseId,documentId));
    }
    private static KnowledgeBaseApplicationApi.Actor actor(Authentication a){return new KnowledgeBaseApplicationApi.Actor(PlatformHttpSupport.userId(a),PlatformHttpSupport.tenantId(a));}
    public record UrlRequest(@Size(max=36) String documentId,@Min(0) @Max(999999999) long expectedRevision,
                             @NotBlank @Size(max=255) String name,@NotBlank @Size(max=36) String spaceId,
                             @NotBlank @Size(max=36) String urlJobId,@NotBlank @Size(max=36) String versionId) {}
}

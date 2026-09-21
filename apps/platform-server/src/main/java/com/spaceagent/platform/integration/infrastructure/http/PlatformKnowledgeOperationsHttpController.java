package com.spaceagent.platform.integration.infrastructure.http;
import com.spaceagent.platform.knowledge.api.*;
import com.spaceagent.shared.api.ApiResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import java.util.List;

@RestController @RequestMapping("/api/v1/knowledge") @ConditionalOnProperty(prefix="platform",name="persistence",havingValue="postgres")
public class PlatformKnowledgeOperationsHttpController {
    private final KnowledgeIndexRepairApi repair;private final KnowledgeLegacyMigrationApi legacy;
    public PlatformKnowledgeOperationsHttpController(KnowledgeIndexRepairApi repair,KnowledgeLegacyMigrationApi legacy){this.repair=repair;this.legacy=legacy;}
    @PostMapping("/bases/{base}/indexed-documents/{doc}/repair") @ResponseStatus(HttpStatus.ACCEPTED)
    public ApiResponse<KnowledgeIndexRepairApi.View> repair(@PathVariable String base,@PathVariable String doc,@Valid @RequestBody Repair body,@RequestHeader("Idempotency-Key") String key,Authentication a){return ApiResponse.ok(repair.request(actor(a),base,doc,body.generationId(),key));}
    @GetMapping("/index-repairs/{id}") public ApiResponse<KnowledgeIndexRepairApi.View> repair(@PathVariable String id,Authentication a){return ApiResponse.ok(repair.get(actor(a),id));}
    @GetMapping("/bases/{base}/legacy-migration") public ApiResponse<List<KnowledgeLegacyMigrationApi.Item>> inspect(@PathVariable String base,@RequestParam(defaultValue="0") int offset,@RequestParam(defaultValue="50") int limit,Authentication a){return ApiResponse.ok(legacy.inspect(actor(a),base,offset,limit));}
    @PostMapping("/bases/{base}/legacy-migration/{doc}") @ResponseStatus(HttpStatus.ACCEPTED)
    public ApiResponse<KnowledgeIndexIntakeApplicationApi.IntakeView> migrate(@PathVariable String base,@PathVariable String doc,@Valid @RequestBody Migration body,@RequestHeader("Idempotency-Key") String key,Authentication a){return ApiResponse.ok(legacy.copy(actor(a),base,doc,body.targetBaseId(),body.spaceId(),key));}
    public record Repair(@NotBlank String generationId){}
    public record Migration(@NotBlank String targetBaseId,@NotBlank String spaceId){}
    private static KnowledgeBaseApplicationApi.Actor actor(Authentication a){return new KnowledgeBaseApplicationApi.Actor(PlatformHttpSupport.userId(a),PlatformHttpSupport.tenantId(a));}
}

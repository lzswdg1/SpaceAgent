package com.spaceagent.platform.integration.infrastructure.http;

import com.spaceagent.platform.knowledge.api.KnowledgeBaseApplicationApi;
import com.spaceagent.platform.knowledge.domain.KnowledgeBase;
import com.spaceagent.platform.knowledge.domain.KnowledgeBaseGrant;
import com.spaceagent.shared.api.ApiResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import java.util.List;

@RestController
@RequestMapping("/api/v1/knowledge/bases")
public class PlatformKnowledgeBaseHttpController {
    private final KnowledgeBaseApplicationApi api;
    public PlatformKnowledgeBaseHttpController(KnowledgeBaseApplicationApi api) { this.api = api; }

    @PostMapping @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<KnowledgeBaseApplicationApi.BaseView> create(@Valid @RequestBody CreateRequest body, Authentication auth) {
        return ApiResponse.ok(api.create(new KnowledgeBaseApplicationApi.CreateCommand(actor(auth), body.scope(), body.name(), body.description())));
    }
    @GetMapping
    public ApiResponse<KnowledgeBaseApplicationApi.Page> list(@RequestParam(defaultValue="0") int offset,
                                                            @RequestParam(defaultValue="50") int limit, Authentication auth) {
        return ApiResponse.ok(api.list(actor(auth), offset, limit));
    }
    @GetMapping("/{baseId}")
    public ApiResponse<KnowledgeBaseApplicationApi.BaseView> get(@PathVariable String baseId, Authentication auth) {
        return ApiResponse.ok(api.get(actor(auth), baseId));
    }
    @PutMapping("/{baseId}")
    public ApiResponse<KnowledgeBaseApplicationApi.BaseView> update(@PathVariable String baseId,
            @Valid @RequestBody UpdateRequest body, Authentication auth) {
        return ApiResponse.ok(api.update(new KnowledgeBaseApplicationApi.UpdateCommand(actor(auth), baseId, body.name(), body.description(), body.expectedRevision())));
    }
    @DeleteMapping("/{baseId}")
    public ApiResponse<Void> archive(@PathVariable String baseId, @RequestParam long expectedRevision, Authentication auth) {
        api.archive(actor(auth), baseId, expectedRevision);
        return ApiResponse.ok(null);
    }
    @GetMapping("/{baseId}/grants")
    public ApiResponse<List<KnowledgeBaseGrant>> grants(@PathVariable String baseId, Authentication auth) {
        return ApiResponse.ok(api.grants(actor(auth), baseId));
    }
    @PutMapping("/{baseId}/grants")
    public ApiResponse<KnowledgeBaseApplicationApi.BaseView> grant(@PathVariable String baseId,
            @Valid @RequestBody GrantRequest body, Authentication auth) {
        return ApiResponse.ok(api.grant(new KnowledgeBaseApplicationApi.GrantCommand(actor(auth), baseId,
                body.subjectType(), body.subjectId(), body.permission(), body.expectedRevision())));
    }
    @DeleteMapping("/{baseId}/grants/{subjectType}/{subjectId}")
    public ApiResponse<KnowledgeBaseApplicationApi.BaseView> revoke(@PathVariable String baseId,
            @PathVariable KnowledgeBaseGrant.SubjectType subjectType, @PathVariable String subjectId,
            @RequestParam long expectedRevision, Authentication auth) {
        return ApiResponse.ok(api.revoke(new KnowledgeBaseApplicationApi.RevokeCommand(actor(auth), baseId,
                subjectType, subjectId, expectedRevision)));
    }
    private static KnowledgeBaseApplicationApi.Actor actor(Authentication auth) {
        return new KnowledgeBaseApplicationApi.Actor(PlatformHttpSupport.userId(auth), PlatformHttpSupport.tenantId(auth));
    }
    public record CreateRequest(@NotNull KnowledgeBase.Scope scope, @NotBlank @Size(max=255) String name,
                                @Size(max=2000) String description) {}
    public record UpdateRequest(@NotBlank @Size(max=255) String name, @Size(max=2000) String description,
                                @Min(1) long expectedRevision) {}
    public record GrantRequest(@NotNull KnowledgeBaseGrant.SubjectType subjectType, @NotBlank @Size(max=64) String subjectId,
                               @NotNull KnowledgeBase.Permission permission, @Min(1) long expectedRevision) {}
}

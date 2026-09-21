package com.spaceagent.platform.integration.infrastructure.http;

import com.spaceagent.platform.knowledge.api.*;
import com.spaceagent.platform.knowledge.domain.DocumentWorkspace;
import com.spaceagent.platform.knowledge.domain.KnowledgeUrlJob;
import com.spaceagent.shared.api.ApiResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import java.util.List;

@RestController
@RequestMapping("/api/v1")
public class PlatformKnowledgeUrlWorkspaceHttpController {
    private final KnowledgeUrlIngestionApplicationApi urls;
    private final DocumentWorkspaceApplicationApi workspaces;
    private final DocumentWorkspaceToolApplicationApi tools;
    public PlatformKnowledgeUrlWorkspaceHttpController(KnowledgeUrlIngestionApplicationApi urls,
            DocumentWorkspaceApplicationApi workspaces,DocumentWorkspaceToolApplicationApi tools){this.urls=urls;this.workspaces=workspaces;this.tools=tools;}

    @PostMapping("/knowledge/url-jobs") public ApiResponse<KnowledgeUrlIngestionApplicationApi.UrlJobView> createUrl(@Valid @RequestBody CreateUrlRequest r,Authentication a){PlatformHttpSupport.requireWrite(a);return ApiResponse.ok(urls.create(new KnowledgeUrlIngestionApplicationApi.CreateUrlJobCommand(tenant(a),user(a),r.knowledgeDocumentId(),r.url(),r.refreshPolicy(),r.idempotencyKey())));}
    @GetMapping("/knowledge/url-jobs/{id}") public ApiResponse<KnowledgeUrlIngestionApplicationApi.UrlJobView> getUrl(@PathVariable String id,Authentication a){return ApiResponse.ok(urls.get(new KnowledgeUrlIngestionApplicationApi.GetQuery(tenant(a),user(a),id)));}
    @GetMapping("/knowledge/documents/{documentId}/url-jobs") public ApiResponse<KnowledgeUrlIngestionApplicationApi.UrlJobPage> listUrls(@PathVariable String documentId,@RequestParam(defaultValue="1") @Min(1) @Max(10000) int page,@RequestParam(defaultValue="20") @Min(1) @Max(100) int pageSize,Authentication a){return ApiResponse.ok(urls.listByDocument(new KnowledgeUrlIngestionApplicationApi.ListByDocumentQuery(tenant(a),user(a),documentId,page,pageSize)));}
    @PostMapping("/knowledge/url-jobs/{id}/{action}") public ApiResponse<KnowledgeUrlIngestionApplicationApi.UrlJobView> urlLifecycle(@PathVariable String id,@PathVariable String action,@Valid @RequestBody RevisionRequest r,Authentication a){PlatformHttpSupport.requireWrite(a);var command=new KnowledgeUrlIngestionApplicationApi.LifecycleCommand(tenant(a),user(a),id,r.expectedRevision());return ApiResponse.ok(switch(action){case "pause"->urls.pause(command);case "resume"->urls.resume(command);case "archive"->urls.archive(command);default->throw new IllegalArgumentException("Unsupported URL lifecycle action");});}

    @PostMapping("/document-workspaces") public ApiResponse<DocumentWorkspaceApplicationApi.WorkspaceView> createWorkspace(@Valid @RequestBody CreateWorkspaceRequest r,Authentication a){PlatformHttpSupport.requireWrite(a);return ApiResponse.ok(workspaces.create(new DocumentWorkspaceApplicationApi.CreateCommand(tenant(a),user(a),r.scopeType(),r.scopeId(),r.name(),new DocumentWorkspace.Quota(r.maximumFiles(),r.maximumBytes()),r.idempotencyKey())));}
    @GetMapping("/document-workspaces") public ApiResponse<List<DocumentWorkspaceApplicationApi.WorkspaceView>> listWorkspaces(@RequestParam(defaultValue="USER") DocumentWorkspace.ScopeType scopeType,Authentication a){return ApiResponse.ok(workspaces.list(new DocumentWorkspaceApplicationApi.ListQuery(tenant(a),user(a),scopeType)));}
    @GetMapping("/document-workspaces/{id}") public ApiResponse<DocumentWorkspaceApplicationApi.WorkspaceView> getWorkspace(@PathVariable String id,Authentication a){return ApiResponse.ok(workspaces.get(new DocumentWorkspaceApplicationApi.GetQuery(tenant(a),user(a),id)));}
    @PostMapping("/document-workspaces/{id}/quota") public ApiResponse<DocumentWorkspaceApplicationApi.WorkspaceView> quota(@PathVariable String id,@Valid @RequestBody WorkspaceQuotaRequest r,Authentication a){PlatformHttpSupport.requireWrite(a);return ApiResponse.ok(workspaces.changeQuota(new DocumentWorkspaceApplicationApi.ChangeQuotaCommand(tenant(a),user(a),id,new DocumentWorkspace.Quota(r.maximumFiles(),r.maximumBytes()),r.expectedRevision())));}
    @PostMapping("/document-workspaces/{id}/{action}") public ApiResponse<DocumentWorkspaceApplicationApi.WorkspaceView> workspaceLifecycle(@PathVariable String id,@PathVariable String action,@Valid @RequestBody RevisionRequest r,Authentication a){PlatformHttpSupport.requireWrite(a);var command=new DocumentWorkspaceApplicationApi.LifecycleCommand(tenant(a),user(a),id,r.expectedRevision());return ApiResponse.ok(switch(action){case "pause"->workspaces.pause(command);case "resume"->workspaces.resume(command);case "archive"->workspaces.archive(command);default->throw new IllegalArgumentException("Unsupported Workspace lifecycle action");});}
    @PostMapping("/document-workspaces/{id}/files/read") public ApiResponse<DocumentWorkspaceToolApplicationApi.ReadView> read(@PathVariable String id,@Valid @RequestBody ReadRequest r,Authentication a){return ApiResponse.ok(tools.read(new DocumentWorkspaceToolApplicationApi.ReadQuery(scope(a,id,r.requestId()),r.path(),r.maxCharacters())));}
    @PostMapping("/document-workspaces/{id}/files/list") public ApiResponse<DocumentWorkspaceToolApplicationApi.ListView> list(@PathVariable String id,@Valid @RequestBody ListRequest r,Authentication a){return ApiResponse.ok(tools.list(new DocumentWorkspaceToolApplicationApi.ListQuery(scope(a,id,r.requestId()),r.path(),r.maxDepth(),r.maxEntries())));}
    @PostMapping("/document-workspaces/{id}/files/write") public ApiResponse<DocumentWorkspaceToolApplicationApi.MutationView> write(@PathVariable String id,@Valid @RequestBody WriteRequest r,Authentication a){PlatformHttpSupport.requireWrite(a);return ApiResponse.ok(tools.write(new DocumentWorkspaceToolApplicationApi.WriteCommand(scope(a,id,r.requestId()),r.path(),r.content(),r.approvalId())));}
    @PostMapping("/document-workspaces/{id}/files/delete") public ApiResponse<DocumentWorkspaceToolApplicationApi.MutationView> delete(@PathVariable String id,@Valid @RequestBody DeleteRequest r,Authentication a){PlatformHttpSupport.requireWrite(a);return ApiResponse.ok(tools.delete(new DocumentWorkspaceToolApplicationApi.DeleteCommand(scope(a,id,r.requestId()),r.path(),r.approvalId())));}
    @PostMapping("/document-workspaces/operations/{toolCallId}/reconcile") public ApiResponse<DocumentWorkspaceToolApplicationApi.ReconciliationView> reconcile(@PathVariable String toolCallId,@Valid @RequestBody ReconcileRequest r,Authentication a){PlatformHttpSupport.requireWrite(a);return ApiResponse.ok(tools.reconcile(new DocumentWorkspaceToolApplicationApi.ReconcileCommand(tenant(a),user(a),httpRun(a),toolCallId,r.requestId(),r.reason())));}

    private static DocumentWorkspaceToolApplicationApi.Scope scope(Authentication a,String id,String request){return new DocumentWorkspaceToolApplicationApi.Scope(tenant(a),user(a),id,httpRun(a),"http-document-workspace:"+id,request,request);}
    private static String httpRun(Authentication a){return "http-document-workspace:"+user(a);}private static String user(Authentication a){return PlatformHttpSupport.userId(a);}private static String tenant(Authentication a){return PlatformHttpSupport.tenantId(a);}
    public record CreateUrlRequest(@NotBlank @Size(max=64) String knowledgeDocumentId,@NotBlank @Size(max=2048) String url,@NotNull KnowledgeUrlJob.RefreshPolicy refreshPolicy,@NotBlank @Size(max=120) String idempotencyKey){}
    public record RevisionRequest(@Positive long expectedRevision){}
    public record CreateWorkspaceRequest(@NotNull DocumentWorkspace.ScopeType scopeType,@NotBlank @Size(max=64) String scopeId,@NotBlank @Size(max=120) String name,@Min(1) @Max(10000) long maximumFiles,@Min(1048576) @Max(1073741824) long maximumBytes,@NotBlank @Size(max=120) String idempotencyKey){}
    public record WorkspaceQuotaRequest(@Positive long expectedRevision,@Min(1) @Max(10000) long maximumFiles,@Min(1048576) @Max(1073741824) long maximumBytes){}
    public record ReadRequest(@NotBlank @Size(max=120) String requestId,@NotBlank @Size(max=500) String path,@Min(1) @Max(200000) int maxCharacters){}
    public record ListRequest(@NotBlank @Size(max=120) String requestId,@Size(max=500) String path,@Min(0) @Max(8) int maxDepth,@Min(1) @Max(500) int maxEntries){}
    public record WriteRequest(@NotBlank @Size(max=120) String requestId,@NotBlank @Size(max=500) String path,@NotNull @Size(max=500000) String content,@Size(max=64) String approvalId){}
    public record DeleteRequest(@NotBlank @Size(max=120) String requestId,@NotBlank @Size(max=500) String path,@Size(max=64) String approvalId){}
    public record ReconcileRequest(@NotBlank @Size(max=120) String requestId,@NotBlank @Size(max=1000) String reason){}
}

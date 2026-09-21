package com.spaceagent.platform.knowledge.application;

import com.spaceagent.platform.identity.api.IdentityOwnershipPort;
import com.spaceagent.platform.knowledge.api.DocumentWorkspaceApplicationApi;
import com.spaceagent.platform.knowledge.api.DocumentWorkspaceOperationApplicationApi;
import com.spaceagent.platform.knowledge.domain.DocumentWorkspace;
import com.spaceagent.platform.knowledge.domain.DocumentWorkspaceOperation;
import com.spaceagent.platform.knowledge.domain.DocumentWorkspaceRepository;
import com.spaceagent.platform.knowledge.domain.DocumentWorkspaceStorageGateway;
import com.spaceagent.shared.exception.BusinessException;
import com.spaceagent.shared.id.IdGenerator;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;

@Service
public class DocumentWorkspaceApplicationService
        implements DocumentWorkspaceApplicationApi, DocumentWorkspaceOperationApplicationApi {
    private final DocumentWorkspaceRepository repository;
    private final DocumentWorkspaceStorageGateway storage;
    private final IdentityOwnershipPort identities;
    private final IdGenerator ids;

    public DocumentWorkspaceApplicationService(DocumentWorkspaceRepository repository,
            DocumentWorkspaceStorageGateway storage, IdentityOwnershipPort identities, IdGenerator ids) {
        this.repository = repository; this.storage = storage; this.identities = identities; this.ids = ids;
    }

    @Override public WorkspaceView create(CreateCommand command) {
        requireMember(command.tenantId(), command.actorUserId());
        requireScope(command.tenantId(), command.actorUserId(), command.scopeType(), command.scopeId());
        requireText(command.name(), "name"); requireText(command.idempotencyKey(), "idempotencyKey");
        var prior = repository.findByCreateKey(command.tenantId(), command.actorUserId(), command.idempotencyKey());
        if (prior.isPresent()) {
            DocumentWorkspace value = prior.get();
            if (value.scopeType() != command.scopeType() || !value.scopeId().equals(command.scopeId())
                    || !value.name().equals(command.name().trim()) || !value.quota().equals(command.quota())) {
                throw conflict("Document Workspace idempotency conflict", "DOCUMENT_WORKSPACE_IDEMPOTENCY_CONFLICT");
            }
            return view(value);
        }
        String id = ids.nextId(); var now = repository.currentTime();
        DocumentWorkspace workspace = new DocumentWorkspace(id, command.tenantId(), command.scopeType(),
                command.scopeId(), command.actorUserId(), command.name().trim(),
                "document-workspace:" + id, command.quota(), new DocumentWorkspace.Usage(0, 0),
                DocumentWorkspace.State.ACTIVE, 1, now, now, null);
        storage.provision(workspace);
        repository.insert(workspace, command.idempotencyKey().trim());
        return view(workspace);
    }

    @Override public WorkspaceView get(GetQuery query) {
        return view(require(query.tenantId(), query.actorUserId(), query.workspaceId(), false));
    }
    @Override public List<WorkspaceView> list(ListQuery query) {
        requireMember(query.tenantId(), query.actorUserId());
        return repository.list(query.tenantId()).stream()
                .filter(value -> value.scopeType() == query.scopeType())
                .filter(value -> accessible(value, query.actorUserId())).map(DocumentWorkspaceApplicationService::view).toList();
    }
    @Override public WorkspaceView changeQuota(ChangeQuotaCommand command) {
        DocumentWorkspace current = requireOwner(command.tenantId(), command.actorUserId(), command.workspaceId());
        return view(update(current.changeQuota(command.quota(), repository.currentTime()), command.expectedRevision()));
    }
    @Override public WorkspaceView pause(LifecycleCommand command) {
        DocumentWorkspace current = requireOwner(command.tenantId(), command.actorUserId(), command.workspaceId());
        return view(update(current.pause(repository.currentTime()), command.expectedRevision()));
    }
    @Override public WorkspaceView resume(LifecycleCommand command) {
        DocumentWorkspace current = requireOwner(command.tenantId(), command.actorUserId(), command.workspaceId());
        return view(update(current.resume(repository.currentTime()), command.expectedRevision()));
    }
    @Override public WorkspaceView archive(LifecycleCommand command) {
        DocumentWorkspace current = requireOwner(command.tenantId(), command.actorUserId(), command.workspaceId());
        return view(update(current.archive(repository.currentTime()), command.expectedRevision()));
    }
    @Override public WorkspaceView requireAccessible(ScopeQuery query) {
        return view(require(query.tenantId(), query.actorUserId(), query.workspaceId(), query.writable()));
    }
    @Override public ClaimView claim(ClaimCommand command) {
        require(command.tenantId(), command.actorUserId(), command.workspaceId(), true);
        requireText(command.toolCallId(), "toolCallId"); requireText(command.idempotencyKey(), "idempotencyKey");
        validatePath(command.path());
        var claim = repository.claimMutation(new DocumentWorkspaceRepository.MutationRequest(
                ids.nextId(), command.tenantId(), command.workspaceId(), command.actorUserId(),
                command.agentRunId(), command.runStepId(), command.toolCallId().trim(), command.idempotencyKey().trim(), command.inputHash(),
                command.type(), command.path(), command.requestedBytes(), repository.currentTime()));
        return new ClaimView(claim.type().name(), claim.operation() == null ? null : operation(claim.operation()));
    }
    @Override public OperationView complete(CompleteCommand command) {
        return repository.completeMutation(command.tenantId(), command.operationId(), command.actualBytes(),
                command.resultHash(), repository.currentTime()).map(DocumentWorkspaceRepository.MutationResult::operation)
                .map(DocumentWorkspaceApplicationService::operation).orElseThrow(DocumentWorkspaceApplicationService::operationConflict);
    }
    @Override public OperationView fail(FailCommand command) {
        return repository.failMutation(command.tenantId(), command.operationId(), command.safeErrorCode(), repository.currentTime())
                .map(DocumentWorkspaceRepository.MutationResult::operation).map(DocumentWorkspaceApplicationService::operation)
                .orElseThrow(DocumentWorkspaceApplicationService::operationConflict);
    }
    @Override public OperationView markUnknown(FailCommand command) {
        return repository.markMutationUnknown(command.tenantId(), command.operationId(), command.safeErrorCode(), repository.currentTime())
                .map(DocumentWorkspaceRepository.MutationResult::operation).map(DocumentWorkspaceApplicationService::operation)
                .orElseThrow(DocumentWorkspaceApplicationService::operationConflict);
    }
    @Override public OperationView findMutation(FindMutationQuery query) {
        requireMember(query.tenantId(), query.actorUserId());
        DocumentWorkspaceOperation value=repository.findMutation(query.tenantId(),query.agentRunId(),query.toolCallId())
                .orElseThrow(DocumentWorkspaceApplicationService::missingOperation);
        require(query.tenantId(),query.actorUserId(),value.workspaceId(),false);
        return operation(value);
    }

    private DocumentWorkspace update(DocumentWorkspace next, long expectedRevision) {
        if (next.revision() != expectedRevision + 1) throw operationConflict();
        return repository.update(next, expectedRevision).orElseThrow(DocumentWorkspaceApplicationService::operationConflict);
    }
    private DocumentWorkspace requireOwner(String tenant, String actor, String id) {
        DocumentWorkspace value = require(tenant, actor, id, false);
        if (!value.ownerId().equals(actor)) throw forbidden();
        return value;
    }
    private DocumentWorkspace require(String tenant, String actor, String id, boolean writable) {
        requireMember(tenant, actor); requireUuid(id);
        DocumentWorkspace value = repository.findById(tenant, id).filter(v -> accessible(v, actor))
                .orElseThrow(DocumentWorkspaceApplicationService::missing);
        if (writable && value.state() != DocumentWorkspace.State.ACTIVE) {
            throw conflict("Document Workspace is not writable", "DOCUMENT_WORKSPACE_NOT_WRITABLE");
        }
        return value;
    }
    private boolean accessible(DocumentWorkspace value, String actor) {
        return value.scopeType() == DocumentWorkspace.ScopeType.ORGANIZATION || value.ownerId().equals(actor);
    }
    private void requireMember(String tenant, String actor) {
        requireText(tenant, "tenantId"); requireText(actor, "actorUserId");
        if (!identities.isMemberOfTenant(tenant, actor)) throw forbidden();
    }
    private static void requireScope(String tenant, String actor, DocumentWorkspace.ScopeType type, String scope) {
        if (type == null || scope == null || (type == DocumentWorkspace.ScopeType.USER && !scope.equals(actor))
                || (type == DocumentWorkspace.ScopeType.ORGANIZATION && !scope.equals(tenant))) throw forbidden();
    }
    private static void validatePath(String path) {
        try { new DocumentWorkspaceOperation("00000000-0000-4000-8000-000000000000", "t", "w", "a", "r", "s", "c", "k",
                "sha256:" + "0".repeat(64), DocumentWorkspaceOperation.Type.WRITE, path, 0, 0, false, 0, 0,
                DocumentWorkspaceOperation.State.PENDING, null, null, null, 1,
                java.time.Instant.EPOCH, java.time.Instant.EPOCH, null); }
        catch (RuntimeException error) { throw new BusinessException("Document path is invalid", HttpStatus.BAD_REQUEST, "DOCUMENT_WORKSPACE_PATH_INVALID"); }
    }
    private static void requireUuid(String value) { try { UUID.fromString(value); } catch (RuntimeException error) { throw missing(); } }
    private static String requireText(String value, String field) {
        if (value == null || value.isBlank() || value.length() > 200) throw new BusinessException(field + " is invalid", HttpStatus.BAD_REQUEST, "DOCUMENT_WORKSPACE_INPUT_INVALID");
        return value.trim();
    }
    private static WorkspaceView view(DocumentWorkspace value) { return new WorkspaceView(value.id(), value.scopeType().name(), value.scopeId(), value.ownerId(), value.name(), value.objectNamespace(), value.quota(), value.usage(), value.state().name(), value.revision(), value.createdAt(), value.updatedAt(), value.archivedAt()); }
    private static OperationView operation(DocumentWorkspaceOperation value) { return new OperationView(value.id(), value.workspaceId(), value.agentRunId(), value.runStepId(), value.toolCallId(), value.inputHash(), value.type().name(), value.path(), value.requestedBytes(), value.previousBytes(), value.previousFilePresent(), value.reservedFiles(), value.reservedBytes(), value.state().name(), value.resultBytes(), value.resultHash(), value.safeErrorCode(), value.revision()); }
    private static BusinessException missing() { return new BusinessException("Document Workspace not found", HttpStatus.NOT_FOUND, "DOCUMENT_WORKSPACE_NOT_FOUND"); }
    private static BusinessException forbidden() { return new BusinessException("Document Workspace access denied", HttpStatus.FORBIDDEN, "DOCUMENT_WORKSPACE_ACCESS_DENIED"); }
    private static BusinessException operationConflict() { return conflict("Document Workspace revision conflict", "DOCUMENT_WORKSPACE_REVISION_CONFLICT"); }
    private static BusinessException missingOperation() { return new BusinessException("Document Workspace operation not found", HttpStatus.NOT_FOUND, "DOCUMENT_WORKSPACE_OPERATION_NOT_FOUND"); }
    private static BusinessException conflict(String message, String code) { return new BusinessException(message, HttpStatus.CONFLICT, code); }
}

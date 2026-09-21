package com.spaceagent.platform.knowledge.api;

import java.util.List;

/** Public document Workspace file boundary; implementation coordinates owner APIs only. */
public interface DocumentWorkspaceToolApplicationApi {
    ReadView read(ReadQuery query);
    ListView list(ListQuery query);
    MutationView write(WriteCommand command);
    MutationView delete(DeleteCommand command);
    ReconciliationView reconcile(ReconcileCommand command);

    record Scope(String tenantId,String actorUserId,String workspaceId,String agentRunId,
            String runStepId,String toolCallId,String idempotencyKey) {}
    record ReadQuery(Scope scope,String path,int maxCharacters) {}
    record ListQuery(Scope scope,String path,int maxDepth,int maxEntries) {}
    record WriteCommand(Scope scope,String path,String content,String approvalId) {}
    record DeleteCommand(Scope scope,String path,String approvalId) {}
    record ReconcileCommand(String tenantId,String actorUserId,String agentRunId,
            String originalToolCallId,String reconciliationToolCallId,String reason) {}
    record ReadView(String path,String content,long sizeBytes,boolean truncated) {}
    record EntryView(String path,boolean directory,long sizeBytes) {}
    record ListView(String root,List<EntryView> entries,boolean truncated) { public ListView { entries=List.copyOf(entries); } }
    record MutationView(String operationId,String path,long sizeBytes,String contentHash,String state) {}
    record ReconciliationView(String operationId,String toolCallId,String state,String verifier,
            java.util.Map<String,String> evidence) { public ReconciliationView { evidence=java.util.Map.copyOf(evidence); } }
}

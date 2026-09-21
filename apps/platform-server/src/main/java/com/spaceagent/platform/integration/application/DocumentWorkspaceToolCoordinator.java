package com.spaceagent.platform.integration.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.spaceagent.platform.governance.api.GovernanceApplicationApi;
import com.spaceagent.platform.governance.domain.GovernanceActionType;
import com.spaceagent.platform.knowledge.api.*;
import com.spaceagent.platform.knowledge.domain.DocumentWorkspaceOperation;
import com.spaceagent.platform.tooling.api.*;
import com.spaceagent.shared.exception.BusinessException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;
import com.spaceagent.platform.tooling.domain.ToolExecutionReconciliationEvidence;
import com.spaceagent.platform.tooling.domain.ToolExecutionStatus;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

@Service
public class DocumentWorkspaceToolCoordinator implements DocumentWorkspaceToolApplicationApi {
    private static final String EXECUTABLE="spaceagent-workspace-tool";
    private static final int MAX_WRITE_BYTES=500_000;
    private final DocumentWorkspaceOperationApplicationApi workspaces;
    private final GovernanceApplicationApi governance;
    private final SandboxToolExecutionApplicationApi sandbox;
    private final ToolExecutionLedgerApplicationApi ledger;
    private final ObjectMapper json;
    public DocumentWorkspaceToolCoordinator(DocumentWorkspaceOperationApplicationApi workspaces,
            GovernanceApplicationApi governance,SandboxToolExecutionApplicationApi sandbox,ObjectMapper json){
        this(workspaces,governance,sandbox,null,json);
    }
    @Autowired
    public DocumentWorkspaceToolCoordinator(DocumentWorkspaceOperationApplicationApi workspaces,
            GovernanceApplicationApi governance,SandboxToolExecutionApplicationApi sandbox,
            ToolExecutionLedgerApplicationApi ledger,ObjectMapper json){
        this.workspaces=workspaces;this.governance=governance;this.sandbox=sandbox;this.ledger=ledger;this.json=json;
    }
    @Override public ReadView read(ReadQuery query){
        requireScope(query.scope(),false);JsonNode node=execute(query.scope(),"document-workspace-read-file",
                List.of("file-read",query.path(),Integer.toString(query.maxCharacters())),null);
        return new ReadView(text(node,"path"),text(node,"content"),node.path("sizeBytes").asLong(),node.path("truncated").asBoolean());
    }
    @Override public ListView list(ListQuery query){
        requireScope(query.scope(),false);JsonNode node=execute(query.scope(),"document-workspace-read-list",
                List.of("file-list",blank(query.path())?".":query.path(),Integer.toString(query.maxDepth()),Integer.toString(query.maxEntries())),null);
        List<EntryView> entries=new ArrayList<>();node.path("entries").forEach(v->entries.add(new EntryView(text(v,"path"),v.path("directory").asBoolean(),v.path("sizeBytes").asLong())));
        return new ListView(text(node,"root"),entries,node.path("truncated").asBoolean());
    }
    @Override public MutationView write(WriteCommand command){
        byte[] bytes=(command.content()==null?"":command.content()).getBytes(StandardCharsets.UTF_8);
        if(bytes.length>MAX_WRITE_BYTES)throw bad("Document Workspace input exceeds limit","DOCUMENT_WORKSPACE_INPUT_TOO_LARGE");
        String hash="sha256:"+sha256(bytes);authorize(command.scope(),command.path(),hash,command.approvalId(),"write");
        return mutate(command.scope(),command.path(),DocumentWorkspaceOperation.Type.WRITE,bytes.length,hash,
                "document-file-write",Base64.getEncoder().encodeToString(bytes));
    }
    @Override public MutationView delete(DeleteCommand command){
        String hash="sha256:"+sha256(("delete\n"+command.path()).getBytes(StandardCharsets.UTF_8));
        authorize(command.scope(),command.path(),hash,command.approvalId(),"delete");
        return mutate(command.scope(),command.path(),DocumentWorkspaceOperation.Type.DELETE,0,hash,"document-file-delete",null);
    }
    @Override public ReconciliationView reconcile(ReconcileCommand command){
        var operation=workspaces.findMutation(new DocumentWorkspaceOperationApplicationApi.FindMutationQuery(
                command.tenantId(),command.actorUserId(),command.agentRunId(),command.originalToolCallId()));
        Map<String,String> evidence=new LinkedHashMap<>();
        if("UNKNOWN".equals(operation.state())){
            Scope proofScope=new Scope(command.tenantId(),command.actorUserId(),operation.workspaceId(),
                    command.agentRunId(),operation.runStepId(),command.reconciliationToolCallId(),
                    "reconcile:"+command.originalToolCallId());
            JsonNode proof=execute(proofScope,"document-workspace-read-proof",
                    List.of("document-file-proof",operation.path()),null);
            boolean exists=proof.path("exists").asBoolean();long bytes=proof.path("sizeBytes").asLong(-1);
            String actualHash=text(proof,"contentHash");
            boolean verified="WRITE".equals(operation.type())?exists&&bytes==operation.requestedBytes()
                    &&actualHash.equals(operation.inputHash()):!exists;
            if(!verified)throw conflict("Document Workspace postcondition is inconclusive","DOCUMENT_WORKSPACE_RECONCILIATION_INCONCLUSIVE");
            operation=workspaces.complete(new DocumentWorkspaceOperationApplicationApi.CompleteCommand(
                    command.tenantId(),operation.id(),"WRITE".equals(operation.type())?bytes:0L,actualHash));
            evidence.put("postcondition","matched");evidence.put("actualSha256",actualHash);
            evidence.put("sizeBytes",Long.toString(bytes));
        }else if(!"SUCCEEDED".equals(operation.state())){
            throw conflict("Document Workspace operation is not UNKNOWN","DOCUMENT_WORKSPACE_RECONCILIATION_STATE_CONFLICT");
        }else evidence.put("postcondition","already_resolved");
        if(ledger!=null){
            ToolExecutionLedgerView original=ledger.findByRunId(command.agentRunId()).stream()
                    .filter(value->value.toolCallId().equals(command.originalToolCallId())).findFirst()
                    .orElseThrow(()->conflict("Tool execution evidence is missing","DOCUMENT_WORKSPACE_LEDGER_NOT_FOUND"));
            if(original.status()==ToolExecutionStatus.UNKNOWN){
                ledger.reconcileUnknown(new ReconcileUnknownToolExecutionCommand(original.agentRunId(),original.toolCallId(),
                        original.inputHash(),original.revision(),ToolExecutionStatus.SUCCEEDED,
                        "{\"verified\":true,\"verifier\":\"document_workspace_sha256\"}",null,null,
                        new ToolExecutionReconciliationEvidence("verified Document Workspace postcondition",null,evidence),
                        command.actorUserId(),command.reason()));
            }else if(original.status()!=ToolExecutionStatus.SUCCEEDED){
                throw conflict("Tool execution is not UNKNOWN","DOCUMENT_WORKSPACE_RECONCILIATION_STATE_CONFLICT");
            }
        }
        return new ReconciliationView(operation.id(),command.originalToolCallId(),operation.state(),
                "document_workspace_sha256",evidence);
    }
    private MutationView mutate(Scope scope,String path,DocumentWorkspaceOperation.Type type,long bytes,String hash,String verb,String input){
        requireScope(scope,true);var claim=workspaces.claim(new DocumentWorkspaceOperationApplicationApi.ClaimCommand(scope.tenantId(),scope.actorUserId(),scope.workspaceId(),scope.agentRunId(),scope.runStepId(),scope.toolCallId(),scope.idempotencyKey(),hash,type,path,bytes));
        if("REPLAY".equals(claim.decision()))return mutation(claim.operation());
        if("UNKNOWN".equals(claim.decision()))throw conflict("Document Workspace outcome is unknown","DOCUMENT_WORKSPACE_OPERATION_UNKNOWN");
        if("FAILED".equals(claim.decision()))throw conflict("Document Workspace operation already failed","DOCUMENT_WORKSPACE_OPERATION_FAILED");
        if("BUSY".equals(claim.decision()))throw conflict("Document Workspace path is blocked","DOCUMENT_WORKSPACE_PATH_BUSY");
        if("CONFLICT".equals(claim.decision()))throw conflict("Document Workspace idempotency conflict","DOCUMENT_WORKSPACE_IDEMPOTENCY_CONFLICT");
        try{
            JsonNode result=execute(scope,type==DocumentWorkspaceOperation.Type.WRITE?"document-workspace-write":"document-workspace-delete",List.of(verb,path),input);
            long actual=result.path("sizeBytes").asLong(-1);String resultHash=text(result,"contentHash");
            return mutation(workspaces.complete(new DocumentWorkspaceOperationApplicationApi.CompleteCommand(scope.tenantId(),claim.operation().id(),actual,resultHash)));
        }catch(SandboxToolExecutionUnavailableException error){
            workspaces.markUnknown(new DocumentWorkspaceOperationApplicationApi.FailCommand(scope.tenantId(),claim.operation().id(),"SANDBOX_OUTCOME_UNKNOWN"));
            throw conflict("Document Workspace outcome is unknown","DOCUMENT_WORKSPACE_OPERATION_UNKNOWN");
        }catch(BusinessException error){
            if("TOOL_EXECUTION_AMBIGUOUS".equals(error.getCode()) || "DOCUMENT_WORKSPACE_REVISION_CONFLICT".equals(error.getCode())) {
                workspaces.markUnknown(new DocumentWorkspaceOperationApplicationApi.FailCommand(scope.tenantId(),claim.operation().id(),"SANDBOX_OUTCOME_UNKNOWN"));
            } else {
                workspaces.fail(new DocumentWorkspaceOperationApplicationApi.FailCommand(scope.tenantId(),claim.operation().id(),safe(error.getCode())));
            }
            throw error;
        }catch(RuntimeException error){
            workspaces.markUnknown(new DocumentWorkspaceOperationApplicationApi.FailCommand(scope.tenantId(),claim.operation().id(),"SANDBOX_EVIDENCE_INVALID"));
            throw conflict("Document Workspace outcome is unknown","DOCUMENT_WORKSPACE_OPERATION_UNKNOWN");
        }
    }
    private JsonNode execute(Scope scope,String tool,List<String> arguments,String input){
        var result=sandbox.execute(new SandboxToolExecutionCommand(scope.agentRunId(),scope.runStepId(),tool,
                scope.toolCallId(),scope.idempotencyKey(),EXECUTABLE,arguments,workspaceRef(scope.workspaceId()),
                "document-workspace:"+scope.workspaceId(),60,input));
        if(!"SUCCEEDED".equals(result.status()))throw new BusinessException("Document Workspace Sandbox operation failed",HttpStatus.BAD_GATEWAY,result.error()==null?"DOCUMENT_WORKSPACE_SANDBOX_FAILED":result.error());
        try{JsonNode node=json.readTree(result.result());if(node==null||!node.isObject())throw new IllegalArgumentException();return node;}
        catch(Exception error){throw new IllegalStateException("Document Workspace Sandbox evidence is invalid",error);}
    }
    private void authorize(Scope scope,String path,String hash,String approval,String verb){
        var result=governance.authorize(new GovernanceApplicationApi.AuthorizeCommand(scope.tenantId(),scope.actorUserId(),GovernanceActionType.CODING_FILE_MUTATION,"DOCUMENT_WORKSPACE",scope.workspaceId(),hash,"Document Workspace "+verb+": "+path,approval));
        if(!result.allowed())throw conflict("Document Workspace approval is required",result.status()==GovernanceApplicationApi.AuthorizationStatus.APPROVAL_REQUIRED?"DOCUMENT_WORKSPACE_APPROVAL_REQUIRED":"DOCUMENT_WORKSPACE_APPROVAL_INVALID");
    }
    private void requireScope(Scope s,boolean writable){if(s==null)throw bad("Document Workspace scope is required","DOCUMENT_WORKSPACE_INPUT_INVALID");workspaces.requireAccessible(new DocumentWorkspaceOperationApplicationApi.ScopeQuery(s.tenantId(),s.actorUserId(),s.workspaceId(),writable));if(blank(s.agentRunId())||blank(s.runStepId())||blank(s.toolCallId())||blank(s.idempotencyKey()))throw bad("Document Workspace execution correlation is required","DOCUMENT_WORKSPACE_INPUT_INVALID");}
    private static String workspaceRef(String id){try{UUID.fromString(id);return "document-workspaces/"+id;}catch(RuntimeException error){throw bad("Document Workspace not found","DOCUMENT_WORKSPACE_NOT_FOUND");}}
    private static MutationView mutation(DocumentWorkspaceOperationApplicationApi.OperationView value){return new MutationView(value.id(),value.path(),value.resultBytes()==null?value.requestedBytes():value.resultBytes(),value.resultHash(),value.state());}
    private static String text(JsonNode node,String field){if(!node.path(field).isTextual())throw new IllegalStateException("Document Workspace Sandbox evidence is invalid");return node.path(field).asText();}
    private static String sha256(byte[] value){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));}catch(Exception error){throw new IllegalStateException("SHA-256 unavailable",error);}}
    private static String safe(String code){return code!=null&&code.matches("[A-Z0-9_-]{1,120}")?code:"DOCUMENT_WORKSPACE_SANDBOX_FAILED";}
    private static boolean blank(String v){return v==null||v.isBlank();}private static BusinessException bad(String m,String c){return new BusinessException(m,HttpStatus.BAD_REQUEST,c);}private static BusinessException conflict(String m,String c){return new BusinessException(m,HttpStatus.CONFLICT,c);}
}

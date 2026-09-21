package com.spaceagent.platform.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.spaceagent.platform.governance.api.GovernanceApplicationApi;
import com.spaceagent.platform.integration.application.DocumentWorkspaceToolCoordinator;
import com.spaceagent.platform.knowledge.api.*;
import com.spaceagent.platform.tooling.api.*;
import com.spaceagent.platform.tooling.domain.*;
import com.spaceagent.shared.exception.BusinessException;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class DocumentWorkspaceReconciliationTest {
    private static final String HASH="sha256:2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824";
    @Test void hashOnlyProofResolvesKnowledgeAndOriginalLedger(){var operations=mock(DocumentWorkspaceOperationApplicationApi.class);var sandbox=mock(SandboxToolExecutionApplicationApi.class);var ledger=mock(ToolExecutionLedgerApplicationApi.class);when(operations.findMutation(any())).thenReturn(operation("UNKNOWN"));when(operations.requireAccessible(any())).thenReturn(null);when(operations.complete(any())).thenReturn(operation("SUCCEEDED"));when(sandbox.execute(any())).thenReturn(new SandboxToolExecutionView("proof","SUCCEEDED","{\"path\":\"a.md\",\"exists\":true,\"sizeBytes\":5,\"contentHash\":\""+HASH+"\"}",null,null));when(ledger.findByRunId("run")).thenReturn(List.of(ledger(ToolExecutionStatus.UNKNOWN)));var coordinator=new DocumentWorkspaceToolCoordinator(operations,mock(GovernanceApplicationApi.class),sandbox,ledger,new ObjectMapper());var result=coordinator.reconcile(new DocumentWorkspaceToolApplicationApi.ReconcileCommand("tenant","owner","run","call","proof","owner confirmed"));assertThat(result.state()).isEqualTo("SUCCEEDED");verify(operations).complete(any());verify(ledger).reconcileUnknown(argThat(value->value.toolCallId().equals("call")&&value.resolution()==ToolExecutionStatus.SUCCEEDED));}
    @Test void mismatchedProofRemainsUnknown(){var operations=mock(DocumentWorkspaceOperationApplicationApi.class);var sandbox=mock(SandboxToolExecutionApplicationApi.class);when(operations.findMutation(any())).thenReturn(operation("UNKNOWN"));when(sandbox.execute(any())).thenReturn(new SandboxToolExecutionView("proof","SUCCEEDED","{\"path\":\"a.md\",\"exists\":true,\"sizeBytes\":4,\"contentHash\":\"sha256:"+"0".repeat(64)+"\"}",null,null));var coordinator=new DocumentWorkspaceToolCoordinator(operations,mock(GovernanceApplicationApi.class),sandbox,mock(ToolExecutionLedgerApplicationApi.class),new ObjectMapper());assertThatThrownBy(()->coordinator.reconcile(new DocumentWorkspaceToolApplicationApi.ReconcileCommand("tenant","owner","run","call","proof","check"))).isInstanceOf(BusinessException.class).extracting(e->((BusinessException)e).getCode()).isEqualTo("DOCUMENT_WORKSPACE_RECONCILIATION_INCONCLUSIVE");verify(operations,never()).complete(any());}
    private static DocumentWorkspaceOperationApplicationApi.OperationView operation(String state){return new DocumentWorkspaceOperationApplicationApi.OperationView("operation","00000000-0000-4000-8000-000000000001","run","step","call",HASH,"WRITE","a.md",5,0,false,1,5,state,"SUCCEEDED".equals(state)?5L:null,"SUCCEEDED".equals(state)?HASH:null,null,2);}
    private static ToolExecutionLedgerView ledger(ToolExecutionStatus status){Instant now=Instant.parse("2026-09-09T00:00:00Z");return new ToolExecutionLedgerView("ledger","run","step","document-workspace-write","call","key","{}","sha256:"+"1".repeat(64),status,null,null,null,now,null,null,null,2,now,now,null,null,null);}
}

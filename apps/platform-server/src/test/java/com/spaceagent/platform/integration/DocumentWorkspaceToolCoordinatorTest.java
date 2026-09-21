package com.spaceagent.platform.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.spaceagent.platform.governance.api.GovernanceApplicationApi;
import com.spaceagent.platform.identity.api.IdentityOwnershipPort;
import com.spaceagent.platform.integration.application.DocumentWorkspaceToolCoordinator;
import com.spaceagent.platform.knowledge.api.*;
import com.spaceagent.platform.knowledge.application.DocumentWorkspaceApplicationService;
import com.spaceagent.platform.knowledge.domain.DocumentWorkspace;
import com.spaceagent.platform.knowledge.infrastructure.memory.InMemoryDocumentWorkspaceRepository;
import com.spaceagent.platform.tooling.api.*;
import com.spaceagent.shared.exception.BusinessException;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class DocumentWorkspaceToolCoordinatorTest {
    @Test void governedWriteUsesLedgerBackedSandboxAndReplaysEvidence(){Fixture f=new Fixture();when(f.sandbox.execute(any())).thenReturn(new SandboxToolExecutionView("call-1","SUCCEEDED","{\"path\":\"notes/a.md\",\"sizeBytes\":5,\"contentHash\":\"sha256:2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824\"}",null,null));var command=new DocumentWorkspaceToolApplicationApi.WriteCommand(f.scope("call-1","write-1"),"notes/a.md","hello",null);var first=f.tools.write(command);var replay=f.tools.write(command);assertThat(first.state()).isEqualTo("SUCCEEDED");assertThat(replay).isEqualTo(first);verify(f.governance,times(2)).authorize(any());verify(f.sandbox,times(1)).execute(argThat(value->value.workspaceRef().equals("document-workspaces/00000000-0000-4000-8000-000000000001")&&value.inputBase64()!=null));}
    @Test void unknownKeepsQuotaAndRejectsRedispatch(){Fixture f=new Fixture();when(f.sandbox.execute(any())).thenThrow(new RuntimeException("transport lost"));var command=new DocumentWorkspaceToolApplicationApi.WriteCommand(f.scope("call-2","write-2"),"draft.md","uncertain",null);assertThatThrownBy(()->f.tools.write(command)).isInstanceOf(BusinessException.class).extracting(e->((BusinessException)e).getCode()).isEqualTo("DOCUMENT_WORKSPACE_OPERATION_UNKNOWN");assertThatThrownBy(()->f.tools.write(command)).isInstanceOf(BusinessException.class).extracting(e->((BusinessException)e).getCode()).isEqualTo("DOCUMENT_WORKSPACE_OPERATION_UNKNOWN");verify(f.sandbox,times(1)).execute(any());assertThat(f.workspace.get(new DocumentWorkspaceApplicationApi.GetQuery(f.tenant,f.owner,f.workspaceId)).usage().byteCount()).isEqualTo(9);}
    private static final class Fixture{final String tenant="tenant-1",owner="owner-1",workspaceId="00000000-0000-4000-8000-000000000001";final GovernanceApplicationApi governance=mock(GovernanceApplicationApi.class);final SandboxToolExecutionApplicationApi sandbox=mock(SandboxToolExecutionApplicationApi.class);final DocumentWorkspaceApplicationService workspace;final DocumentWorkspaceToolCoordinator tools;Fixture(){Iterator<String> ids=List.of(workspaceId,"00000000-0000-4000-8000-000000000002","00000000-0000-4000-8000-000000000003").iterator();IdentityOwnershipPort memberships=(t,u)->tenant.equals(t)&&owner.equals(u);var repository=new InMemoryDocumentWorkspaceRepository(()->Instant.parse("2026-09-09T00:00:00Z"));workspace=new DocumentWorkspaceApplicationService(repository,w->{},memberships,ids::next);workspace.create(new DocumentWorkspaceApplicationApi.CreateCommand(tenant,owner,DocumentWorkspace.ScopeType.USER,owner,"Docs",new DocumentWorkspace.Quota(10,1_048_576),"create"));when(governance.authorize(any())).thenReturn(new GovernanceApplicationApi.AuthorizationView(GovernanceApplicationApi.AuthorizationStatus.ALLOWED,null));tools=new DocumentWorkspaceToolCoordinator(workspace,governance,sandbox,new ObjectMapper());}DocumentWorkspaceToolApplicationApi.Scope scope(String call,String key){return new DocumentWorkspaceToolApplicationApi.Scope(tenant,owner,workspaceId,"run-1","step-1",call,key);}}
}

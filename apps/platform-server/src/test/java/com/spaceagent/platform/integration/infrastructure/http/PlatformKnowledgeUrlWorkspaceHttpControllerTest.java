package com.spaceagent.platform.integration.infrastructure.http;

import com.spaceagent.platform.knowledge.api.*;
import com.spaceagent.platform.knowledge.domain.DocumentWorkspace;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import java.time.Instant;
import java.util.List;
import java.util.Arrays;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class PlatformKnowledgeUrlWorkspaceHttpControllerTest {
    @Test void authenticatedAdapterDerivesIdentityAndNeverAcceptsHostPathBindings(){var urls=mock(KnowledgeUrlIngestionApplicationApi.class);var workspaces=mock(DocumentWorkspaceApplicationApi.class);var tools=mock(DocumentWorkspaceToolApplicationApi.class);var controller=new PlatformKnowledgeUrlWorkspaceHttpController(urls,workspaces,tools);var auth=new UsernamePasswordAuthenticationToken("owner","ignored",List.of(()->"ROLE_OWNER"));var view=new DocumentWorkspaceApplicationApi.WorkspaceView("00000000-0000-4000-8000-000000000001","USER","owner","owner","Docs","document-workspace:00000000-0000-4000-8000-000000000001",new DocumentWorkspace.Quota(10,1048576),new DocumentWorkspace.Usage(0,0),"ACTIVE",1,Instant.EPOCH,Instant.EPOCH,null);when(workspaces.create(any())).thenReturn(view);var response=controller.createWorkspace(new PlatformKnowledgeUrlWorkspaceHttpController.CreateWorkspaceRequest(DocumentWorkspace.ScopeType.USER,"owner","Docs",10,1048576,"request"),auth);assertThat(response.data().objectNamespace()).startsWith("document-workspace:");verify(workspaces).create(argThat(c->c.tenantId().equals("owner")&&c.actorUserId().equals("owner")&&Arrays.stream(c.getClass().getRecordComponents()).noneMatch(component->component.getName().toLowerCase().contains("path"))));}
    @Test void urlJobDiscoveryDerivesScopeAndBoundsPage(){var urls=mock(KnowledgeUrlIngestionApplicationApi.class);var controller=new PlatformKnowledgeUrlWorkspaceHttpController(urls,mock(DocumentWorkspaceApplicationApi.class),mock(DocumentWorkspaceToolApplicationApi.class));var auth=new UsernamePasswordAuthenticationToken("owner","ignored",List.of(()->"ROLE_OWNER"));when(urls.listByDocument(any())).thenReturn(new KnowledgeUrlIngestionApplicationApi.UrlJobPage(List.of(),1,20,0));controller.listUrls("document",1,20,auth);verify(urls).listByDocument(argThat(q->q.tenantId().equals("owner")&&q.ownerId().equals("owner")&&q.knowledgeDocumentId().equals("document")&&q.page()==1&&q.pageSize()==20));}
}

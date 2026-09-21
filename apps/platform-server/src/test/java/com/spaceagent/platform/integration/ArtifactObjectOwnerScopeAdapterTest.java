package com.spaceagent.platform.integration;

import com.spaceagent.platform.artifact.api.ArtifactApplicationApi;
import com.spaceagent.platform.artifact.domain.ArtifactObjectReference;
import com.spaceagent.platform.integration.application.ArtifactObjectOwnerScopeAdapter;
import com.spaceagent.platform.knowledge.api.KnowledgeOwnershipPort;
import com.spaceagent.platform.project.api.*;
import com.spaceagent.platform.project.domain.ProjectStatus;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.Optional;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class ArtifactObjectOwnerScopeAdapterTest {
    @Test void ownerProofUsesOnlyProjectKnowledgeAndArtifactPublicApis(){var projects=mock(ProjectApplicationApi.class);var knowledge=mock(KnowledgeOwnershipPort.class);var artifacts=mock(ArtifactApplicationApi.class);when(projects.getProject(any())).thenReturn(new ProjectView("project","tenant","owner","P","",ProjectStatus.ACTIVE,Instant.EPOCH,Instant.EPOCH));when(knowledge.canAccess("document","owner")).thenReturn(true);when(artifacts.find("tenant","artifact")).thenReturn(Optional.of(new ArtifactApplicationApi.ArtifactView("artifact","project","task","run","workspace",com.spaceagent.platform.artifact.domain.ArtifactType.TEST_REPORT,"name",null,"sha256:x",null,"{}",Instant.EPOCH)));var adapter=new ArtifactObjectOwnerScopeAdapter(projects,knowledge,artifacts);assertThat(adapter.canAttach("tenant","owner",ArtifactObjectReference.OwnerType.PROJECT,"project")).isTrue();assertThat(adapter.canAttach("tenant","owner",ArtifactObjectReference.OwnerType.KNOWLEDGE,"document")).isTrue();assertThat(adapter.canAttach("tenant","owner",ArtifactObjectReference.OwnerType.ARTIFACT,"artifact")).isTrue();}
}

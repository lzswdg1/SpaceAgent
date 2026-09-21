package com.spaceagent.platform.integration.infrastructure.http;

import com.spaceagent.platform.artifact.api.ArtifactObjectApplicationApi;
import com.spaceagent.platform.artifact.domain.ArtifactObjectReference;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import java.time.Instant;
import java.util.List;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class PlatformArtifactObjectHttpControllerTest {
    @Test void httpDerivesTenantActorAndAcceptsOnlyOpaqueOwnerReference(){var api=mock(ArtifactObjectApplicationApi.class);var controller=new PlatformArtifactObjectHttpController(api);var auth=new UsernamePasswordAuthenticationToken("owner","n/a",List.of(()->"ROLE_OWNER"));when(api.attach(any())).thenReturn(new ArtifactObjectApplicationApi.ReferenceView("ref","object","PROJECT","project","report","ACTIVE",1,Instant.EPOCH,null));var response=controller.attach("object",new PlatformArtifactObjectHttpController.AttachRequest(ArtifactObjectReference.OwnerType.PROJECT,"project","report","request"),auth);assertThat(response.data().ownerResourceId()).isEqualTo("project");verify(api).attach(argThat(c->c.tenantId().equals("owner")&&c.actorUserId().equals("owner")));}
}

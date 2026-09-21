package com.spaceagent.platform.knowledge;

import com.spaceagent.platform.knowledge.api.DocumentWorkspaceApplicationApi;
import com.spaceagent.platform.knowledge.domain.DocumentWorkspace;
import org.junit.jupiter.api.Test;
import java.lang.reflect.RecordComponent;import java.time.Instant;import java.util.Arrays;
import static org.assertj.core.api.Assertions.*;

class DocumentWorkspaceDomainTest {
 private static final Instant NOW=Instant.parse("2026-09-09T00:00:00Z");
 @Test void userAndOrganizationScopesAreExactAndHaveNoProjectGitBinding(){var user=workspace(DocumentWorkspace.ScopeType.USER,"owner","owner");assertThat(user.scopeId()).isEqualTo("owner");var organization=workspace(DocumentWorkspace.ScopeType.ORGANIZATION,"tenant","owner");assertThat(organization.scopeId()).isEqualTo("tenant");assertThatThrownBy(()->workspace(DocumentWorkspace.ScopeType.USER,"other","owner")).isInstanceOf(IllegalArgumentException.class);assertThat(componentNames(DocumentWorkspace.class)).doesNotContain("projectId","taskId","workspacePath","gitRef","repositoryId");}
 @Test void quotaReservationReleaseAndReductionAreRevisioned(){var value=workspace(DocumentWorkspace.ScopeType.USER,"owner","owner");var reserved=value.reserve(2,2_000_000,NOW.plusSeconds(1));assertThat(reserved.usage()).isEqualTo(new DocumentWorkspace.Usage(2,2_000_000));assertThat(reserved.revision()).isEqualTo(2);assertThatThrownBy(()->reserved.reserve(10,9_000_000,NOW)).isInstanceOf(IllegalStateException.class);assertThatThrownBy(()->reserved.changeQuota(new DocumentWorkspace.Quota(1,1_048_576),NOW)).isInstanceOf(IllegalArgumentException.class);assertThat(reserved.release(1,1_000_000,NOW).usage()).isEqualTo(new DocumentWorkspace.Usage(1,1_000_000));}
 @Test void pauseResumeArchiveFailClosed(){var active=workspace(DocumentWorkspace.ScopeType.USER,"owner","owner");var paused=active.pause(NOW.plusSeconds(1));assertThatThrownBy(()->paused.reserve(1,1,NOW)).isInstanceOf(IllegalStateException.class);var resumed=paused.resume(NOW.plusSeconds(2));assertThat(resumed.state()).isEqualTo(DocumentWorkspace.State.ACTIVE);var archived=resumed.archive(NOW.plusSeconds(3));assertThat(archived.archivedAt()).isNotNull();assertThat(archived.archive(NOW.plusSeconds(4))).isSameAs(archived);}
 @Test void publicCommandsContainNoPathGitRuntimeOrClientUsage(){assertThat(componentNames(DocumentWorkspaceApplicationApi.CreateCommand.class)).containsExactly("tenantId","actorUserId","scopeType","scopeId","name","quota","idempotencyKey").doesNotContain("absolutePath","projectId","gitRef","usage","state","runId","toolResult");}
 private static DocumentWorkspace workspace(DocumentWorkspace.ScopeType type,String scope,String owner){return new DocumentWorkspace("workspace","tenant",type,scope,owner,"Documents","document-workspace:workspace",new DocumentWorkspace.Quota(10,10_000_000),new DocumentWorkspace.Usage(0,0),DocumentWorkspace.State.ACTIVE,1,NOW,NOW,null);}
 private static java.util.List<String> componentNames(Class<?> type){return Arrays.stream(type.getRecordComponents()).map(RecordComponent::getName).toList();}
}

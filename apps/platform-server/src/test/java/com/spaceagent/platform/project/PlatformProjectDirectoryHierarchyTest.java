package com.spaceagent.platform.project;

import com.spaceagent.platform.conversation.api.StartConversationCommand;
import com.spaceagent.platform.conversation.application.ConversationApplicationService;
import com.spaceagent.platform.conversation.infrastructure.memory.InMemoryConversationRepository;
import com.spaceagent.platform.conversation.infrastructure.memory.InMemoryMessageRepository;
import com.spaceagent.platform.project.api.CreateProjectCommand;
import com.spaceagent.platform.project.api.ProjectDirectoryApplicationApi;
import com.spaceagent.platform.project.application.ProjectAccessPolicy;
import com.spaceagent.platform.project.application.ProjectApplicationService;
import com.spaceagent.platform.project.application.ProjectDirectoryApplicationService;
import com.spaceagent.platform.project.domain.SourceRepository;
import com.spaceagent.platform.project.domain.SourceRepositoryState;
import com.spaceagent.platform.project.domain.SourceRepositoryType;
import com.spaceagent.platform.project.domain.SourceRepositoryVisibility;
import com.spaceagent.platform.project.infrastructure.memory.InMemoryProjectDirectoryRepository;
import com.spaceagent.platform.project.infrastructure.memory.InMemoryProjectMembershipRepository;
import com.spaceagent.platform.project.infrastructure.memory.InMemoryProjectRepository;
import com.spaceagent.platform.project.infrastructure.memory.InMemorySourceRepositoryRepository;
import com.spaceagent.shared.exception.BusinessException;
import com.spaceagent.shared.id.UuidGenerator;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PlatformProjectDirectoryHierarchyTest {
    @Test
    void projectOwnsDirectoriesAndDirectoryOwnsConversationGrouping() {
        var ids = new UuidGenerator();
        var projects = new InMemoryProjectRepository();
        var memberships = new InMemoryProjectMembershipRepository();
        var directoryRepository = new InMemoryProjectDirectoryRepository();
        var sourceRepository = new InMemorySourceRepositoryRepository();
        var access = new ProjectAccessPolicy(projects, memberships, (tenant, user) -> true, (t, u, w) -> {});
        var projectApi = new ProjectApplicationService(
                projects, memberships, access, ids, () -> NOW, directoryRepository);
        var directoryApi = new ProjectDirectoryApplicationService(
                directoryRepository, sourceRepository, access, ids, () -> NOW);
        String projectId = projectApi.createProject(
                new CreateProjectCommand("tenant", "user", "Monorepo", null)).id();
        var source = source(projectId, "source-one", "Repository");
        var otherSource = source(projectId, "source-two", "Other");
        sourceRepository.save(source);
        sourceRepository.save(otherSource);

        var defaults = directoryApi.list(new ProjectDirectoryApplicationApi.ListQuery(
                "tenant", "user", projectId));
        assertThat(defaults).singleElement().satisfies(directory -> {
            assertThat(directory.defaultDirectory()).isTrue();
            assertThat(directory.sourceRepositoryId()).isNull();
        });
        var directory = directoryApi.create(new ProjectDirectoryApplicationApi.CreateCommand(
                "tenant", "user", projectId, source.id(), "Backend", "services/backend"));
        assertThat(directory.relativePath()).isEqualTo("services/backend");
        assertThat(directoryApi.create(new ProjectDirectoryApplicationApi.CreateCommand(
                "tenant", "user", projectId, source.id(), "Backend duplicate",
                "services/backend")).id()).isEqualTo(directory.id());
        assertThatThrownBy(() -> directoryApi.create(
                new ProjectDirectoryApplicationApi.CreateCommand(
                        "tenant", "user", projectId, source.id(), "Traversal", "../secret")))
                .isInstanceOfSatisfying(BusinessException.class, error ->
                        assertThat(error.getCode()).isEqualTo("PROJECT_DIRECTORY_INPUT_INVALID"));
        assertThatThrownBy(() -> directoryApi.resolveWorkspaceDirectory(
                new ProjectDirectoryApplicationApi.ResolveWorkspaceCommand(
                        "tenant", "user", projectId, directory.id(), otherSource.id())))
                .isInstanceOfSatisfying(BusinessException.class, error ->
                        assertThat(error.getCode()).isEqualTo(
                                "PROJECT_DIRECTORY_SOURCE_MISMATCH"));
        assertThatThrownBy(() -> directoryApi.resolveWorkspaceDirectory(
                new ProjectDirectoryApplicationApi.ResolveWorkspaceCommand(
                        "tenant", "user", projectId, directory.id(), source.id())))
                .isInstanceOfSatisfying(BusinessException.class, error ->
                        assertThat(error.getCode()).isEqualTo(
                                "PROJECT_DIRECTORY_SANDBOX_ROOT_REQUIRED"));

        var conversationRepository = new InMemoryConversationRepository();
        var conversations = new ConversationApplicationService(
                conversationRepository, new InMemoryMessageRepository(), ids, () -> NOW,
                null, directoryApi);
        var conversation = conversations.start(new StartConversationCommand(
                projectId, directory.id(), null, null, "tenant", "user", "agent", "Backend work"));
        assertThat(conversation.projectDirectoryId()).isEqualTo(directory.id());
        assertThat(conversations.pageByProjectDirectory(
                "tenant", "user", projectId, directory.id(), 1, 20).items())
                .extracting(value -> value.id()).containsExactly(conversation.id());

        var defaultConversation = conversations.start(new StartConversationCommand(
                projectId, null, null, null, "tenant", "user", "agent", "Project overview"));
        assertThat(defaultConversation.projectDirectoryId()).isEqualTo(defaults.getFirst().id());
    }

    private static SourceRepository source(String projectId, String providerId, String name) {
        return new SourceRepository(
                UUID.randomUUID().toString(), projectId, "tenant", null, null, null,
                providerId, name, "https://example.com/" + providerId + ".git", null,
                "main", SourceRepositoryType.GIT, SourceRepositoryState.READY,
                SourceRepositoryVisibility.PRIVATE, "user", NOW, NOW);
    }

    private static final Instant NOW = Instant.parse("2026-09-05T00:00:00Z");
}

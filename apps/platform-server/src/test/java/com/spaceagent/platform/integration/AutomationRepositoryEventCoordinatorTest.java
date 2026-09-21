package com.spaceagent.platform.integration;

import com.spaceagent.platform.automation.application.AutomationRepositoryEventApplicationService;
import com.spaceagent.platform.automation.domain.AutomationTrigger;
import com.spaceagent.platform.automation.domain.AutomationTriggerPersistence;
import com.spaceagent.platform.automation.domain.AutomationTriggerSource;
import com.spaceagent.platform.automation.domain.AutomationTriggerState;
import com.spaceagent.platform.automation.domain.AutomationTriggerType;
import com.spaceagent.platform.automation.infrastructure.memory.InMemoryAutomationTriggerRepository;
import com.spaceagent.platform.integration.application.AutomationRepositoryEventCoordinator;
import com.spaceagent.platform.tooling.api.McpConnectionQualificationApplicationApi;
import com.spaceagent.platform.tooling.api.McpMarketplaceApplicationApi;
import com.spaceagent.platform.tooling.domain.McpAuthType;
import com.spaceagent.platform.tooling.domain.McpConnectionState;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AutomationRepositoryEventCoordinatorTest {
    private static final Instant NOW = Instant.parse("2026-09-08T00:00:00Z");
    private static final String SNAPSHOT_HASH = "sha256:" + "a".repeat(64);
    private static final String PAYLOAD_HASH = "sha256:" + "b".repeat(64);
    private final String triggerId = UUID.randomUUID().toString();
    private final String lineageId = UUID.randomUUID().toString();
    private final String subscriptionId = UUID.randomUUID().toString();
    private final String installationId = UUID.randomUUID().toString();
    private final String connectionId = UUID.randomUUID().toString();
    private final String snapshotId = UUID.randomUUID().toString();
    private InMemoryAutomationTriggerRepository repository;
    private McpConnectionQualificationApplicationApi qualifications;
    private McpMarketplaceApplicationApi marketplace;

    @BeforeEach
    void setUp() {
        repository = new InMemoryAutomationTriggerRepository();
        var source = new AutomationTriggerSource.Repository(
                installationId, connectionId, 7, snapshotId, SNAPSHOT_HASH,
                "spaceagent/platform", Set.of(AutomationTriggerSource.RepositoryEvent.PUSH));
        String hash = AutomationTrigger.calculateConfigSha256(
                "tenant", "owner", "agent", "Repo", "process", AutomationTriggerType.REPOSITORY, source);
        repository.insertTrigger(new AutomationTrigger(
                triggerId, lineageId, 1, null, "tenant", "owner", "agent", "Repo", "process",
                AutomationTriggerType.REPOSITORY, source, hash, AutomationTriggerState.ACTIVE,
                1, NOW.minusSeconds(60), NOW.minusSeconds(30), null));
        repository.insertSubscription(new AutomationTriggerPersistence.Subscription(
                subscriptionId, triggerId, lineageId, "tenant", "owner", AutomationTriggerType.REPOSITORY,
                SNAPSHOT_HASH, AutomationTriggerPersistence.SubscriptionState.ACTIVE,
                1, NOW.minusSeconds(30), NOW.minusSeconds(30), null));
        qualifications = mock(McpConnectionQualificationApplicationApi.class);
        marketplace = mock(McpMarketplaceApplicationApi.class);
        qualified(7, snapshotId, "a".repeat(64), "owner", installationId);
    }

    @Test
    void exactQualifiedEventCreatesAndReplaysOneOccurrence() {
        var coordinator = coordinator();
        var first = coordinator.accept(command("event-1", "spaceagent/platform",
                AutomationTriggerSource.RepositoryEvent.PUSH));
        var replay = coordinator.accept(command("event-1", "spaceagent/platform",
                AutomationTriggerSource.RepositoryEvent.PUSH));
        assertThat(replay.occurrenceId()).isEqualTo(first.occurrenceId());
        assertThat(repository.findOccurrence("tenant", "owner", first.occurrenceId())).isPresent();
    }

    @Test
    void staleRevisionSnapshotOrInstallationFailsClosed() {
        qualified(8, snapshotId, "a".repeat(64), "owner", installationId);
        assertCode(coordinator(), command("event-2", "spaceagent/platform",
                AutomationTriggerSource.RepositoryEvent.PUSH),
                "AUTOMATION_REPOSITORY_EVENT_BINDING_INVALID");
        qualified(7, UUID.randomUUID().toString(), "a".repeat(64), "owner", installationId);
        assertCode(coordinator(), command("event-3", "spaceagent/platform",
                AutomationTriggerSource.RepositoryEvent.PUSH),
                "AUTOMATION_REPOSITORY_EVENT_BINDING_INVALID");
        qualified(7, snapshotId, "a".repeat(64), "owner", UUID.randomUUID().toString());
        assertCode(coordinator(), command("event-4", "spaceagent/platform",
                AutomationTriggerSource.RepositoryEvent.PUSH),
                "AUTOMATION_REPOSITORY_EVENT_BINDING_INVALID");
    }

    @Test
    void repositoryAndEventAllowlistAreExact() {
        assertCode(coordinator(), command("event-5", "other/repository",
                AutomationTriggerSource.RepositoryEvent.PUSH),
                "AUTOMATION_REPOSITORY_EVENT_UNSUPPORTED");
        assertCode(coordinator(), command("event-6", "spaceagent/platform",
                AutomationTriggerSource.RepositoryEvent.ISSUE),
                "AUTOMATION_REPOSITORY_EVENT_UNSUPPORTED");
    }

    @Test
    void toolingFailureIsRedactedAndCreatesNoOccurrence() {
        when(qualifications.qualification("tenant", "owner", connectionId))
                .thenThrow(new IllegalStateException("secret transport detail"));
        assertCode(coordinator(), command("event-7", "spaceagent/platform",
                AutomationTriggerSource.RepositoryEvent.PUSH),
                "AUTOMATION_REPOSITORY_EVENT_BINDING_INVALID");
        assertThat(repository.createOrFindOccurrence(new AutomationTriggerPersistence.Occurrence(
                UUID.randomUUID().toString(), triggerId, lineageId, subscriptionId, "tenant", "owner",
                "sha256:" + "c".repeat(64), PAYLOAD_HASH,
                AutomationTriggerPersistence.OccurrenceState.RECEIVED, NOW, null, 1, NOW, NOW)).state())
                .isEqualTo(AutomationTriggerPersistence.OccurrenceState.RECEIVED);
    }

    private AutomationRepositoryEventCoordinator coordinator() {
        AtomicInteger sequence = new AtomicInteger();
        var automation = new AutomationRepositoryEventApplicationService(repository,
                () -> new UUID(0, sequence.incrementAndGet()).toString(), () -> NOW);
        return new AutomationRepositoryEventCoordinator(automation, qualifications, marketplace);
    }

    private void qualified(long revision, String snapshot, String hash, String owner, String installation) {
        when(qualifications.qualification("tenant", owner, connectionId)).thenReturn(
                new McpConnectionQualificationApplicationApi.QualificationView(
                        connectionId, McpConnectionState.ACTIVE, revision, snapshot, hash,
                        "2025-06-18", "fixture", null, "1.0", 3, NOW, null));
        when(marketplace.connections("tenant", owner)).thenReturn(List.of(
                new McpMarketplaceApplicationApi.ConnectionView(
                        connectionId, installation, "tenant", owner, "https://mcp.example/mcp",
                        McpAuthType.OAUTH2, McpConnectionState.ACTIVE, true, null, null,
                        revision, NOW, NOW, null)));
    }

    private AutomationRepositoryEventCoordinator.Command command(
            String eventId, String repositoryId, AutomationTriggerSource.RepositoryEvent event) {
        return new AutomationRepositoryEventCoordinator.Command(
                triggerId, subscriptionId, repositoryId, event, eventId, PAYLOAD_HASH, NOW);
    }

    private static void assertCode(
            AutomationRepositoryEventCoordinator coordinator,
            AutomationRepositoryEventCoordinator.Command command,
            String code) {
        assertThatThrownBy(() -> coordinator.accept(command))
                .isInstanceOfSatisfying(com.spaceagent.shared.exception.BusinessException.class,
                        error -> assertThat(error.getCode()).isEqualTo(code))
                .hasMessageNotContaining("secret");
    }
}

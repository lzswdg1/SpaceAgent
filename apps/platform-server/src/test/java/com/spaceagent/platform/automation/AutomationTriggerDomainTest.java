package com.spaceagent.platform.automation;

import com.spaceagent.platform.automation.api.AutomationTriggerApplicationApi;
import com.spaceagent.platform.automation.domain.AutomationTrigger;
import com.spaceagent.platform.automation.domain.AutomationTriggerSource;
import com.spaceagent.platform.automation.domain.AutomationTriggerState;
import com.spaceagent.platform.automation.domain.AutomationTriggerType;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AutomationTriggerDomainTest {
    private static final Instant NOW = Instant.parse("2026-09-08T00:00:00Z");

    @Test
    void supportsFourSecretFreeVersionedEventSourcesWithStableHashes() {
        List<AutomationTriggerSource> sources = List.of(
                new AutomationTriggerSource.Webhook(
                        List.of("webhook-key:key-v1", "webhook-key:key-v0"),
                        "HMAC_SHA256", 64_000, 300),
                new AutomationTriggerSource.Repository(
                        "installation", "connection", 4, "snapshot",
                        "sha256:" + "a".repeat(64), "octocat/demo",
                        Set.of(AutomationTriggerSource.RepositoryEvent.PUSH,
                                AutomationTriggerSource.RepositoryEvent.PULL_REQUEST)),
                new AutomationTriggerSource.TaskCompletion(
                        "project", "task", Set.of(
                                AutomationTriggerSource.TaskOutcome.SUCCEEDED)),
                new AutomationTriggerSource.FollowUp("conversation", "task", 3_600));

        assertThat(sources.stream().map(this::trigger))
                .extracting(AutomationTrigger::type)
                .containsExactly(
                        AutomationTriggerType.WEBHOOK,
                        AutomationTriggerType.REPOSITORY,
                        AutomationTriggerType.TASK_COMPLETION,
                        AutomationTriggerType.FOLLOW_UP);
        assertThat(sources.stream().map(this::trigger)
                .map(AutomationTrigger::configSha256)).allMatch(value ->
                value.matches("sha256:[0-9a-f]{64}"));
    }

    @Test
    void lifecycleChangesStateButNeverRewritesVersionedConfiguration() {
        AutomationTrigger draft = trigger(new AutomationTriggerSource.FollowUp(
                "conversation", "task", 600));
        AutomationTrigger active = draft.activate(NOW.plusSeconds(1));
        AutomationTrigger paused = active.pause();
        AutomationTrigger resumed = paused.activate(NOW.plusSeconds(2));
        AutomationTrigger archived = resumed.archive(NOW.plusSeconds(3));

        assertThat(List.of(draft.state(), active.state(), paused.state(),
                resumed.state(), archived.state())).containsExactly(
                AutomationTriggerState.DRAFT, AutomationTriggerState.ACTIVE,
                AutomationTriggerState.PAUSED, AutomationTriggerState.ACTIVE,
                AutomationTriggerState.ARCHIVED);
        assertThat(archived.configSha256()).isEqualTo(draft.configSha256());
        assertThat(archived.version()).isEqualTo(1);
        assertThat(archived.revision()).isEqualTo(5);
    }

    @Test
    void rejectsSecretsMutableScheduleTypesAndInvalidExactBindings() {
        assertThatThrownBy(() -> new AutomationTriggerSource.Webhook(
                List.of("actual-webhook-secret"), "HMAC_SHA256", 1_000, 60))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new AutomationTriggerSource.Repository(
                "installation", "connection", 0, "snapshot",
                "sha256:" + "a".repeat(64), "octocat/demo",
                Set.of(AutomationTriggerSource.RepositoryEvent.PUSH)))
                .isInstanceOf(IllegalArgumentException.class);
        AutomationTriggerSource source = new AutomationTriggerSource.FollowUp(
                "conversation", "task", 600);
        assertThatThrownBy(() -> new AutomationTrigger(
                "id", "lineage", 1, null, "tenant", "owner", "agent",
                "description", "prompt", AutomationTriggerType.SCHEDULED, source,
                AutomationTrigger.calculateConfigSha256(
                        "tenant", "owner", "agent", "description", "prompt",
                        AutomationTriggerType.SCHEDULED, source),
                AutomationTriggerState.DRAFT, 1, NOW, null, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void publicCommandsContainNoSecretPayloadDeliveryOrTerminalState() {
        assertThat(AutomationTriggerApplicationApi.CreateCommand.class.getRecordComponents())
                .extracting(java.lang.reflect.RecordComponent::getName)
                .containsExactly("tenantId", "ownerUserId", "agentId", "description",
                        "prompt", "source", "idempotencyKey")
                .doesNotContain("secret", "payload", "signature", "terminalState",
                        "executionId", "agentRunId");
    }

    private AutomationTrigger trigger(AutomationTriggerSource source) {
        String hash = AutomationTrigger.calculateConfigSha256(
                "tenant", "owner", "agent", "description", "prompt",
                source.type(), source);
        return new AutomationTrigger(
                "trigger-version", "trigger", 1, null, "tenant", "owner", "agent",
                "description", "prompt", source.type(), source, hash,
                AutomationTriggerState.DRAFT, 1, NOW, null, null);
    }
}

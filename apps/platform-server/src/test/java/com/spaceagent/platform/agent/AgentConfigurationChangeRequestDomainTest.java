package com.spaceagent.platform.agent;

import com.spaceagent.platform.agent.domain.AgentConfigurationChangeRequest;
import com.spaceagent.platform.agent.domain.AgentConfigurationChangeState;
import com.spaceagent.platform.agent.domain.AgentConfigurationProposal;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AgentConfigurationChangeRequestDomainTest {

    private static final Instant NOW = Instant.parse("2026-09-11T12:00:00Z");

    @Test
    void pendingProposalCanBeReplacedWithoutCreatingHistory() {
        AgentConfigurationChangeRequest request = pending();
        AgentConfigurationProposal replacement = proposal("second", "b".repeat(64));

        AgentConfigurationChangeRequest updated = request.replacePending(
                "00000000-0000-4000-8000-000000000011", 4, "c".repeat(64),
                "sha256:" + "d".repeat(64), replacement, NOW.plusSeconds(1));

        assertThat(updated.id()).isEqualTo(request.id());
        assertThat(updated.revision()).isEqualTo(2);
        assertThat(updated.proposal()).isEqualTo(replacement);
        assertThat(updated.approvalId()).isNotEqualTo(request.approvalId());
    }

    @Test
    void terminalApplicationErasesProposalBodyAndPinsAppliedRevision() {
        AgentConfigurationChangeRequest applied = pending().close(
                AgentConfigurationChangeState.APPLIED, "organization-owner", "approved",
                4L, NOW.plusSeconds(2));

        assertThat(applied.state()).isEqualTo(AgentConfigurationChangeState.APPLIED);
        assertThat(applied.proposal()).isNull();
        assertThat(applied.appliedAgentRevision()).isEqualTo(4);
        assertThatThrownBy(() -> applied.close(
                AgentConfigurationChangeState.REJECTED, "owner", null, null, NOW.plusSeconds(3)))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void creatorCannotReceiveAnApprovalRequestForTheirOwnAgent() {
        assertThatThrownBy(() -> AgentConfigurationChangeRequest.pending(
                "00000000-0000-4000-8000-000000000001",
                "00000000-0000-4000-8000-000000000010",
                "tenant", "agent", "same-user", "same-user", 3, "a".repeat(64),
                "sha256:" + "b".repeat(64), proposal("next", "b".repeat(64)), NOW))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static AgentConfigurationChangeRequest pending() {
        return AgentConfigurationChangeRequest.pending(
                "00000000-0000-4000-8000-000000000001",
                "00000000-0000-4000-8000-000000000010",
                "tenant", "agent", "agent-owner", "member", 3, "a".repeat(64),
                "sha256:" + "b".repeat(64), proposal("first", "a".repeat(64)), NOW);
    }

    private static AgentConfigurationProposal proposal(String prompt, String configHash) {
        return new AgentConfigurationProposal(
                "Agent", null, prompt, null, "provider", "model", 0.2,
                200_000, 4096, 25, "ask", true, false, true,
                List.of("knowledge"), List.of("web_search"), List.of(), configHash);
    }
}

package com.spaceagent.platform.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.spaceagent.platform.agent.api.CreateAgentDefinitionCommand;
import com.spaceagent.platform.agent.api.UpdateAgentDefinitionCommand;
import com.spaceagent.platform.agent.application.AgentApplicationService;
import com.spaceagent.platform.agent.application.AgentConfigurationFactory;
import com.spaceagent.platform.agent.infrastructure.memory.InMemoryAgentCurrentConfigurationRepository;
import com.spaceagent.platform.agent.infrastructure.memory.InMemoryAgentRepository;
import com.spaceagent.platform.inference.api.ModelPoolApplicationApi;
import com.spaceagent.platform.inference.api.ModelPoolResolutionView;
import com.spaceagent.platform.inference.api.ResolvedModelCandidateView;
import com.spaceagent.shared.exception.BusinessException;
import com.spaceagent.shared.id.IdGenerator;
import com.spaceagent.shared.time.TimeProvider;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PlatformAgentModelPoolBindingTest {

    @Test
    void activePoolIsEffectiveImmediatelyAndCanReturnToDirectBinding() {
        ModelPoolApplicationApi pools = mock(ModelPoolApplicationApi.class);
        when(pools.resolvePool("tenant-1", "owner-1", "pool-1"))
                .thenReturn(resolution("pool-1"));
        Fixture fixture = fixture(pools);

        var created = fixture.agents().create(poolCommand("pool-1", null, null));

        assertThat(created.modelPoolId()).isEqualTo("pool-1");
        assertThat(created.modelProviderId()).isNull();
        assertThat(created.modelId()).isNull();
        verify(pools).resolvePool("tenant-1", "owner-1", "pool-1");

        var direct = fixture.agents().update(new UpdateAgentDefinitionCommand(
                "owner-1", "tenant-1", created.id(),
                null, null, null, "", "provider-1", "model-1",
                null, null, null, null, null, null, null,
                null, null, null));
        assertThat(direct.modelPoolId()).isNull();
        assertThat(direct.modelProviderId()).isEqualTo("provider-1");
        assertThat(direct.modelId()).isEqualTo("model-1");
        assertThat(direct.revision()).isEqualTo(2);
    }

    @Test
    void mixedOrUnavailablePoolBindingIsRejectedBeforeCurrentConfigurationPersistence() {
        ModelPoolApplicationApi pools = mock(ModelPoolApplicationApi.class);
        when(pools.resolvePool("tenant-1", "owner-1", "draft-pool"))
                .thenThrow(new BusinessException(
                        "ModelPool is not active",
                        org.springframework.http.HttpStatus.CONFLICT,
                        "MODEL_POOL_NOT_ACTIVE"));
        Fixture fixture = fixture(pools);

        assertThatThrownBy(() -> fixture.agents().create(
                poolCommand("pool-1", "provider-1", "model-1")))
                .isInstanceOfSatisfying(BusinessException.class,
                        error -> assertThat(error.getCode()).isEqualTo("MODEL_BINDING_CONFLICT"));
        assertThatThrownBy(() -> fixture.agents().create(
                poolCommand("draft-pool", null, null)))
                .isInstanceOfSatisfying(BusinessException.class,
                        error -> assertThat(error.getCode()).isEqualTo("MODEL_POOL_NOT_ACTIVE"));
        assertThat(fixture.agents().listByTenantAndOwner("tenant-1", "owner-1")).isEmpty();
    }

    private static CreateAgentDefinitionCommand poolCommand(
            String poolId,
            String providerId,
            String modelId) {
        return new CreateAgentDefinitionCommand(
                "owner-1", "tenant-1", "pool-agent", "description", "be precise",
                poolId, providerId, modelId, 0.4, 4096, 20, "private",
                true, false, false, List.of(), List.of(), List.of());
    }

    private static ModelPoolResolutionView resolution(String poolId) {
        return new ModelPoolResolutionView(poolId, List.of(
                new ResolvedModelCandidateView(
                        "member-1", "provider-1", "provider-model-1",
                        "model-1", 10, 1)));
    }

    private static Fixture fixture(ModelPoolApplicationApi pools) {
        AtomicInteger sequence = new AtomicInteger();
        IdGenerator ids = () -> "id-" + sequence.incrementAndGet();
        TimeProvider time = () -> Instant.parse("2026-08-22T16:00:00Z");
        InMemoryAgentRepository agents = new InMemoryAgentRepository();
        var current = new InMemoryAgentCurrentConfigurationRepository();
        AgentApplicationService service = new AgentApplicationService(
                agents, ids, time, null, pools, null,
                new AgentConfigurationFactory(new ObjectMapper()), null, current);
        return new Fixture(service);
    }

    private record Fixture(
            AgentApplicationService agents) {
    }
}

package com.spaceagent.platform.controlplane;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.spaceagent.platform.agent.api.AgentApplicationApi;
import com.spaceagent.platform.agent.api.AgentOwnershipPort;
import com.spaceagent.platform.agent.api.AgentDefinitionView;
import com.spaceagent.platform.agent.api.CreateAgentDefinitionCommand;
import com.spaceagent.platform.agent.api.UpdateAgentDefinitionCommand;
import com.spaceagent.platform.agent.application.AgentApplicationService;
import com.spaceagent.platform.agent.application.AgentConfigurationFactory;
import com.spaceagent.platform.agent.infrastructure.memory.InMemoryAgentCurrentConfigurationRepository;
import com.spaceagent.platform.agent.infrastructure.memory.InMemoryAgentRepository;
import com.spaceagent.platform.identity.api.IdentityApplicationApi;
import com.spaceagent.platform.identity.api.UpdateUserProfileCommand;
import com.spaceagent.platform.identity.api.UserProfileView;
import com.spaceagent.platform.identity.application.IdentityApplicationService;
import com.spaceagent.platform.identity.infrastructure.memory.InMemoryIdentityRepository;
import com.spaceagent.platform.inference.api.AddProviderModelCommand;
import com.spaceagent.platform.inference.api.CreateModelProviderCommand;
import com.spaceagent.platform.inference.api.InferenceApplicationApi;
import com.spaceagent.platform.inference.api.InferenceOwnershipPort;
import com.spaceagent.platform.inference.api.ModelProviderView;
import com.spaceagent.platform.inference.api.ProviderModelView;
import com.spaceagent.platform.inference.application.InferenceApplicationService;
import com.spaceagent.platform.inference.domain.ModelProviderSecretCipher;
import com.spaceagent.platform.inference.infrastructure.memory.InMemoryInferenceProviderRepository;
import com.spaceagent.platform.inference.infrastructure.memory.InMemoryModelPoolRepository;
import com.spaceagent.platform.knowledge.api.KnowledgeApplicationApi;
import com.spaceagent.platform.knowledge.api.KnowledgeDocumentView;
import com.spaceagent.platform.knowledge.api.MarkKnowledgeDocumentStatusCommand;
import com.spaceagent.platform.knowledge.application.KnowledgeApplicationService;
import com.spaceagent.platform.knowledge.domain.KnowledgeDocumentStatus;
import com.spaceagent.platform.knowledge.infrastructure.memory.InMemoryKnowledgeChunkRepository;
import com.spaceagent.platform.knowledge.infrastructure.memory.InMemoryKnowledgeDocumentRepository;
import com.spaceagent.shared.id.IdGenerator;
import com.spaceagent.shared.time.TimeProvider;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies the M4b strangler boundary: platform-server owns authoritative
 * control-plane state while memory repositories remain scaffolding.
 */
class PlatformControlPlaneMigrationTest {

    private static final Instant NOW = Instant.parse("2026-08-18T08:00:00Z");

    @Test
    void identityProfileIsOwnedByPlatformIdentityApi() {
        IdentityApplicationApi api = identityApi();
        String userId = api.createUser(
                new com.spaceagent.platform.identity.api.CreateUserCommand(
                        api.createTenant(
                                new com.spaceagent.platform.identity.api.CreateTenantCommand("Acme", "acme")).id(),
                        "alice@example.com",
                        "Alice")).id();

        UserProfileView profile = api.saveProfile(new UpdateUserProfileCommand(
                userId, "DIRECT", "UTC", "Platform-owned profile"));

        assertEquals("DIRECT", profile.preferredTone());
        assertEquals("UTC", profile.timezone());
        assertEquals("Platform-owned profile", profile.summary());
        assertTrue(api.findProfile(userId).isPresent());
    }

    @Test
    void agentConfigurationIsOwnedByAgentModule() {
        AgentApplicationApi api = agentApi();
        AgentDefinitionView created = api.create(new CreateAgentDefinitionCommand(
                "owner-1", "tenant-1", "builder", "Build agent", "Build software",
                "provider-1", "model-a", 0.2, 8000, 40, "auto",
                true, true, true,
                List.of("kb-1"), List.of("tool-1"), List.of("skill-1")));

        assertEquals(1L, created.revision());
        assertTrue(((AgentOwnershipPort) api).isOwner(created.id(), "owner-1"));
        assertFalse(((AgentOwnershipPort) api).isOwner(created.id(), "owner-2"));

        AgentDefinitionView updated = api.update(new UpdateAgentDefinitionCommand(
                "owner-1", "tenant-1", created.id(), "builder-v2", null, null, null, null,
                null, null, null, null, null, null, null, null, null, null));

        assertEquals("builder-v2", updated.name());
        assertEquals(2L, updated.revision());
        assertEquals(List.of(updated), api.listByOwner("owner-1"));

        api.archive("owner-1", created.id());
        assertTrue(api.listByOwner("owner-1").isEmpty());
    }

    @Test
    void modelProviderStateIsOwnedByInferenceModule() {
        InferenceApplicationApi api = inferenceApi();
        ModelProviderView provider = api.createProvider(new CreateModelProviderCommand(
                "tenant-1", "owner-1", "openai", "openai-compatible",
                "https://api.openai.com/v1", "secret", "bearer", true, true,
                List.of(new CreateModelProviderCommand.ProviderModelDraft(
                        "gpt-4o", "GPT-4o", 128000))));

        assertTrue(provider.hasSecret());
        assertTrue(((InferenceOwnershipPort) api).isOwner(provider.id(), "owner-1"));
        assertFalse(((InferenceOwnershipPort) api).isOwner(provider.id(), "owner-2"));

        ProviderModelView model = api.addModel(new AddProviderModelCommand(
                "tenant-1", provider.id(), "gpt-4o-mini", "GPT-4o mini", 128000, false));
        assertEquals("gpt-4o-mini", model.modelId());
        assertEquals(2, api.listModels(provider.id()).size());

        api.validateSelection("tenant-1", provider.id(), "gpt-4o-mini");
    }

    @Test
    void knowledgeDocumentLifecycleIsOwnedByKnowledgeModule() {
        KnowledgeApplicationApi api = knowledgeApi();
        KnowledgeDocumentView document = api.createDocument(
                new com.spaceagent.platform.knowledge.api.CreateKnowledgeDocumentCommand(
                        "owner-1", "architecture.md", "text/markdown", "storage://architecture.md"));

        KnowledgeDocumentView ready = api.markDocumentStatus(new MarkKnowledgeDocumentStatusCommand(
                document.id(), KnowledgeDocumentStatus.READY, null));
        assertEquals(KnowledgeDocumentStatus.READY, ready.status());
        assertEquals(List.of(ready), api.findDocumentsByOwner("owner-1"));

        api.deleteDocument(document.id());
        assertTrue(api.findDocumentsByOwner("owner-1").isEmpty());
    }

    private IdentityApplicationApi identityApi() {
        return new IdentityApplicationService(
                new InMemoryIdentityRepository(),
                sequentialIdGenerator("identity"),
                fixedTimeProvider());
    }

    private AgentApplicationApi agentApi() {
        IdGenerator ids = sequentialIdGenerator("agent");
        TimeProvider time = fixedTimeProvider();
        InMemoryAgentRepository agents = new InMemoryAgentRepository();
        return new AgentApplicationService(
                agents, ids, time, null, null, null,
                new AgentConfigurationFactory(new ObjectMapper()), null,
                new InMemoryAgentCurrentConfigurationRepository());
    }

    private InferenceApplicationApi inferenceApi() {
        var providers = new InMemoryInferenceProviderRepository();
        var time = fixedTimeProvider();
        return new InferenceApplicationService(
                providers,
                new InMemoryModelPoolRepository(),
                new TestSecretCipher(),
                value -> value,
                sequentialIdGenerator("inference"),
                time,
                new com.spaceagent.platform.inference.infrastructure.memory.InMemoryProviderHealthProbeRepository(
                        providers, time));
    }

    private KnowledgeApplicationApi knowledgeApi() {
        return new KnowledgeApplicationService(
                new InMemoryKnowledgeDocumentRepository(),
                new InMemoryKnowledgeChunkRepository(),
                sequentialIdGenerator("knowledge"),
                fixedTimeProvider());
    }

    private IdGenerator sequentialIdGenerator(String prefix) {
        AtomicInteger sequence = new AtomicInteger();
        return () -> prefix + "-" + sequence.incrementAndGet();
    }

    private TimeProvider fixedTimeProvider() {
        return () -> NOW;
    }

    private static final class TestSecretCipher implements ModelProviderSecretCipher {
        @Override
        public String encrypt(String plaintext) {
            return "encrypted:" + plaintext;
        }

        @Override
        public String decrypt(String encoded) {
            return encoded.startsWith("encrypted:") ? encoded.substring("encrypted:".length()) : encoded;
        }
    }
}

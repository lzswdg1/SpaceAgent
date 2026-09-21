package com.spaceagent.platform.controlplane;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.spaceagent.platform.agent.api.AgentApplicationApi;
import com.spaceagent.platform.agent.api.AgentOwnershipPort;
import com.spaceagent.platform.agent.api.AgentDefinitionView;
import com.spaceagent.platform.agent.api.CreateAgentDefinitionCommand;
import com.spaceagent.platform.agent.application.AgentApplicationService;
import com.spaceagent.platform.agent.application.AgentConfigurationFactory;
import com.spaceagent.platform.agent.infrastructure.memory.InMemoryAgentCurrentConfigurationRepository;
import com.spaceagent.platform.agent.domain.AgentDefinitionStatus;
import com.spaceagent.platform.agent.infrastructure.memory.InMemoryAgentRepository;
import com.spaceagent.platform.identity.api.CreateTenantCommand;
import com.spaceagent.platform.identity.api.CreateUserCommand;
import com.spaceagent.platform.identity.api.AddTenantMembershipCommand;
import com.spaceagent.platform.identity.api.IdentityApplicationApi;
import com.spaceagent.platform.identity.api.IdentityOwnershipPort;
import com.spaceagent.platform.identity.api.TenantView;
import com.spaceagent.platform.identity.api.UserView;
import com.spaceagent.platform.identity.application.IdentityApplicationService;
import com.spaceagent.platform.identity.infrastructure.memory.InMemoryIdentityRepository;
import com.spaceagent.platform.identity.domain.TenantRole;
import com.spaceagent.platform.knowledge.api.AddKnowledgeChunkCommand;
import com.spaceagent.platform.knowledge.api.CreateKnowledgeDocumentCommand;
import com.spaceagent.platform.knowledge.api.KnowledgeApplicationApi;
import com.spaceagent.platform.knowledge.api.KnowledgeChunkView;
import com.spaceagent.platform.knowledge.api.KnowledgeDocumentView;
import com.spaceagent.platform.knowledge.api.KnowledgeOwnershipPort;
import com.spaceagent.platform.knowledge.application.KnowledgeApplicationService;
import com.spaceagent.platform.knowledge.domain.KnowledgeDocumentStatus;
import com.spaceagent.platform.knowledge.infrastructure.memory.InMemoryKnowledgeChunkRepository;
import com.spaceagent.platform.knowledge.infrastructure.memory.InMemoryKnowledgeDocumentRepository;
import com.spaceagent.shared.exception.BusinessException;
import com.spaceagent.shared.id.IdGenerator;
import com.spaceagent.shared.time.TimeProvider;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies the first M4 platform-server control-plane slice: identity, agent, and
 * knowledge expose public application APIs and ownership ports implemented inside
 * their own module, with no dependency on another module's persistence package.
 */
class PlatformControlPlaneApiTest {

    private static final Instant NOW = Instant.parse("2026-08-18T08:00:00Z");

    @Test
    void identityPublicApiCreatesTenantUserAndVerifiesMembership() {
        IdentityApplicationApi api = identityApplicationApi();
        IdentityOwnershipPort ownership = (IdentityOwnershipPort) api;

        TenantView tenant = api.createTenant(new CreateTenantCommand("Acme", "acme"));
        UserView user = api.createUser(new CreateUserCommand(
                tenant.id(),
                "alice@example.com",
                "Alice"));
        api.addTenantMembership(new AddTenantMembershipCommand(
                tenant.id(), user.id(), TenantRole.OWNER));

        assertEquals("Acme", tenant.name());
        assertEquals("acme", tenant.slug());
        assertEquals(tenant.id(), user.tenantId());
        assertTrue(ownership.isMemberOfTenant(tenant.id(), user.id()));
        assertFalse(ownership.isMemberOfTenant("other-tenant", user.id()));
        assertEquals(user, api.findUser(user.id()).orElseThrow());
    }

    @Test
    void identityPublicApiRejectsUserWithoutTenant() {
        IdentityApplicationApi api = identityApplicationApi();

        BusinessException error = assertThrows(
                BusinessException.class,
                () -> api.createUser(new CreateUserCommand(
                        "missing-tenant",
                        "alice@example.com",
                        "Alice")));

        assertEquals("Tenant not found: missing-tenant", error.getMessage());
    }

    @Test
    void agentPublicApiRegistersDefinitionAndChecksOwnership() {
        AgentApplicationApi api = agentApplicationApi();
        AgentOwnershipPort ownership = (AgentOwnershipPort) api;

        AgentDefinitionView definition = api.create(new CreateAgentDefinitionCommand(
                "owner-1", "tenant-1", "builder", "Build agent", "build software",
                null, null, 0.7, 4096, 25, "private",
                true, false, false, List.of(), List.of(), List.of()));

        assertEquals("builder", definition.name());
        assertEquals(AgentDefinitionStatus.ACTIVE, definition.status());
        assertTrue(ownership.isOwner(definition.id(), "owner-1"));
        assertFalse(ownership.isOwner(definition.id(), "owner-2"));
    }

    @Test
    void knowledgePublicApiCreatesDocumentChunksAndChecksAccess() {
        KnowledgeApplicationApi api = knowledgeApplicationApi();
        KnowledgeOwnershipPort ownership = (KnowledgeOwnershipPort) api;

        KnowledgeDocumentView document = api.createDocument(new CreateKnowledgeDocumentCommand(
                "owner-1",
                "architecture.md",
                "text/markdown",
                "storage://architecture.md"));

        KnowledgeChunkView first = api.addChunk(new AddKnowledgeChunkCommand(
                document.id(),
                0,
                "Java owns state",
                "embedding://doc/0"));
        KnowledgeChunkView second = api.addChunk(new AddKnowledgeChunkCommand(
                document.id(),
                1,
                "Python owns intelligence",
                "embedding://doc/1"));

        assertEquals(KnowledgeDocumentStatus.UPLOADED, document.status());
        assertEquals(List.of(first, second), api.findChunksByDocument(document.id()));
        assertTrue(ownership.canAccess(document.id(), "owner-1"));
        assertFalse(ownership.canAccess(document.id(), "owner-2"));
    }

    @Test
    void knowledgePublicApiRejectsChunkForMissingDocument() {
        KnowledgeApplicationApi api = knowledgeApplicationApi();

        BusinessException error = assertThrows(
                BusinessException.class,
                () -> api.addChunk(new AddKnowledgeChunkCommand(
                        "missing-document",
                        0,
                        "content",
                        "embedding://doc/0")));

        assertEquals("Knowledge document not found: missing-document", error.getMessage());
    }

    private IdentityApplicationApi identityApplicationApi() {
        return new IdentityApplicationService(
                new InMemoryIdentityRepository(),
                sequentialIdGenerator("identity"),
                fixedTimeProvider());
    }

    private AgentApplicationApi agentApplicationApi() {
        IdGenerator ids = sequentialIdGenerator("agent");
        TimeProvider time = fixedTimeProvider();
        InMemoryAgentRepository agents = new InMemoryAgentRepository();
        return new AgentApplicationService(
                agents, ids, time, null, null, null,
                new AgentConfigurationFactory(new ObjectMapper()), null,
                new InMemoryAgentCurrentConfigurationRepository());
    }

    private KnowledgeApplicationApi knowledgeApplicationApi() {
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
}

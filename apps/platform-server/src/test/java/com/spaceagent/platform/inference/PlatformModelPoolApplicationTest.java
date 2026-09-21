package com.spaceagent.platform.inference;

import com.spaceagent.platform.inference.api.AddModelPoolMemberCommand;
import com.spaceagent.platform.inference.api.AddProviderModelCommand;
import com.spaceagent.platform.inference.api.CreateModelPoolCommand;
import com.spaceagent.platform.inference.api.CreateModelProviderCommand;
import com.spaceagent.platform.inference.api.RemoveModelPoolMemberCommand;
import com.spaceagent.platform.inference.api.TestProviderConnectionCommand;
import com.spaceagent.platform.inference.api.UpdateModelPoolStatusCommand;
import com.spaceagent.platform.inference.api.UpdateModelProviderCommand;
import com.spaceagent.platform.inference.application.InferenceApplicationService;
import com.spaceagent.platform.inference.application.ModelPoolApplicationService;
import com.spaceagent.platform.inference.application.ProviderConnectionApplicationService;
import com.spaceagent.platform.inference.domain.ModelPoolRoutingStrategy;
import com.spaceagent.platform.inference.domain.ModelPoolStatus;
import com.spaceagent.platform.inference.domain.ModelPoolVisibility;
import com.spaceagent.platform.inference.domain.ModelProviderSecretCipher;
import com.spaceagent.platform.inference.domain.ProviderConnectionProbeResult;
import com.spaceagent.platform.inference.domain.ProviderConnectionStatus;
import com.spaceagent.platform.inference.infrastructure.memory.InMemoryInferenceProviderRepository;
import com.spaceagent.platform.inference.infrastructure.memory.InMemoryModelPoolRepository;
import com.spaceagent.platform.inference.infrastructure.memory.InMemoryProviderHealthProbeRepository;
import com.spaceagent.platform.inference.infrastructure.memory.InMemoryModelPriceRepository;
import com.spaceagent.platform.inference.application.ModelPricingApplicationService;
import com.spaceagent.platform.inference.api.ModelPricingApplicationApi;
import com.spaceagent.platform.inference.infrastructure.InferenceProperties;
import com.spaceagent.shared.exception.BusinessException;
import com.spaceagent.shared.id.UuidGenerator;
import com.spaceagent.shared.time.TimeProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlatformModelPoolApplicationTest {

    private static final Instant NOW = Instant.parse("2026-08-22T14:00:00Z");

    private InMemoryInferenceProviderRepository providers;
    private InMemoryModelPoolRepository pools;
    private InferenceApplicationService inference;
    private ModelPoolApplicationService modelPools;
    private InMemoryProviderHealthProbeRepository healthProbes;
    private InMemoryModelPriceRepository prices;
    private ModelPricingApplicationService pricing;
    private UuidGenerator ids;

    @BeforeEach
    void setUp() {
        providers = new InMemoryInferenceProviderRepository();
        pools = new InMemoryModelPoolRepository();
        ids = new UuidGenerator();
        TimeProvider time = () -> NOW;
        healthProbes = new InMemoryProviderHealthProbeRepository(providers, time);
        inference = new InferenceApplicationService(
                providers, pools, new TestCipher(), value -> value, ids, time, healthProbes);
        prices = new InMemoryModelPriceRepository();
        modelPools = new ModelPoolApplicationService(pools, providers, ids, time, prices);
        pricing = new ModelPricingApplicationService(prices,providers,ids,time);
    }

    @Test
    void providerTestPersistsActiveHealthAndSensitiveUpdateResetsIt() {
        var provider = createProvider("primary", "model-a");
        ProviderConnectionApplicationService connection = new ProviderConnectionApplicationService(
                providers,
                value -> new ProviderConnectionProbeResult(
                        true, 12, List.of("model-a", "model-b"), null),
                () -> NOW, healthProbes, new UuidGenerator(), new InferenceProperties());

        var result = connection.testProvider(new TestProviderConnectionCommand(
                "tenant-1", provider.id()));
        assertTrue(result.success());
        assertEquals(ProviderConnectionStatus.ACTIVE, result.status());
        assertEquals(List.of("model-a", "model-b"), result.discoveredModelIds());
        assertEquals(ProviderConnectionStatus.ACTIVE,
                inference.findProvider(provider.id()).orElseThrow().connectionStatus());

        var renamed = inference.updateProvider(new UpdateModelProviderCommand(
                "tenant-1", provider.id(), "renamed", null, null, null, null,
                null, null, null));
        assertEquals(ProviderConnectionStatus.ACTIVE, renamed.connectionStatus());
        var changed = inference.updateProvider(new UpdateModelProviderCommand(
                "tenant-1", provider.id(), null, null, "https://new.example.com/v1",
                null, null, null, null, null));
        assertEquals(ProviderConnectionStatus.UNTESTED, changed.connectionStatus());
        assertEquals(null, changed.lastTestedAt());
    }

    @Test
    void omittedModelContextDefaultsTo200kAndExplicitValuesRemainUnchanged() {
        var provider = inference.createProvider(new CreateModelProviderCommand(
                "tenant-1", "owner-1", "default-context", "openai-compatible",
                "https://example.com/v1", "secret", "bearer", true, false,
                List.of(new CreateModelProviderCommand.ProviderModelDraft(
                        "model-default", "Model Default", null))));
        assertEquals(200_000, inference.listModels(provider.id()).getFirst().maxContextTokens());

        var explicit = inference.addModel(new AddProviderModelCommand(
                "tenant-1", provider.id(), "model-explicit", "Model Explicit", 131_072, false));
        assertEquals(131_072, explicit.maxContextTokens());

        inference.updateProvider(new UpdateModelProviderCommand(
                "tenant-1", provider.id(), null, null, null, null, null, null, null,
                List.of(new CreateModelProviderCommand.ProviderModelDraft(
                        "model-updated", "Model Updated", null))));
        assertEquals(200_000, inference.listModels(provider.id()).getFirst().maxContextTokens());
    }

    @Test
    void poolRejectsUntestedProviderThenResolvesStablePriorityFallbacks() {
        var first = createProvider("first", "model-a");
        var second = createProvider("second", "model-b");
        var pool = modelPools.createPool(new CreateModelPoolCommand(
                "tenant-1", "owner-1", "default-pool",
                ModelPoolVisibility.ORGANIZATION, ModelPoolRoutingStrategy.PRIORITY, true));
        String firstModel = inference.listModels(first.id()).getFirst().id();
        String secondModel = inference.listModels(second.id()).getFirst().id();

        BusinessException untested = assertThrows(BusinessException.class,
                () -> modelPools.addMember(new AddModelPoolMemberCommand(
                        "tenant-1", "owner-1", pool.id(), first.id(), firstModel, 20, 1)));
        assertEquals("MODEL_POOL_PROVIDER_NOT_ACTIVE", untested.getCode());

        testSuccess(first.id(), "model-a");
        testSuccess(second.id(), "model-b");
        modelPools.addMember(new AddModelPoolMemberCommand(
                "tenant-1", "owner-1", pool.id(), first.id(), firstModel, 20, 1));
        modelPools.addMember(new AddModelPoolMemberCommand(
                "tenant-1", "owner-1", pool.id(), second.id(), secondModel, 10, 2));
        assertEquals(ModelPoolStatus.ACTIVE, modelPools.activatePool(
                new UpdateModelPoolStatusCommand("tenant-1", "owner-1", pool.id())).status());

        var resolution = modelPools.resolvePool("tenant-1", "member-1", pool.id());
        assertEquals(List.of("model-b", "model-a"), resolution.candidates().stream()
                .map(candidate -> candidate.modelId()).toList());
    }

    @Test
    void resolutionSkipsUnhealthyProviderAndHonorsFallbackDisabled() {
        var first = createProvider("first", "model-a");
        var second = createProvider("second", "model-b");
        testSuccess(first.id(), "model-a");
        testSuccess(second.id(), "model-b");
        var pool = modelPools.createPool(new CreateModelPoolCommand(
                "tenant-1", "owner-1", "single-choice",
                ModelPoolVisibility.ORGANIZATION, ModelPoolRoutingStrategy.PRIORITY, false));
        modelPools.addMember(new AddModelPoolMemberCommand(
                "tenant-1", "owner-1", pool.id(), first.id(),
                inference.listModels(first.id()).getFirst().id(), 10, 1));
        modelPools.addMember(new AddModelPoolMemberCommand(
                "tenant-1", "owner-1", pool.id(), second.id(),
                inference.listModels(second.id()).getFirst().id(), 20, 1));
        modelPools.activatePool(new UpdateModelPoolStatusCommand(
                "tenant-1", "owner-1", pool.id()));

        assertEquals(1, modelPools.resolvePool("tenant-1", "member-1", pool.id())
                .candidates().size());
        new ProviderConnectionApplicationService(
                providers,
                value -> new ProviderConnectionProbeResult(
                        false, 8, List.of(), "PROVIDER_AUTH_FAILED"),
                () -> NOW, healthProbes, new UuidGenerator(), new InferenceProperties())
                .testProvider(new TestProviderConnectionCommand(
                        "tenant-1", first.id()));
        assertEquals("model-b", modelPools.resolvePool("tenant-1", "member-1", pool.id())
                .candidates().getFirst().modelId());
    }

    @Test
    void privatePoolIsOwnerOnlyAndReferencedProviderCannotBeDeleted() {
        var provider = createProvider("private-provider", "model-a");
        testSuccess(provider.id(), "model-a");
        var pool = modelPools.createPool(new CreateModelPoolCommand(
                "tenant-1", "owner-1", "private-pool",
                ModelPoolVisibility.PRIVATE, ModelPoolRoutingStrategy.PRIORITY, true));
        var member = modelPools.addMember(new AddModelPoolMemberCommand(
                "tenant-1", "owner-1", pool.id(), provider.id(),
                inference.listModels(provider.id()).getFirst().id(), 1, 1));

        assertThrows(BusinessException.class,
                () -> modelPools.getPool("tenant-1", "other-user", pool.id()));
        BusinessException referenced = assertThrows(BusinessException.class,
                () -> inference.deleteProvider("tenant-1", provider.id()));
        assertEquals(HttpStatus.CONFLICT, referenced.getStatus());
        assertEquals("MODEL_POOL_PROVIDER_REFERENCED", referenced.getCode());

        modelPools.removeMember(new RemoveModelPoolMemberCommand(
                "tenant-1", "owner-1", pool.id(), member.id()));
        inference.deleteProvider("tenant-1", provider.id());
        assertTrue(inference.findProvider(provider.id()).isEmpty());
    }

    @Test
    void executesDeterministicWeightedLatencyAndCostStrategies() {
        var slow = createProvider("slow", "model-slow");
        var fast = createProvider("fast", "model-fast");
        testHealth(slow.id(), "model-slow", 80);
        testHealth(fast.id(), "model-fast", 10);
        String slowModel=inference.listModels(slow.id()).getFirst().id();
        String fastModel=inference.listModels(fast.id()).getFirst().id();
        pricing.createPrice(new ModelPricingApplicationApi.CreatePriceCommand(
                "tenant-1","owner-1",slowModel,100,200,NOW,null));
        pricing.createPrice(new ModelPricingApplicationApi.CreatePriceCommand(
                "tenant-1","owner-1",fastModel,10,20,NOW,null));

        var latency=pool("latency",ModelPoolRoutingStrategy.LATENCY,slow.id(),slowModel,fast.id(),fastModel);
        assertEquals("model-fast",modelPools.resolvePool(
                "tenant-1","member",latency,"route").candidates().getFirst().modelId());
        var cost=pool("cost",ModelPoolRoutingStrategy.COST,slow.id(),slowModel,fast.id(),fastModel);
        assertEquals("model-fast",modelPools.resolvePool(
                "tenant-1","member",cost,"route").candidates().getFirst().modelId());
        var weighted=pool("weighted",ModelPoolRoutingStrategy.WEIGHTED,slow.id(),slowModel,fast.id(),fastModel);
        var first=modelPools.resolvePool("tenant-1","member",weighted,"stable-run");
        var replay=modelPools.resolvePool("tenant-1","member",weighted,"stable-run");
        assertEquals(first.candidates(),replay.candidates());
        assertEquals(first.candidateSnapshotHash(),replay.candidateSnapshotHash());
    }

    private String pool(String name,ModelPoolRoutingStrategy strategy,String p1,String m1,String p2,String m2){var pool=modelPools.createPool(new CreateModelPoolCommand("tenant-1","owner-1",name,ModelPoolVisibility.ORGANIZATION,strategy,true));modelPools.addMember(new AddModelPoolMemberCommand("tenant-1","owner-1",pool.id(),p1,m1,10,1));modelPools.addMember(new AddModelPoolMemberCommand("tenant-1","owner-1",pool.id(),p2,m2,10,3));modelPools.activatePool(new UpdateModelPoolStatusCommand("tenant-1","owner-1",pool.id()));return pool.id();}

    private com.spaceagent.platform.inference.api.ModelProviderView createProvider(
            String name,
            String modelId) {
        return inference.createProvider(new CreateModelProviderCommand(
                "tenant-1", "owner-1", name, "openai-compatible",
                "https://example.com/v1", "secret", "bearer", true, false,
                List.of(new CreateModelProviderCommand.ProviderModelDraft(
                        modelId, modelId, 32768))));
    }

    private void testSuccess(String providerId, String modelId) {
        testHealth(providerId,modelId,10);
    }

    private void testHealth(String providerId,String modelId,int latency) {
        new ProviderConnectionApplicationService(
                providers,
                value -> new ProviderConnectionProbeResult(
                        true, latency, List.of(modelId), null),
                () -> NOW, healthProbes, new UuidGenerator(), new InferenceProperties())
                .testProvider(new TestProviderConnectionCommand(
                        "tenant-1", providerId));
    }

    private static final class TestCipher implements ModelProviderSecretCipher {
        @Override
        public String encrypt(String plaintext) {
            return "encrypted:" + plaintext;
        }

        @Override
        public String decrypt(String encoded) {
            return encoded.substring("encrypted:".length());
        }
    }
}

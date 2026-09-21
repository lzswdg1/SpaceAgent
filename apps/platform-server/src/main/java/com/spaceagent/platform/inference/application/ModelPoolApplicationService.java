package com.spaceagent.platform.inference.application;

import com.spaceagent.platform.inference.api.AddModelPoolMemberCommand;
import com.spaceagent.platform.inference.api.CreateModelPoolCommand;
import com.spaceagent.platform.inference.api.ModelPoolApplicationApi;
import com.spaceagent.platform.inference.api.ModelPoolMemberView;
import com.spaceagent.platform.inference.api.ModelPoolResolutionView;
import com.spaceagent.platform.inference.api.ModelPoolView;
import com.spaceagent.platform.inference.api.RemoveModelPoolMemberCommand;
import com.spaceagent.platform.inference.api.ResolvedModelCandidateView;
import com.spaceagent.platform.inference.api.UpdateModelPoolStatusCommand;
import com.spaceagent.platform.inference.domain.InferenceProviderRepository;
import com.spaceagent.platform.inference.domain.ModelPool;
import com.spaceagent.platform.inference.domain.ModelPoolMember;
import com.spaceagent.platform.inference.domain.ModelPoolRepository;
import com.spaceagent.platform.inference.domain.ModelPoolRoutingStrategy;
import com.spaceagent.platform.inference.domain.ModelPoolStatus;
import com.spaceagent.platform.inference.domain.ModelPoolVisibility;
import com.spaceagent.platform.inference.domain.ModelProvider;
import com.spaceagent.platform.inference.domain.ModelPrice;
import com.spaceagent.platform.inference.domain.ProviderConnectionStatus;
import com.spaceagent.platform.inference.domain.ProviderModel;
import com.spaceagent.shared.exception.BusinessException;
import com.spaceagent.shared.id.IdGenerator;
import com.spaceagent.shared.time.TimeProvider;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Comparator;
import java.util.HexFormat;

/** Application service for durable, deterministic ModelPool membership and resolution. */
@Service
public class ModelPoolApplicationService implements ModelPoolApplicationApi {

    private final ModelPoolRepository poolRepository;
    private final InferenceProviderRepository providerRepository;
    private final IdGenerator idGenerator;
    private final TimeProvider timeProvider;
    private final com.spaceagent.platform.inference.domain.ModelPriceRepository priceRepository;

    public ModelPoolApplicationService(
            ModelPoolRepository poolRepository,
            InferenceProviderRepository providerRepository,
            IdGenerator idGenerator,
            TimeProvider timeProvider,
            com.spaceagent.platform.inference.domain.ModelPriceRepository priceRepository) {
        this.poolRepository = poolRepository;
        this.providerRepository = providerRepository;
        this.idGenerator = idGenerator;
        this.timeProvider = timeProvider;
        this.priceRepository = priceRepository;
    }

    @Override
    @Transactional
    public ModelPoolView createPool(CreateModelPoolCommand command) {
        String tenantId = requireText(command.tenantId(), "tenantId");
        String ownerId = requireText(command.ownerId(), "ownerId");
        String name = normalizeName(command.name());
        if (poolRepository.existsByTenantAndName(tenantId, name)) {
            throw nameConflict();
        }
        Instant now = timeProvider.now();
        ModelPool pool = new ModelPool(
                idGenerator.nextId(), tenantId, ownerId, name,
                command.visibility() == null ? ModelPoolVisibility.PRIVATE : command.visibility(),
                command.routingStrategy() == null
                        ? ModelPoolRoutingStrategy.PRIORITY : command.routingStrategy(),
                command.fallbackEnabled(), ModelPoolStatus.DRAFT, now, now);
        savePool(pool);
        return toView(pool);
    }

    @Override
    @Transactional(readOnly = true)
    public List<ModelPoolView> listPools(String tenantId, String userId) {
        return poolRepository.findPoolsByTenantId(requireText(tenantId, "tenantId")).stream()
                .filter(pool -> canView(pool, userId))
                .map(ModelPoolApplicationService::toView)
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public ModelPoolView getPool(String tenantId, String userId, String poolId) {
        return toView(requireVisiblePool(tenantId, userId, poolId));
    }

    @Override
    @Transactional(readOnly = true)
    public List<ModelPoolMemberView> listMembers(String tenantId, String userId, String poolId) {
        ModelPool pool = requireVisiblePool(tenantId, userId, poolId);
        return poolRepository.findMembersByPoolId(pool.id()).stream()
                .map(this::toMemberView)
                .toList();
    }

    @Override
    @Transactional
    public ModelPoolMemberView addMember(AddModelPoolMemberCommand command) {
        ModelPool pool = requireOwnedPool(command.tenantId(), command.userId(), command.poolId());
        if (pool.status() == ModelPoolStatus.DISABLED) {
            throw new BusinessException(
                    "Disabled ModelPool cannot change membership",
                    HttpStatus.CONFLICT,
                    "MODEL_POOL_DISABLED");
        }
        ModelProvider provider = providerRepository.findProviderByTenantAndId(
                        pool.tenantId(), command.providerId())
                .filter(ModelProvider::enabled)
                .filter(value -> value.connectionStatus() == ProviderConnectionStatus.ACTIVE)
                .orElseThrow(() -> new BusinessException(
                        "Provider must pass connection test before pool membership",
                        HttpStatus.CONFLICT,
                        "MODEL_POOL_PROVIDER_NOT_ACTIVE"));
        ProviderModel model = providerRepository.findModelById(command.providerModelId())
                .filter(value -> provider.id().equals(value.providerId()))
                .orElseThrow(() -> new BusinessException(
                        "Provider model not found",
                        HttpStatus.NOT_FOUND,
                        "PROVIDER_MODEL_NOT_FOUND"));
        if (poolRepository.findMembersByPoolId(pool.id()).stream()
                .anyMatch(member -> model.id().equals(member.providerModelId()))) {
            throw new BusinessException(
                    "Provider model already belongs to ModelPool",
                    HttpStatus.CONFLICT,
                    "MODEL_POOL_MEMBER_CONFLICT");
        }
        int priority = command.priority() == null ? 100 : command.priority();
        int weight = command.weight() == null ? 1 : command.weight();
        if (priority < 0 || priority > 10000 || weight < 1 || weight > 1000) {
            throw invalidInput("ModelPool priority or weight is invalid");
        }
        Instant now = timeProvider.now();
        ModelPoolMember member = new ModelPoolMember(
                idGenerator.nextId(), pool.id(), provider.id(), model.id(),
                priority, weight, true, now, now);
        try {
            poolRepository.saveMember(member);
        } catch (DataIntegrityViolationException exception) {
            throw new BusinessException(
                    "Provider model already belongs to ModelPool",
                    HttpStatus.CONFLICT,
                    "MODEL_POOL_MEMBER_CONFLICT");
        }
        return toMemberView(member);
    }

    @Override
    @Transactional
    public void removeMember(RemoveModelPoolMemberCommand command) {
        ModelPool pool = requireOwnedPool(command.tenantId(), command.userId(), command.poolId());
        requireUuid(command.memberId(), ModelPoolApplicationService::memberNotFound);
        if (!poolRepository.removeMember(pool.id(), command.memberId())) {
            throw memberNotFound();
        }
        if (pool.status() == ModelPoolStatus.ACTIVE && eligibleMembers(pool).isEmpty()) {
            poolRepository.savePool(pool.markDraft(timeProvider.now()));
        }
    }

    @Override
    @Transactional
    public ModelPoolView activatePool(UpdateModelPoolStatusCommand command) {
        ModelPool pool = requireOwnedPool(command.tenantId(), command.userId(), command.poolId());
        if (eligibleMembers(pool).isEmpty()) {
            throw new BusinessException(
                    "ModelPool has no tested active candidates",
                    HttpStatus.CONFLICT,
                    "MODEL_POOL_NO_ACTIVE_CANDIDATE");
        }
        ModelPool activated = pool.activate(timeProvider.now());
        poolRepository.savePool(activated);
        return toView(activated);
    }

    @Override
    @Transactional
    public ModelPoolView disablePool(UpdateModelPoolStatusCommand command) {
        ModelPool pool = requireOwnedPool(command.tenantId(), command.userId(), command.poolId());
        ModelPool disabled = pool.disable(timeProvider.now());
        poolRepository.savePool(disabled);
        return toView(disabled);
    }

    @Override
    @Transactional(readOnly = true)
    public ModelPoolResolutionView resolvePool(String tenantId,String userId,String poolId,String routingKey) {
        ModelPool pool = requireVisiblePool(tenantId, userId, poolId);
        if (pool.status() != ModelPoolStatus.ACTIVE) {
            throw new BusinessException(
                    "ModelPool is not active", HttpStatus.CONFLICT, "MODEL_POOL_NOT_ACTIVE");
        }
        List<EligibleMember> eligible=eligibleMembers(pool);
        if(pool.routingStrategy()==ModelPoolRoutingStrategy.COST&&eligible.stream().anyMatch(v->v.price()==null))throw new BusinessException("Cost routing requires active prices",HttpStatus.CONFLICT,"MODEL_POOL_PRICE_REQUIRED");
        if(pool.routingStrategy()==ModelPoolRoutingStrategy.LATENCY&&eligible.stream().anyMatch(v->v.provider().lastTestLatencyMs()==null))throw new BusinessException("Latency routing requires health latency",HttpStatus.CONFLICT,"MODEL_POOL_LATENCY_REQUIRED");
        List<EligibleMember> ordered=order(pool,eligible,routingKey==null?pool.id():routingKey);
        List<ResolvedModelCandidateView> candidates = ordered.stream()
                .map(candidate -> new ResolvedModelCandidateView(
                        candidate.member().id(), candidate.provider().id(),
                        candidate.model().id(), candidate.model().modelId(),
                        candidate.member().priority(), candidate.member().weight(),
                        candidate.provider().lastTestLatencyMs(),
                        candidate.price()==null?null:candidate.price().id(),
                        candidate.price()==null?null:candidate.price().inputMicrosPerMillionTokens(),
                        candidate.price()==null?null:candidate.price().outputMicrosPerMillionTokens()))
                .toList();
        if (candidates.isEmpty()) {
            throw new BusinessException(
                    "ModelPool has no active candidates",
                    HttpStatus.CONFLICT,
                    "MODEL_POOL_NO_ACTIVE_CANDIDATE");
        }
        return new ModelPoolResolutionView(
                pool.id(), pool.fallbackEnabled() ? candidates : List.of(candidates.getFirst()),
                pool.routingStrategy(),pool.fallbackEnabled(),snapshot(pool,candidates));
    }

    private List<EligibleMember> eligibleMembers(ModelPool pool) {
        List<ModelPoolMember> members = poolRepository.findMembersByPoolId(pool.id()).stream()
                .filter(ModelPoolMember::enabled).toList();
        java.util.Map<String, ModelProvider> providers = providerRepository
                .findProvidersByTenantAndIds(pool.tenantId(), members.stream()
                        .map(ModelPoolMember::providerId).distinct().toList()).stream()
                .collect(java.util.stream.Collectors.toMap(ModelProvider::id, value -> value));
        java.util.Map<String, ProviderModel> models = providerRepository
                .findModelsByIds(members.stream().map(ModelPoolMember::providerModelId)
                        .distinct().toList()).stream()
                .collect(java.util.stream.Collectors.toMap(ProviderModel::id, value -> value));
        java.util.Map<String, ModelPrice> prices = priceRepository
                .findEffectiveByProviderModelIds(models.keySet().stream().toList(), timeProvider.now()).stream()
                .collect(java.util.stream.Collectors.toMap(ModelPrice::providerModelId, value -> value));
        return members.stream().map(member -> {
            ModelProvider provider = providers.get(member.providerId());
            ProviderModel model = models.get(member.providerModelId());
            if (provider == null || model == null || !provider.enabled()
                    || provider.connectionStatus() != ProviderConnectionStatus.ACTIVE
                    || !provider.id().equals(model.providerId())) return null;
            return new EligibleMember(member, provider, model, prices.get(model.id()));
        }).filter(java.util.Objects::nonNull).toList();
    }

    private ModelPool requireVisiblePool(String tenantId, String userId, String poolId) {
        requireUuid(poolId, ModelPoolApplicationService::poolNotFound);
        ModelPool pool = poolRepository.findPoolById(poolId)
                .filter(value -> tenantId.equals(value.tenantId()))
                .orElseThrow(ModelPoolApplicationService::poolNotFound);
        if (!canView(pool, userId)) {
            throw poolNotFound();
        }
        return pool;
    }

    private ModelPool requireOwnedPool(String tenantId, String userId, String poolId) {
        ModelPool pool = requireVisiblePool(tenantId, userId, poolId);
        if (!userId.equals(pool.ownerId())) {
            throw new BusinessException(
                    "ModelPool access denied", HttpStatus.FORBIDDEN, "MODEL_POOL_ACCESS_DENIED");
        }
        return pool;
    }

    private static boolean canView(ModelPool pool, String userId) {
        return pool.visibility() == ModelPoolVisibility.ORGANIZATION
                || userId.equals(pool.ownerId());
    }

    private void savePool(ModelPool pool) {
        try {
            poolRepository.savePool(pool);
        } catch (DataIntegrityViolationException exception) {
            throw nameConflict();
        }
    }

    private ModelPoolMemberView toMemberView(ModelPoolMember member) {
        ProviderModel model = providerRepository.findModelById(member.providerModelId())
                .orElseThrow(ModelPoolApplicationService::memberNotFound);
        return new ModelPoolMemberView(
                member.id(), member.poolId(), member.providerId(), member.providerModelId(),
                model.modelId(), member.priority(), member.weight(), member.enabled(),
                member.createdAt(), member.updatedAt());
    }

    private static ModelPoolView toView(ModelPool pool) {
        return new ModelPoolView(
                pool.id(), pool.tenantId(), pool.ownerId(), pool.name(), pool.visibility(),
                pool.routingStrategy(), pool.fallbackEnabled(), pool.status(),
                pool.createdAt(), pool.updatedAt());
    }

    private static String normalizeName(String value) {
        String name = requireText(value, "name");
        if (name.length() > 120) {
            throw invalidInput("ModelPool name must not exceed 120 characters");
        }
        return name;
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw invalidInput(field + " is required");
        }
        return value.trim();
    }

    private static void requireUuid(
            String value,
            java.util.function.Supplier<BusinessException> failure) {
        try {
            UUID.fromString(value);
        } catch (IllegalArgumentException | NullPointerException exception) {
            throw failure.get();
        }
    }

    private static BusinessException invalidInput(String message) {
        return new BusinessException(message, HttpStatus.BAD_REQUEST, "MODEL_POOL_INPUT_INVALID");
    }

    private static BusinessException poolNotFound() {
        return new BusinessException(
                "ModelPool not found", HttpStatus.NOT_FOUND, "MODEL_POOL_NOT_FOUND");
    }

    private static BusinessException memberNotFound() {
        return new BusinessException(
                "ModelPool member not found", HttpStatus.NOT_FOUND, "MODEL_POOL_MEMBER_NOT_FOUND");
    }

    private static BusinessException nameConflict() {
        return new BusinessException(
                "ModelPool name already exists",
                HttpStatus.CONFLICT,
                "MODEL_POOL_NAME_CONFLICT");
    }

    private record EligibleMember(
            ModelPoolMember member,
            ModelProvider provider,
            ProviderModel model,
            com.spaceagent.platform.inference.domain.ModelPrice price) {
    }

    private static List<EligibleMember> order(ModelPool pool,List<EligibleMember> values,String key){List<EligibleMember> base=new java.util.ArrayList<>(values);Comparator<EligibleMember> stable=Comparator.comparingInt((EligibleMember v)->v.member().priority()).thenComparing(v->v.member().id());switch(pool.routingStrategy()){case PRIORITY->base.sort(stable);case LATENCY->base.sort(Comparator.comparingInt((EligibleMember v)->v.provider().lastTestLatencyMs()).thenComparing(stable));case COST->base.sort(Comparator.comparingLong((EligibleMember v)->Math.addExact(v.price().inputMicrosPerMillionTokens(),v.price().outputMicrosPerMillionTokens())).thenComparing(stable));case WEIGHTED->{base.sort(stable);long total=base.stream().mapToLong(v->v.member().weight()).sum(),point=Math.floorMod(hashLong(key),total);int chosen=0;long cursor=0;for(int i=0;i<base.size();i++){cursor+=base.get(i).member().weight();if(point<cursor){chosen=i;break;}}EligibleMember first=base.remove(chosen);base.addFirst(first);}}return List.copyOf(base);}
    private static String snapshot(ModelPool pool,List<ResolvedModelCandidateView> values){String raw=pool.id()+"|"+pool.routingStrategy()+"|"+values.stream().map(v->v.memberId()+":"+v.providerId()+":"+v.modelId()+":"+v.priceId()).reduce((a,b)->a+"|"+b).orElse("");return "sha256:"+HexFormat.of().formatHex(digest(raw));}
    private static long hashLong(String value){byte[] b=digest(value);return java.nio.ByteBuffer.wrap(b).getLong();}private static byte[] digest(String value){try{return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));}catch(Exception e){throw new IllegalStateException(e);}}
}

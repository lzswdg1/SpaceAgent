package com.spaceagent.platform.inference.infrastructure.memory;

import com.spaceagent.platform.inference.domain.*;
import com.spaceagent.shared.time.TimeProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Repository
@ConditionalOnProperty(prefix = "platform", name = "persistence", havingValue = "memory", matchIfMissing = true)
public class InMemoryProviderHealthProbeRepository implements ProviderHealthProbeRepository {
    private final InferenceProviderRepository providers;
    private final TimeProvider time;
    private final Map<String, Schedule> schedules = new ConcurrentHashMap<>();
    private final Map<String, ProviderHealthObservation> observations = new ConcurrentHashMap<>();

    public InMemoryProviderHealthProbeRepository(
            InferenceProviderRepository providers, TimeProvider time) {
        this.providers = providers;
        this.time = time;
    }

    @Override
    public synchronized void synchronize(ModelProvider provider, Instant now) {
        schedules.compute(provider.id(), (id, current) -> {
            if (!provider.enabled()) return new Schedule(provider.id(), provider.tenantId(), false,
                    now, null, null, null, 0, current == null ? 0 : current.revision() + 1, 0);
            if (current == null || !current.enabled()) {
                return new Schedule(provider.id(), provider.tenantId(), true,
                        now, null, null, null, 0, current == null ? 1 : current.revision() + 1, 0);
            }
            return current;
        });
    }

    @Override
    public synchronized Optional<ProviderHealthProbeClaim> claimDue(
            String owner, long leaseSeconds) {
        Instant now = time.now();
        Schedule selected = schedules.values().stream()
                .filter(Schedule::enabled)
                .filter(value -> !value.nextProbeAt().isAfter(now))
                .filter(value -> value.claimToken() == null || !value.leaseUntil().isAfter(now))
                .sorted(Comparator.comparing(Schedule::nextProbeAt)
                        .thenComparing(Schedule::providerId))
                .findFirst().orElse(null);
        if (selected == null) return Optional.empty();
        Schedule claimed = new Schedule(
                selected.providerId(), selected.tenantId(), true, selected.nextProbeAt(),
                UUID.randomUUID().toString(), owner, now.plusSeconds(leaseSeconds),
                selected.fencingToken() + 1, selected.revision() + 1,
                selected.attemptCount() + 1);
        schedules.put(claimed.providerId(), claimed);
        return Optional.of(view(claimed));
    }

    @Override
    public synchronized boolean complete(Completion completion) {
        Schedule current = schedules.get(completion.claim().providerId());
        Instant now = time.now();
        if (current == null || !Objects.equals(current.claimToken(), completion.claim().claimToken())
                || !Objects.equals(current.claimOwner(), completion.claim().claimOwner())
                || current.fencingToken() != completion.claim().fencingToken()
                || current.revision() != completion.claim().revision()
                || !current.leaseUntil().isAfter(now)) return false;
        ModelProvider provider = providers.findProviderById(current.providerId()).orElse(null);
        if (provider == null || !provider.enabled()) return false;
        providers.saveProvider(provider.recordConnectionTest(
                completion.result(), completion.observedAt()));
        observations.put(completion.observationId(), observation(
                completion.observationId(), provider, "SCHEDULED",
                completion.result(), completion.observedAt()));
        long delay = completion.result().success()
                ? completion.successIntervalSeconds() : completion.retryDelaySeconds();
        schedules.put(current.providerId(), new Schedule(
                current.providerId(), current.tenantId(), true,
                completion.observedAt().plusSeconds(delay), null, null, null,
                current.fencingToken(), current.revision() + 1,
                completion.result().success() ? 0 : current.attemptCount()));
        return true;
    }

    @Override
    public synchronized void recordManual(ManualObservation value) {
        ModelProvider updated = value.provider().recordConnectionTest(
                value.result(), value.observedAt());
        providers.saveProvider(updated);
        synchronize(updated, value.observedAt());
        Schedule current = schedules.get(updated.id());
        schedules.put(updated.id(), new Schedule(
                current.providerId(), current.tenantId(), updated.enabled(),
                value.observedAt().plusSeconds(value.nextProbeSeconds()), null, null, null,
                current.fencingToken(), current.revision() + 1, 0));
        observations.put(value.observationId(), observation(
                value.observationId(), updated, "MANUAL", value.result(), value.observedAt()));
    }

    @Override
    public List<ProviderHealthObservation> findObservations(
            String tenantId, String providerId, int limit) {
        return observations.values().stream()
                .filter(value -> value.tenantId().equals(tenantId)
                        && value.providerId().equals(providerId))
                .sorted(Comparator.comparing(ProviderHealthObservation::observedAt).reversed())
                .limit(Math.max(1, Math.min(limit, 100)))
                .toList();
    }

    private static ProviderHealthProbeClaim view(Schedule value) {
        return new ProviderHealthProbeClaim(
                value.providerId(), value.tenantId(), value.claimToken(), value.claimOwner(),
                value.fencingToken(), value.revision(), value.attemptCount(), value.leaseUntil());
    }

    private static ProviderHealthObservation observation(
            String id, ModelProvider provider, String source,
            ProviderConnectionProbeResult result, Instant at) {
        return new ProviderHealthObservation(
                id, provider.id(), provider.tenantId(), source, result.success(),
                result.success() ? ProviderConnectionStatus.ACTIVE : ProviderConnectionStatus.UNHEALTHY,
                result.latencyMs(), result.discoveredModelIds(),
                result.success() ? null : result.errorCode(), at);
    }

    private record Schedule(
            String providerId,
            String tenantId,
            boolean enabled,
            Instant nextProbeAt,
            String claimToken,
            String claimOwner,
            Instant leaseUntil,
            long fencingToken,
            long revision,
            int attemptCount) {
    }
}

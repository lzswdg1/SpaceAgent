package com.spaceagent.platform.runtime.infrastructure.memory;

import com.spaceagent.platform.runtime.domain.ProjectPlanMergeBarrier;
import com.spaceagent.platform.runtime.domain.ProjectPlanMergeBarrierClaim;
import com.spaceagent.platform.runtime.domain.ProjectPlanMergeBarrierEntry;
import com.spaceagent.platform.runtime.domain.ProjectPlanMergeBarrierRepository;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Repository;

@Repository
@ConditionalOnProperty(prefix = "platform", name = "persistence", havingValue = "memory", matchIfMissing = true)
public class InMemoryProjectPlanMergeBarrierRepository implements ProjectPlanMergeBarrierRepository {
    private final Map<String, ProjectPlanMergeBarrier> barriers = new LinkedHashMap<>();
    private final Map<String, List<ProjectPlanMergeBarrierEntry>> entries = new LinkedHashMap<>();
    private final Map<String, ClaimState> claims = new LinkedHashMap<>();

    @Override
    public synchronized void create(ProjectPlanMergeBarrier barrier, List<ProjectPlanMergeBarrierEntry> values) {
        if (barriers.containsKey(barrier.executionId())) throw new IllegalStateException("merge barrier exists");
        var sorted = values.stream().sorted(Comparator.comparingInt(ProjectPlanMergeBarrierEntry::applyIndex)).toList();
        for (int index = 0; index < sorted.size(); index++) {
            if (sorted.get(index).applyIndex() != index) throw new IllegalArgumentException("merge barrier indices must be contiguous");
        }
        barriers.put(barrier.executionId(), barrier);
        entries.put(barrier.executionId(), sorted);
    }

    @Override
    public synchronized void createIfAbsent(ProjectPlanMergeBarrier barrier) {
        if (!barriers.containsKey(barrier.executionId())) {
            barriers.put(barrier.executionId(), barrier);
            entries.put(barrier.executionId(), List.of());
        }
    }

    @Override
    public synchronized boolean append(ProjectPlanMergeBarrierEntry entry) {
        var barrier = barriers.get(entry.executionId());
        if (barrier == null) return false;
        var values = new ArrayList<>(entries.getOrDefault(entry.executionId(), List.of()));
        var existing = values.stream().filter(value -> value.applyIndex() == entry.applyIndex()).findFirst();
        if (existing.isPresent()) {
            return existing.get().planStepId().equals(entry.planStepId())
                    && existing.get().sourceMergeId().equals(entry.sourceMergeId());
        }
        values.add(entry);
        values.sort(Comparator.comparingInt(ProjectPlanMergeBarrierEntry::applyIndex));
        entries.put(entry.executionId(), List.copyOf(values));
        if (barrier.state() == ProjectPlanMergeBarrier.State.COMPLETED
                && barrier.nextApplyIndex() == entry.applyIndex()) {
            barriers.put(barrier.executionId(), copyBarrier(
                    barrier, barrier.nextApplyIndex(), ProjectPlanMergeBarrier.State.ACTIVE,
                    entry.updatedAt(), null));
        }
        return true;
    }

    @Override
    public synchronized Optional<ProjectPlanMergeBarrier> find(String executionId) {
        return Optional.ofNullable(barriers.get(executionId));
    }

    @Override
    public synchronized Optional<ProjectPlanMergeBarrierEntry> next(String executionId) {
        var barrier = barriers.get(executionId);
        if (barrier == null || barrier.state() != ProjectPlanMergeBarrier.State.ACTIVE) return Optional.empty();
        return entry(executionId, barrier.nextApplyIndex())
                .filter(value -> value.state() == ProjectPlanMergeBarrierEntry.State.READY);
    }

    @Override
    public synchronized boolean advance(ProjectPlanMergeBarrier barrier,
                                        ProjectPlanMergeBarrierEntry entry, Instant now) {
        var current = barriers.get(barrier.executionId());
        var stored = entry(entry.executionId(), entry.applyIndex()).orElse(null);
        if (current == null || stored == null || current.revision() != barrier.revision()
                || current.nextApplyIndex() != entry.applyIndex()
                || stored.revision() != entry.revision()
                || stored.state() != ProjectPlanMergeBarrierEntry.State.READY) return false;
        replaceEntry(applied(stored, now));
        advanceBarrier(current, stored.applyIndex(), now);
        return true;
    }

    @Override
    public synchronized boolean block(ProjectPlanMergeBarrier barrier, Instant now) {
        var current = barriers.get(barrier.executionId());
        if (current == null || current.revision() != barrier.revision()
                || current.state() != ProjectPlanMergeBarrier.State.ACTIVE) return false;
        barriers.put(current.executionId(), copyBarrier(
                current, current.nextApplyIndex(), ProjectPlanMergeBarrier.State.BLOCKED, now, null));
        return true;
    }

    @Override
    public synchronized Optional<ProjectPlanMergeBarrierClaim> claimNext(
            String owner, String token, Instant now, Instant leaseUntil, int maximumAttempts) {
        return barriers.values().stream()
                .filter(value -> value.state() == ProjectPlanMergeBarrier.State.ACTIVE)
                .sorted(Comparator.comparing(ProjectPlanMergeBarrier::createdAt)
                        .thenComparing(ProjectPlanMergeBarrier::executionId))
                .map(barrier -> claim(barrier, owner, token, now, leaseUntil, maximumAttempts))
                .flatMap(Optional::stream)
                .findFirst();
    }

    private Optional<ProjectPlanMergeBarrierClaim> claim(
            ProjectPlanMergeBarrier barrier, String owner, String token,
            Instant now, Instant leaseUntil, int maximumAttempts) {
        var entry = entry(barrier.executionId(), barrier.nextApplyIndex()).orElse(null);
        if (entry == null || entry.state() != ProjectPlanMergeBarrierEntry.State.READY) return Optional.empty();
        var previous = claims.getOrDefault(key(entry), ClaimState.empty());
        if (previous.attempt() >= maximumAttempts
                || previous.claimToken() != null && previous.leaseUntil().isAfter(now)) return Optional.empty();
        var claimedEntry = copyEntry(entry, entry.state(), entry.revision() + 1, now, null);
        replaceEntry(claimedEntry);
        var claimed = new ClaimState(previous.attempt() + 1, owner, token,
                previous.fencingToken() + 1, leaseUntil);
        claims.put(key(entry), claimed);
        return Optional.of(toClaim(barrier, claimedEntry, claimed));
    }

    @Override
    public synchronized boolean heartbeat(ProjectPlanMergeBarrierClaim claim, Instant now, Instant leaseUntil) {
        var state = validClaim(claim, now).orElse(null);
        if (state == null) return false;
        claims.put(key(claim.executionId(), claim.applyIndex()), new ClaimState(
                state.attempt(), state.claimOwner(), state.claimToken(), state.fencingToken(), leaseUntil));
        return true;
    }

    @Override
    public synchronized boolean releaseKnownNoEffect(ProjectPlanMergeBarrierClaim claim, Instant now) {
        var state = validClaim(claim, now).orElse(null);
        if (state == null) return false;
        var entry = entry(claim.executionId(), claim.applyIndex()).orElseThrow();
        replaceEntry(copyEntry(entry, entry.state(), entry.revision() + 1, now, null));
        claims.put(key(entry), state.released());
        return true;
    }

    @Override
    public synchronized boolean completeApplied(ProjectPlanMergeBarrierClaim claim, Instant now) {
        var state = validClaim(claim, now).orElse(null);
        var barrier = barriers.get(claim.executionId());
        if (state == null || barrier == null
                || barrier.nextApplyIndex() != claim.applyIndex()) return false;
        var entry = entry(claim.executionId(), claim.applyIndex()).orElseThrow();
        replaceEntry(applied(entry, now));
        claims.put(key(entry), state.released());
        advanceBarrier(barrier, entry.applyIndex(), now);
        return true;
    }

    @Override
    public synchronized boolean blockClaim(
            ProjectPlanMergeBarrierClaim claim, ProjectPlanMergeBarrierEntry.State state,
            String safeErrorCode, Instant now) {
        if (state != ProjectPlanMergeBarrierEntry.State.BLOCKED
                && state != ProjectPlanMergeBarrierEntry.State.UNKNOWN) return false;
        var claimState = validClaim(claim, now).orElse(null);
        var barrier = barriers.get(claim.executionId());
        if (claimState == null || barrier == null
                || barrier.nextApplyIndex() != claim.applyIndex()) return false;
        var entry = entry(claim.executionId(), claim.applyIndex()).orElseThrow();
        replaceEntry(copyEntry(entry, state, entry.revision() + 1, now, now));
        claims.put(key(entry), claimState.released());
        barriers.put(barrier.executionId(), copyBarrier(
                barrier, barrier.nextApplyIndex(), ProjectPlanMergeBarrier.State.BLOCKED, now, null));
        return true;
    }

    @Override
    public synchronized int blockExhausted(int maximumAttempts, Instant now) {
        int blocked = 0;
        for (var barrier : List.copyOf(barriers.values())) {
            var entry = entry(barrier.executionId(), barrier.nextApplyIndex()).orElse(null);
            if (entry == null || entry.state() != ProjectPlanMergeBarrierEntry.State.READY) continue;
            var claim = claims.getOrDefault(key(entry), ClaimState.empty());
            if (claim.attempt() < maximumAttempts
                    || claim.claimToken() != null && claim.leaseUntil().isAfter(now)) continue;
            replaceEntry(copyEntry(entry, ProjectPlanMergeBarrierEntry.State.UNKNOWN,
                    entry.revision() + 1, now, now));
            claims.put(key(entry), claim.released());
            barriers.put(barrier.executionId(), copyBarrier(
                    barrier, barrier.nextApplyIndex(), ProjectPlanMergeBarrier.State.BLOCKED, now, null));
            blocked++;
        }
        return blocked;
    }

    private Optional<ClaimState> validClaim(ProjectPlanMergeBarrierClaim claim, Instant now) {
        var state = claims.get(key(claim.executionId(), claim.applyIndex()));
        if (state == null || state.claimToken() == null || !state.leaseUntil().isAfter(now)
                || !state.claimOwner().equals(claim.claimOwner())
                || !state.claimToken().equals(claim.claimToken())
                || state.fencingToken() != claim.fencingToken()) return Optional.empty();
        return Optional.of(state);
    }

    private Optional<ProjectPlanMergeBarrierEntry> entry(String executionId, int applyIndex) {
        return entries.getOrDefault(executionId, List.of()).stream()
                .filter(value -> value.applyIndex() == applyIndex).findFirst();
    }

    private void replaceEntry(ProjectPlanMergeBarrierEntry replacement) {
        var values = new ArrayList<>(entries.get(replacement.executionId()));
        for (int index = 0; index < values.size(); index++) {
            if (values.get(index).applyIndex() == replacement.applyIndex()) {
                values.set(index, replacement);
                entries.put(replacement.executionId(), List.copyOf(values));
                return;
            }
        }
        throw new IllegalStateException("merge barrier entry missing");
    }

    private void advanceBarrier(ProjectPlanMergeBarrier barrier, int applyIndex, Instant now) {
        int next = applyIndex + 1;
        boolean complete = entry(barrier.executionId(), next).isEmpty();
        barriers.put(barrier.executionId(), copyBarrier(
                barrier, next, complete ? ProjectPlanMergeBarrier.State.COMPLETED
                        : ProjectPlanMergeBarrier.State.ACTIVE,
                now, complete ? now : null));
    }

    private static ProjectPlanMergeBarrierClaim toClaim(
            ProjectPlanMergeBarrier barrier, ProjectPlanMergeBarrierEntry entry, ClaimState state) {
        return new ProjectPlanMergeBarrierClaim(barrier.executionId(), barrier.tenantId(),
                barrier.ownerId(), barrier.projectId(), barrier.taskPlanId(), entry.applyIndex(),
                entry.planStepId(), entry.sourceMergeId(), barrier.revision(),
                state.attempt(), state.claimOwner(),
                state.claimToken(), state.fencingToken(), state.leaseUntil());
    }

    private static ProjectPlanMergeBarrier copyBarrier(
            ProjectPlanMergeBarrier value, int next, ProjectPlanMergeBarrier.State state,
            Instant updatedAt, Instant completedAt) {
        return new ProjectPlanMergeBarrier(value.executionId(), value.tenantId(), value.ownerId(),
                value.projectId(), value.taskPlanId(), next, state, value.revision() + 1,
                value.createdAt(), updatedAt, completedAt);
    }

    private static ProjectPlanMergeBarrierEntry copyEntry(
            ProjectPlanMergeBarrierEntry value, ProjectPlanMergeBarrierEntry.State state,
            long revision, Instant updatedAt, Instant completedAt) {
        return new ProjectPlanMergeBarrierEntry(value.executionId(), value.applyIndex(),
                value.planStepId(), value.sourceMergeId(), state, revision,
                value.createdAt(), updatedAt, completedAt);
    }

    private static ProjectPlanMergeBarrierEntry applied(ProjectPlanMergeBarrierEntry value, Instant now) {
        return copyEntry(value, ProjectPlanMergeBarrierEntry.State.APPLIED,
                value.revision() + 1, now, now);
    }

    private static String key(ProjectPlanMergeBarrierEntry entry) {
        return key(entry.executionId(), entry.applyIndex());
    }

    private static String key(String executionId, int applyIndex) {
        return executionId + ":" + applyIndex;
    }

    private record ClaimState(int attempt, String claimOwner, String claimToken,
                              long fencingToken, Instant leaseUntil) {
        private static ClaimState empty() { return new ClaimState(0, null, null, 0, null); }
        private ClaimState released() { return new ClaimState(attempt, null, null, fencingToken, null); }
    }
}

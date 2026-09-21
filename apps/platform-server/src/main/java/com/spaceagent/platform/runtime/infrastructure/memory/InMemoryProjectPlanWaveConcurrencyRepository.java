package com.spaceagent.platform.runtime.infrastructure.memory;

import com.spaceagent.platform.runtime.domain.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

@Repository
@ConditionalOnProperty(prefix = "platform", name = "persistence", havingValue = "memory", matchIfMissing = true)
public class InMemoryProjectPlanWaveConcurrencyRepository implements ProjectPlanWaveConcurrencyRepository {
    private final Map<String, ProjectPlanWaveConcurrency> budgets = new LinkedHashMap<>();
    private final Map<String, ProjectPlanWaveClaim> claims = new LinkedHashMap<>();
    @Override public synchronized void insert(ProjectPlanWaveConcurrency value) {
        if (budgets.putIfAbsent(value.executionId(), value) != null) throw new IllegalStateException("wave budget exists");
    }
    @Override public synchronized Optional<ProjectPlanWaveConcurrency> findByExecutionId(String executionId) { return Optional.ofNullable(budgets.get(executionId)); }
    @Override public synchronized Optional<ProjectPlanWaveClaim> claim(String executionId, String tenant, String owner,
            String plan, String step, String worker, String token, Instant now, Instant lease) {
        var budget = budgets.get(executionId); if (budget == null || !budget.tenantId().equals(tenant) || !budget.ownerId().equals(owner) || !budget.taskPlanId().equals(plan)) return Optional.empty();
        String key = executionId + ":" + step; var prior = claims.get(key);
        int active = budget.activeClaims();
        if (prior != null && prior.state() == ProjectPlanWaveClaim.State.CLAIMED && !prior.leaseUntil().isAfter(now)) { active--; prior = released(prior, now); claims.put(key, prior); }
        if (prior != null && prior.state() == ProjectPlanWaveClaim.State.CLAIMED || active >= budget.maxParallelism()) return Optional.empty();
        var claim = new ProjectPlanWaveClaim(executionId, plan, step, tenant, owner, worker, token,
                prior == null ? 1 : prior.fencingToken() + 1, lease, ProjectPlanWaveClaim.State.CLAIMED,
                prior == null ? 1 : prior.revision() + 1, prior == null ? now : prior.createdAt(), now, null, null);
        claims.put(key, claim); budgets.put(executionId, new ProjectPlanWaveConcurrency(executionId, tenant, owner,
                budget.projectId(), plan, budget.maxParallelism(), active + 1, budget.revision() + 1, budget.createdAt(), now));
        return Optional.of(claim);
    }
    @Override public synchronized boolean release(ProjectPlanWaveClaim claim, Instant at) {
        String key = claim.executionId() + ":" + claim.planStepId(); var current = claims.get(key); var budget = budgets.get(claim.executionId());
        if (current == null || budget == null || current.state() != ProjectPlanWaveClaim.State.CLAIMED || !current.claimToken().equals(claim.claimToken()) || current.fencingToken() != claim.fencingToken()) return false;
        claims.put(key, released(current, at)); budgets.put(budget.executionId(), new ProjectPlanWaveConcurrency(budget.executionId(), budget.tenantId(), budget.ownerId(), budget.projectId(), budget.taskPlanId(), budget.maxParallelism(), budget.activeClaims() - 1, budget.revision() + 1, budget.createdAt(), at)); return true;
    }
    @Override public synchronized boolean bindJob(ProjectPlanWaveClaim claim,String jobId,Instant at){String key=claim.executionId()+":"+claim.planStepId();var current=claims.get(key);if(current==null||current.state()!=ProjectPlanWaveClaim.State.CLAIMED||!current.claimToken().equals(claim.claimToken())||current.fencingToken()!=claim.fencingToken()||current.jobId()!=null&&!current.jobId().equals(jobId))return false;claims.put(key,new ProjectPlanWaveClaim(current.executionId(),current.taskPlanId(),current.planStepId(),current.tenantId(),current.ownerId(),current.claimOwner(),current.claimToken(),current.fencingToken(),current.leaseUntil(),current.state(),current.revision()+1,current.createdAt(),at,null,jobId));return true;}
    @Override public synchronized boolean synchronizeWithJob(String executionId,String stepId,String jobId,String owner,String token,long fence,Instant lease,Instant at){String key=executionId+":"+stepId;var current=claims.get(key);if(current==null||current.state()!=ProjectPlanWaveClaim.State.CLAIMED||current.jobId()!=null&&!current.jobId().equals(jobId))return false;claims.put(key,new ProjectPlanWaveClaim(current.executionId(),current.taskPlanId(),current.planStepId(),current.tenantId(),current.ownerId(),owner,token,fence,lease,current.state(),current.revision()+1,current.createdAt(),at,null,jobId));return true;}
    @Override public synchronized boolean releaseBound(String executionId,String stepId,String jobId,String token,long fence,Instant at){String key=executionId+":"+stepId;var current=claims.get(key);var budget=budgets.get(executionId);if(current==null||budget==null||current.state()!=ProjectPlanWaveClaim.State.CLAIMED||!java.util.Objects.equals(current.jobId(),jobId)||!current.claimToken().equals(token)||current.fencingToken()!=fence)return false;claims.put(key,released(current,at));budgets.put(executionId,new ProjectPlanWaveConcurrency(executionId,budget.tenantId(),budget.ownerId(),budget.projectId(),budget.taskPlanId(),budget.maxParallelism(),budget.activeClaims()-1,budget.revision()+1,budget.createdAt(),at));return true;}
    private static ProjectPlanWaveClaim released(ProjectPlanWaveClaim v, Instant at) { return new ProjectPlanWaveClaim(v.executionId(), v.taskPlanId(), v.planStepId(), v.tenantId(), v.ownerId(), v.claimOwner(), v.claimToken(), v.fencingToken(), v.leaseUntil(), ProjectPlanWaveClaim.State.RELEASED, v.revision() + 1, v.createdAt(), at, at, v.jobId()); }
}

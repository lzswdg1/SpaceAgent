package com.spaceagent.platform.runtime;

import com.spaceagent.platform.runtime.domain.ProjectPlanWaveConcurrency;
import com.spaceagent.platform.runtime.infrastructure.memory.InMemoryProjectPlanWaveConcurrencyRepository;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class ProjectPlanWaveConcurrencyRepositoryTest {
    @Test void bindsAuthoritativeJobLeaseAndRejectsStaleRelease() {
        Instant now = Instant.parse("2026-09-08T00:00:00Z");
        var repository = new InMemoryProjectPlanWaveConcurrencyRepository();
        repository.insert(new ProjectPlanWaveConcurrency("execution", "tenant", "owner", "project", "plan", 1, 0, 1, now, now));
        var first = repository.claim("execution", "tenant", "owner", "plan", "step-a", "dispatch", "dispatch-token", now, now.plusSeconds(300));
        assertThat(first).isPresent();
        assertThat(repository.bindJob(first.orElseThrow(), "job-a", now.plusSeconds(1))).isTrue();
        assertThat(repository.synchronizeWithJob("execution", "step-a", "job-a",
                "worker-a", "job-token-a", 7, now.plusSeconds(900), now.plusSeconds(2))).isTrue();
        assertThat(repository.claim("execution", "tenant", "owner", "plan", "step-b",
                "worker-b", "token-b", now.plusSeconds(301), now.plusSeconds(601))).isEmpty();
        assertThat(repository.releaseBound("execution", "step-a", "job-a",
                "job-token-a", 6, now.plusSeconds(302))).isFalse();
        assertThat(repository.releaseBound("execution", "step-a", "job-a",
                "job-token-a", 7, now.plusSeconds(302))).isTrue();
        var second = repository.claim("execution", "tenant", "owner", "plan", "step-b", "worker-b", "token-b", now.plusSeconds(303), now.plusSeconds(304));
        assertThat(second).isPresent();
        var reclaimed = repository.claim("execution", "tenant", "owner", "plan", "step-b", "worker-c", "token-c", now.plusSeconds(305), now.plusSeconds(600));
        assertThat(reclaimed).isPresent();
        assertThat(reclaimed.orElseThrow().fencingToken()).isGreaterThan(second.orElseThrow().fencingToken());
        assertThat(repository.release(second.orElseThrow(), now.plusSeconds(306))).isFalse();
    }
}

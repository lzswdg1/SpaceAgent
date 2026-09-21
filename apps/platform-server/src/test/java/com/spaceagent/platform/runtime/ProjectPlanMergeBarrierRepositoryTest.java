package com.spaceagent.platform.runtime;

import com.spaceagent.platform.runtime.domain.*;
import com.spaceagent.platform.runtime.infrastructure.memory.InMemoryProjectPlanMergeBarrierRepository;
import org.junit.jupiter.api.Test;
import java.time.Instant; import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;

class ProjectPlanMergeBarrierRepositoryTest {
    @Test void advancesStableCursorAndRetainsAppliedEvidenceAcrossRestart() {
        Instant now=Instant.EPOCH; var repo=new InMemoryProjectPlanMergeBarrierRepository();
        var barrier=new ProjectPlanMergeBarrier("execution","tenant","owner","project","plan",0,ProjectPlanMergeBarrier.State.ACTIVE,1,now,now,null);
        var first=new ProjectPlanMergeBarrierEntry("execution",0,"step-a","merge-a",ProjectPlanMergeBarrierEntry.State.READY,1,now,now,null);
        var second=new ProjectPlanMergeBarrierEntry("execution",1,"step-b","merge-b",ProjectPlanMergeBarrierEntry.State.READY,1,now,now,null);
        repo.create(barrier,List.of(first,second));
        assertThat(repo.next("execution")).contains(first);
        assertThat(repo.advance(barrier,first,now.plusSeconds(1))).isTrue();
        var restarted=repo.find("execution").orElseThrow();
        assertThat(restarted.nextApplyIndex()).isEqualTo(1);
        assertThat(repo.next("execution")).contains(second);
        assertThat(repo.advance(restarted,second,now.plusSeconds(2))).isTrue();
        assertThat(repo.find("execution").orElseThrow().state()).isEqualTo(ProjectPlanMergeBarrier.State.COMPLETED);
        assertThat(repo.next("execution")).isEmpty();
    }
}

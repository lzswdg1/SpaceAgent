package com.spaceagent.platform.inference.api;

import java.util.List;
import com.spaceagent.platform.inference.domain.ModelPoolRoutingStrategy;

public record ModelPoolResolutionView(
        String poolId,
        List<ResolvedModelCandidateView> candidates,
        ModelPoolRoutingStrategy routingStrategy,
        boolean fallbackEnabled,
        String candidateSnapshotHash) {

    public ModelPoolResolutionView {
        candidates = List.copyOf(candidates);
    }
    public ModelPoolResolutionView(String poolId,List<ResolvedModelCandidateView> candidates){
        this(poolId,candidates,ModelPoolRoutingStrategy.PRIORITY,candidates.size()>1,null);}
}

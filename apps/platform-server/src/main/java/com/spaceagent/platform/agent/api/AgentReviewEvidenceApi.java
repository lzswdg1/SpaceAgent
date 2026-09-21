package com.spaceagent.platform.agent.api;
import java.time.Instant;
import java.util.List;
public interface AgentReviewEvidenceApi {
    Page reviews(String userId,String tenantId,int page,int pageSize);
    record Review(String id,String agentId,String tenantId,String agentOwnerId,String requestedBy,String closedBy,String state,
                  long baseAgentRevision,Long appliedAgentRevision,long revision,Instant createdAt,Instant closedAt){}
    record Page(List<Review> items,long total){public Page{items=List.copyOf(items);}}
}

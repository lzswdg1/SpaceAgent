package com.spaceagent.platform.knowledge.api;

import com.spaceagent.platform.knowledge.domain.KnowledgeUrlJob;
import java.time.Instant;
import java.util.List;

public interface KnowledgeUrlIngestionApplicationApi {
    UrlJobView create(CreateUrlJobCommand command);
    UrlJobView pause(LifecycleCommand command);
    UrlJobView resume(LifecycleCommand command);
    UrlJobView archive(LifecycleCommand command);
    UrlJobView get(GetQuery query);
    UrlJobPage listByDocument(ListByDocumentQuery query);
    record CreateUrlJobCommand(String tenantId,String ownerId,String knowledgeDocumentId,String url,
            KnowledgeUrlJob.RefreshPolicy refreshPolicy,String idempotencyKey) {}
    record LifecycleCommand(String tenantId,String ownerId,String urlJobId,long expectedRevision) {}
    record GetQuery(String tenantId,String ownerId,String urlJobId) {}
    record ListByDocumentQuery(String tenantId,String ownerId,String knowledgeDocumentId,int page,int pageSize) {}
    record UrlJobPage(List<UrlJobView> items,int page,int pageSize,long total) {
        public UrlJobPage { items = List.copyOf(items); }
    }
    record UrlJobView(String id,String knowledgeDocumentId,String normalizedUrl,String origin,
            KnowledgeUrlJob.RefreshPolicy refreshPolicy,String state,long revision,Instant nextRefreshAt,
            Instant createdAt,Instant updatedAt,Instant archivedAt) {}
}

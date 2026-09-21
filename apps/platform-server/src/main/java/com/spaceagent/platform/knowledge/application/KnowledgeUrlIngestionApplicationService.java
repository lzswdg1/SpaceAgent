package com.spaceagent.platform.knowledge.application;

import com.spaceagent.platform.knowledge.api.KnowledgeUrlIngestionApplicationApi;
import com.spaceagent.platform.knowledge.domain.*;
import com.spaceagent.shared.exception.BusinessException;
import com.spaceagent.shared.id.IdGenerator;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import java.time.Instant;

@Service
public class KnowledgeUrlIngestionApplicationService implements KnowledgeUrlIngestionApplicationApi {
    private final KnowledgeUrlRepository urls;private final KnowledgeDocumentRepository documents;private final IdGenerator ids;
    public KnowledgeUrlIngestionApplicationService(KnowledgeUrlRepository urls,KnowledgeDocumentRepository documents,IdGenerator ids){this.urls=urls;this.documents=documents;this.ids=ids;}
    @Override public UrlJobView create(CreateUrlJobCommand c){requireDocument(c.ownerId(),c.knowledgeDocumentId());String normalized=KnowledgeUrlJob.normalize(c.url());var existing=urls.findJob(c.tenantId(),c.ownerId(),c.knowledgeDocumentId(),normalized);if(existing.isPresent()){var value=existing.get();if(!value.refreshPolicy().equals(c.refreshPolicy()))throw conflict("Knowledge URL idempotency conflict","KNOWLEDGE_URL_IDEMPOTENCY_CONFLICT");return view(value);}Instant now=urls.currentTime();var value=new KnowledgeUrlJob(ids.nextId(),c.tenantId(),c.ownerId(),c.knowledgeDocumentId(),normalized,KnowledgeUrlJob.origin(normalized),c.refreshPolicy(),KnowledgeUrlJob.State.ACTIVE,1,now,now,now,null);urls.insertJob(value);return view(value);}
    @Override public UrlJobView pause(LifecycleCommand c){return lifecycle(c,KnowledgeUrlJob.State.PAUSED);}
    @Override public UrlJobView resume(LifecycleCommand c){return lifecycle(c,KnowledgeUrlJob.State.ACTIVE);}
    @Override public UrlJobView archive(LifecycleCommand c){return lifecycle(c,KnowledgeUrlJob.State.ARCHIVED);}
    @Override public UrlJobView get(GetQuery q){return view(require(q.tenantId(),q.ownerId(),q.urlJobId()));}
    @Override public UrlJobPage listByDocument(ListByDocumentQuery q){
        if(q.page()<1||q.page()>10000||q.pageSize()<1||q.pageSize()>100)throw new BusinessException("Invalid Knowledge URL page",HttpStatus.BAD_REQUEST,"KNOWLEDGE_URL_PAGE_INVALID");
        requireDocument(q.ownerId(),q.knowledgeDocumentId());
        int offset=(q.page()-1)*q.pageSize();
        var items=urls.findJobsByDocument(q.tenantId(),q.ownerId(),q.knowledgeDocumentId(),offset,q.pageSize()).stream().map(KnowledgeUrlIngestionApplicationService::view).toList();
        return new UrlJobPage(items,q.page(),q.pageSize(),urls.countJobsByDocument(q.tenantId(),q.ownerId(),q.knowledgeDocumentId()));
    }
    private UrlJobView lifecycle(LifecycleCommand c,KnowledgeUrlJob.State state){KnowledgeUrlJob current=require(c.tenantId(),c.ownerId(),c.urlJobId());if(current.revision()!=c.expectedRevision())throw conflict("Knowledge URL revision conflict","KNOWLEDGE_URL_REVISION_CONFLICT");if(current.state()==KnowledgeUrlJob.State.ARCHIVED&&state!=KnowledgeUrlJob.State.ARCHIVED)throw conflict("Archived Knowledge URL cannot resume","KNOWLEDGE_URL_STATE_CONFLICT");Instant now=urls.currentTime();Instant next=state==KnowledgeUrlJob.State.ACTIVE?now:null;Instant archived=state==KnowledgeUrlJob.State.ARCHIVED?now:null;var changed=new KnowledgeUrlJob(current.id(),current.tenantId(),current.ownerId(),current.knowledgeDocumentId(),current.normalizedUrl(),current.origin(),current.refreshPolicy(),state,current.revision()+1,next,current.createdAt(),now,archived);return view(urls.updateJob(changed,current.revision()).orElseThrow(()->conflict("Knowledge URL revision conflict","KNOWLEDGE_URL_REVISION_CONFLICT")));}
    private KnowledgeUrlJob require(String t,String o,String id){return urls.findJob(t,o,id).orElseThrow(()->new BusinessException("Knowledge URL not found",HttpStatus.NOT_FOUND,"KNOWLEDGE_URL_NOT_FOUND"));}
    private void requireDocument(String owner,String id){if(documents.findById(id).filter(value->java.util.Objects.equals(value.ownerId(),owner)).isEmpty())throw new BusinessException("Knowledge document not found",HttpStatus.NOT_FOUND,"KNOWLEDGE_DOCUMENT_NOT_FOUND");}
    private static UrlJobView view(KnowledgeUrlJob v){return new UrlJobView(v.id(),v.knowledgeDocumentId(),v.normalizedUrl(),v.origin(),v.refreshPolicy(),v.state().name(),v.revision(),v.nextRefreshAt(),v.createdAt(),v.updatedAt(),v.archivedAt());}
    private static BusinessException conflict(String m,String c){return new BusinessException(m,HttpStatus.CONFLICT,c);}
}

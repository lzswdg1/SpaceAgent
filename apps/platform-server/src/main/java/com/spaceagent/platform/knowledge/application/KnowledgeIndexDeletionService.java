package com.spaceagent.platform.knowledge.application;

import com.spaceagent.platform.knowledge.api.*;
import com.spaceagent.platform.knowledge.domain.*;
import com.spaceagent.shared.exception.BusinessException;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

@Service @ConditionalOnProperty(prefix="platform",name="persistence",havingValue="postgres")
public class KnowledgeIndexDeletionService implements KnowledgeIndexDeletionApplicationApi {
    private final KnowledgeAccessApplicationApi access;private final KnowledgeIndexDeletionRepository deletion;
    public KnowledgeIndexDeletionService(KnowledgeAccessApplicationApi access,KnowledgeIndexDeletionRepository deletion){this.access=access;this.deletion=deletion;}
    public View delete(KnowledgeBaseApplicationApi.Actor actor,String base,String doc,long revision){
        access.authorize(actor,base,KnowledgeBase.Permission.MANAGE);
        if(deletion.view(base,doc).isEmpty())throw missing();
        try{return view(deletion.request(base,doc,revision));}catch(IllegalStateException e){throw new BusinessException("Document revision changed",HttpStatus.CONFLICT,"KNOWLEDGE_DELETE_CONFLICT");}
    }
    public View status(KnowledgeBaseApplicationApi.Actor actor,String base,String doc){access.authorize(actor,base,KnowledgeBase.Permission.READ);return view(deletion.view(base,doc).orElseThrow(KnowledgeIndexDeletionService::missing));}
    private static View view(KnowledgeIndexDeletionRepository.View v){return new View(v.documentId(),v.state(),v.pendingGenerations());}
    private static BusinessException missing(){return new BusinessException("Indexed document not found",HttpStatus.NOT_FOUND,"KNOWLEDGE_DOCUMENT_NOT_FOUND");}
}

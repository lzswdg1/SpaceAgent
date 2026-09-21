package com.spaceagent.platform.knowledge.application;
import com.spaceagent.platform.knowledge.api.*;
import com.spaceagent.platform.knowledge.domain.*;
import com.spaceagent.shared.exception.BusinessException;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import java.util.List;

@Service @ConditionalOnProperty(prefix="platform",name="persistence",havingValue="postgres")
public class KnowledgeProcessingApplicationService implements KnowledgeProcessingApplicationApi {
    private final KnowledgeAccessApplicationApi access;private final KnowledgeProcessingPolicyRepository policies;private final KnowledgeDocumentChunker chunks;private final KnowledgeBaseRepository bases;
    public KnowledgeProcessingApplicationService(KnowledgeAccessApplicationApi access,KnowledgeProcessingPolicyRepository policies,KnowledgeDocumentChunker chunks,KnowledgeBaseRepository bases){this.access=access;this.policies=policies;this.chunks=chunks;this.bases=bases;}
    public PolicyView policy(KnowledgeBaseApplicationApi.Actor a,String b){access.authorize(a,b,KnowledgeBase.Permission.READ);var p=policies.get(b);return new PolicyView(p.revision(),p.policy());}
    @org.springframework.transaction.annotation.Transactional
    public PolicyView configure(KnowledgeBaseApplicationApi.Actor a,String b,long r,KnowledgeProcessingPolicy p){bases.lock(b);access.authorize(a,b,KnowledgeBase.Permission.MANAGE);try{var saved=policies.save(b,r,p);return new PolicyView(saved.revision(),saved.policy());}catch(IllegalStateException e){throw new BusinessException("Processing policy changed",HttpStatus.CONFLICT,"KNOWLEDGE_POLICY_CONFLICT");}}
    public List<KnowledgeDocumentChunker.Segment> preview(KnowledgeBaseApplicationApi.Actor a,String b,String text,KnowledgeProcessingPolicy p){access.authorize(a,b,KnowledgeBase.Permission.WRITE);try{if(text==null||text.length()>64000||p==null)throw new IllegalArgumentException();var result=chunks.split(text,List.of(),p);if(result.size()>256)throw new IllegalArgumentException();return result;}catch(IllegalArgumentException e){throw new BusinessException("Preview exceeds limits or policy is invalid",HttpStatus.BAD_REQUEST,"KNOWLEDGE_PREVIEW_INVALID");}}
}

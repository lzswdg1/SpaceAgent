package com.spaceagent.platform.knowledge.application;

import com.spaceagent.platform.knowledge.api.*;
import com.spaceagent.platform.knowledge.domain.*;
import com.spaceagent.platform.inference.api.EmbeddingBatchApplicationApi;
import com.spaceagent.shared.exception.BusinessException;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Bounded scoped recall. PostgreSQL supplies all returned content and final authority. */
@Service @ConditionalOnProperty(prefix="platform",name="persistence",havingValue="postgres")
public class KnowledgeCollectionRetrievalService implements KnowledgeCollectionRetrievalApi {
    private final KnowledgeAccessApplicationApi access;
    private final KnowledgeRetrievalIndexRepository repository;
    private final KnowledgeIndexCatalogRepository catalog;
    private final ObjectProvider<VectorIndexGateway> vectors;
    private final EmbeddingBatchApplicationApi embeddings;
    private final KnowledgeDocumentChunker tokenizer;
    private final com.spaceagent.platform.identity.api.IdentityActivityApplicationApi activity;
    private final com.spaceagent.platform.identity.api.IdentityApplicationApi identity;
    @org.springframework.beans.factory.annotation.Autowired(required=false)
    private com.spaceagent.platform.inference.api.RerankApplicationApi reranker;
    @org.springframework.beans.factory.annotation.Autowired(required=false) private KnowledgeOperationalRepository operations;
    @org.springframework.beans.factory.annotation.Autowired(required=false) private io.micrometer.core.instrument.MeterRegistry metrics;
    public KnowledgeCollectionRetrievalService(KnowledgeAccessApplicationApi access,KnowledgeRetrievalIndexRepository repository,
            KnowledgeIndexCatalogRepository catalog,ObjectProvider<VectorIndexGateway> vectors,EmbeddingBatchApplicationApi embeddings,
            KnowledgeDocumentChunker tokenizer,com.spaceagent.platform.identity.api.IdentityActivityApplicationApi activity,
            com.spaceagent.platform.identity.api.IdentityApplicationApi identity){this.access=access;this.repository=repository;this.catalog=catalog;this.vectors=vectors;this.embeddings=embeddings;this.tokenizer=tokenizer;this.activity=activity;this.identity=identity;}
    @Override public Result retrieve(Query q){
        validate(q);currentActor(q);
        String lease=operations==null?null:operations.acquireQuery(q.actor().organizationId(),8).orElseThrow(()->new BusinessException(
            "Knowledge query concurrency limit reached",HttpStatus.TOO_MANY_REQUESTS,"KNOWLEDGE_QUERY_CAPACITY_LIMIT"));
        long start=System.nanoTime();String outcome="failed";
        try{var result=execute(q);outcome=result.degraded()?"degraded":result.insufficientEvidence()?"empty":"succeeded";return result;}
        finally{if(lease!=null)operations.releaseQuery(lease);if(metrics!=null)metrics.timer("spaceagent.rag.retrieval.duration","outcome",outcome).record(System.nanoTime()-start,java.util.concurrent.TimeUnit.NANOSECONDS);}
    }
    private Result execute(Query q){
        long started=System.nanoTime();validate(q);currentActor(q);LinkedHashSet<String> warnings=new LinkedHashSet<>();List<Candidate> candidates=new ArrayList<>();
        // Authorize the complete binding set before any model/network request (no partial unauthorized queries).
        Map<String,KnowledgeBase> bases=new LinkedHashMap<>();
        for(String id:q.baseIds())bases.put(id,access.authorize(q.actor(),id,KnowledgeBase.Permission.READ).base());
        VectorIndexGateway gateway=vectors.getIfAvailable();
        if(gateway==null)throw new BusinessException("Vector retrieval is unavailable",HttpStatus.SERVICE_UNAVAILABLE,"KNOWLEDGE_VECTOR_UNAVAILABLE");
        boolean partial=false,degraded=false;int groupBudget=16;
        for(var base:bases.values()){
            var published=repository.published(base.id(),257);if(published.size()>256){partial=true;warnings.add("DOCUMENT_SCOPE_LIMIT");published=published.subList(0,256);}
            Map<String,List<KnowledgeRetrievalIndexRepository.Published>> groups=new LinkedHashMap<>();
            for(var p:published)groups.computeIfAbsent(p.spaceId()+":"+p.schema(),ignored->new ArrayList<>()).add(p);
            for(var rows:groups.values()){
                if(System.nanoTime()-started>45_000_000_000L){partial=true;warnings.add("RETRIEVAL_TIME_BUDGET");break;}
                if(groupBudget--<=0){partial=true;warnings.add("SPACE_SCOPE_LIMIT");break;}
                var row=rows.getFirst();var model=catalog.findSpace(base.id(),row.spaceId()).orElseThrow(()->new IllegalStateException("Published space missing"));
                var space=new VectorIndexGateway.Space(model.id(),model.fingerprint(),model.dimensions(),row.schema());
                gateway.validateSpace(space);
                List<Double> queryVector=null;
                // This key is stable for a caller retry, and never reused across model spaces or changed query inputs.
                try{
                    currentActor(q);access.authorize(q.actor(),base.id(),KnowledgeBase.Permission.READ);
                    var response=embeddings.embed(new EmbeddingBatchApplicationApi.Request(q.actor().organizationId(),q.actor().userId(),
                        "rag-query:"+q.operationKey()+":"+model.id(),model.providerId(),model.modelId(),model.providerFingerprint(),model.fingerprint(),model.dimensions(),List.of(q.query())));
                    if(response.status()==EmbeddingBatchApplicationApi.Status.SUCCEEDED){queryVector=response.vectors().getFirst();model.validateVector(queryVector);}
                    else {degraded=true;warnings.add("QUERY_EMBEDDING_"+response.status().name());}
                }catch(BusinessException e){if(e.getStatus()==HttpStatus.CONFLICT || e.getStatus()==HttpStatus.FORBIDDEN)throw e;degraded=true;warnings.add("QUERY_EMBEDDING_UNAVAILABLE");}
                Map<String,Candidate> ranked=new LinkedHashMap<>();
                for(int offset=0;offset<rows.size();offset+=64){
                    if(System.nanoTime()-started>45_000_000_000L){partial=true;warnings.add("RETRIEVAL_TIME_BUDGET");break;}
                    var slice=rows.subList(offset,Math.min(rows.size(),offset+64));
                    var scope=new VectorIndexGateway.Scope(base.scope()==KnowledgeBase.Scope.PERSONAL?"user:"+base.ownerId():"org:"+base.organizationId(),base.id(),slice.stream().map(KnowledgeRetrievalIndexRepository.Published::generationId).toList());
                    // Fixed overfetch bounds replace an unbounded 'search until enough authorized hits' loop.
                    int fetch=Math.min(100,Math.max(20,q.topK()*4));
                    if(queryVector!=null)try{fuse(ranked,base.id(),scope,gateway.search(space,scope,queryVector,fetch));}
                    catch(RuntimeException e){degraded=true;warnings.add("DENSE_RECALL_UNAVAILABLE");}
                    if(row.schema().equals("hybrid_v2"))try{fuse(ranked,base.id(),scope,gateway.lexicalSearch(space,scope,q.query(),fetch));}
                    catch(RuntimeException e){degraded=true;warnings.add("LEXICAL_RECALL_UNAVAILABLE");}
                    else {degraded=true;warnings.add("LEGACY_DENSE_ONLY");}
                }
                candidates.addAll(ranked.values());
            }
        }
        long recallFinished=System.nanoTime();String ranking="RRF",rerankerModel=null;
        candidates.sort(Comparator.comparingDouble(Candidate::score).reversed().thenComparing(Candidate::key));
        List<Candidate> safeCandidates=new ArrayList<>();List<String> texts=new ArrayList<>();
        authorizeBases(q, bases.keySet());
        var boundedCandidates = candidates.stream().limit(32).toList();
        if(candidates.size()>32){partial=true;warnings.add("RERANK_CANDIDATE_LIMIT");}
        var candidateEvidence = evidence(boundedCandidates);
        for(var c:boundedCandidates){
            var m=c.match();
            var evidence=Optional.ofNullable(candidateEvidence.get(evidenceKey(c)));
            if(evidence.isEmpty() || !evidence.get().documentId().equals(m.documentId())){partial=true;warnings.add("STALE_CANDIDATES_REMOVED");continue;}
            safeCandidates.add(c);texts.add(evidence.get().content());
        }
        if(reranker!=null && !texts.isEmpty()){
            currentActor(q);for(String id:bases.keySet())access.authorize(q.actor(),id,KnowledgeBase.Permission.READ);
            try{
                var ranked=reranker.rank(new com.spaceagent.platform.inference.api.RerankApplicationApi.Request(q.actor().organizationId(),q.actor().userId(),q.query(),texts));
                if(ranked.succeeded() && ranked.scores().size()==safeCandidates.size() && ranked.scores().stream().map(s->s.index()).distinct().count()==safeCandidates.size()){
                    var ordered=new ArrayList<Candidate>();for(var score:ranked.scores())ordered.add(safeCandidates.get(score.index()));safeCandidates=ordered;
                    ranking="RERANK";rerankerModel=ranked.modelId()+"@"+ranked.modelRevision();
                }else{degraded=true;warnings.add("RERANK_FALLBACK");}
            }catch(RuntimeException e){degraded=true;warnings.add("RERANK_FALLBACK");}
        }
        long rerankFinished=System.nanoTime();
        authorizeBases(q, bases.keySet());
        var finalEvidence = evidence(safeCandidates);
        List<Hit> hits=new ArrayList<>();Set<String> seen=new HashSet<>();int used=0;
        for(var candidate:safeCandidates){
            if(hits.size()>=q.topK())break;
            var m=candidate.match();var evidence=Optional.ofNullable(finalEvidence.get(evidenceKey(candidate)));
            if(evidence.isEmpty() || !evidence.get().documentId().equals(m.documentId())){partial=true;warnings.add("STALE_CANDIDATES_REMOVED");continue;}
            var e=evidence.get();if(!seen.add(e.documentId()+":"+e.contentHash()))continue;
            Map<String,Object> location=new LinkedHashMap<>(e.metadata());String content=e.content();
            var citation=new Citation(candidate.baseId(),e.documentId(),e.title(),e.generationId(),e.chunkId(),e.documentRevision(),e.contentHash(),location);
            Hit hit=new Hit(content,candidate.score(),citation);int available=q.maxContextTokens()-used;
            if(tokenizer.tokens(hit.rendered())>available){
                partial=true;warnings.add("CONTEXT_BUDGET_REACHED");
                int end=content.length();while(end>0 && tokenizer.tokens(new Hit(content.substring(0,end),candidate.score(),citation).rendered())>available){
                    end-=Math.max(1,end/8);if(end>0 && Character.isHighSurrogate(content.charAt(end-1)))end--;}
                if(end==0)continue;content=content.substring(0,end);location.put("excerpt",true);location.put("excerptCharacters",end);
                location.put("excerptHash",KnowledgeIndexBuildCoordinator.hash(content.getBytes(StandardCharsets.UTF_8)));
                if(location.get("startOffset") instanceof Number start)location.put("endOffset",start.intValue()+end);
                citation=new Citation(candidate.baseId(),e.documentId(),e.title(),e.generationId(),e.chunkId(),e.documentRevision(),e.contentHash(),location);
                hit=new Hit(content,candidate.score(),citation);
            }
            used+=tokenizer.tokens(hit.rendered());hits.add(hit);
        }
        // An authorization change during downstream recall invalidates the result rather than silently sharing evidence.
        currentActor(q);for(String id:bases.keySet())access.authorize(q.actor(),id,KnowledgeBase.Permission.READ);
        return new Result(hits,partial,degraded,hits.isEmpty(),used,List.copyOf(warnings),ranking,rerankerModel,
            Map.of("recall",(recallFinished-started)/1_000_000,"evidenceAndRerank",(rerankFinished-recallFinished)/1_000_000,
                   "context",(System.nanoTime()-rerankFinished)/1_000_000));
    }
    private static void fuse(Map<String,Candidate> merged,String base,VectorIndexGateway.Scope scope,List<VectorIndexGateway.Match> matches){
        Set<String> oneChannel=new HashSet<>();int rank=0;
        for(var m:matches){if(++rank>100)break;if(!scope.generationIds().contains(m.generationId()) || !Double.isFinite(m.score()))continue;
            String key=base+":"+m.generationId()+":"+m.chunkId();if(!oneChannel.add(key))continue;
            double contribution=1.0/(60+rank);var old=merged.get(key);
            merged.put(key,new Candidate(key,base,m,contribution+(old==null?0:old.score())));
        }
    }

    private void authorizeBases(Query query, Collection<String> bases) {
        currentActor(query);
        for (String base : new LinkedHashSet<>(bases)) access.authorize(query.actor(), base, KnowledgeBase.Permission.READ);
    }

    private Map<KnowledgeRetrievalIndexRepository.EvidenceKey, KnowledgeRetrievalIndexRepository.Evidence> evidence(List<Candidate> candidates) {
        return repository.evidenceBatch(candidates.stream().map(KnowledgeCollectionRetrievalService::evidenceKey).toList());
    }

    private static KnowledgeRetrievalIndexRepository.EvidenceKey evidenceKey(Candidate candidate) {
        var match = candidate.match();
        return new KnowledgeRetrievalIndexRepository.EvidenceKey(candidate.baseId(), match.generationId(), match.chunkId(), match.contentHash());
    }
    private void currentActor(Query q){
        if(!activity.isUserActive(q.actor().userId()) || identity.findTenant(q.actor().organizationId()).filter(t->"ACTIVE".equals(t.status().name())).isEmpty()
            || identity.findTenantMembership(q.actor().organizationId(),q.actor().userId()).filter(m->"ACTIVE".equals(m.status().name())).isEmpty())
            throw new BusinessException("Retrieval actor unavailable",HttpStatus.FORBIDDEN,"KNOWLEDGE_RETRIEVAL_ACTOR_UNAVAILABLE");
    }
    private static void validate(Query q){
        if(q==null || q.actor()==null || q.actor().userId()==null || q.actor().organizationId()==null || q.baseIds().isEmpty() || q.baseIds().size()>8
            || q.query()==null || q.query().isBlank() || q.query().length()>4000 || q.query().getBytes(StandardCharsets.UTF_8).length>8000
            || q.topK()<1 || q.topK()>20 || q.maxContextTokens()<64 || q.maxContextTokens()>16384
            || q.operationKey()==null || !q.operationKey().matches("[A-Za-z0-9_.:-]{1,80}"))
            throw new BusinessException("Invalid bounded retrieval request",HttpStatus.BAD_REQUEST,"KNOWLEDGE_RETRIEVAL_INVALID");
    }
    private record Candidate(String key,String baseId,VectorIndexGateway.Match match,double score){}
}

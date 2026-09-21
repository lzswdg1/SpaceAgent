package com.spaceagent.platform.knowledge.application;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.spaceagent.platform.knowledge.api.*;
import com.spaceagent.platform.knowledge.domain.*;
import com.spaceagent.shared.exception.BusinessException;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.*;

/** Restores only an already active generation from verified stored vectors. Never invokes a model. */
@Service @ConditionalOnProperty(prefix="platform",name="persistence",havingValue="postgres")
public class KnowledgeIndexRepairService implements KnowledgeIndexRepairApi {
    private final KnowledgeIndexRepairRepository repairs;private final KnowledgeAccessApplicationApi access;private final KnowledgeBaseRepository bases;
    private final KnowledgeIndexPublicationRepository publications;private final KnowledgeIndexCatalogRepository catalog;private final KnowledgeIndexJobRepository jobs;
    private final KnowledgeIndexObjectStore objects;private final ObjectProvider<VectorIndexGateway> gateways;private final ObjectMapper json;
    public KnowledgeIndexRepairService(KnowledgeIndexRepairRepository repairs,KnowledgeAccessApplicationApi access,KnowledgeBaseRepository bases,
        KnowledgeIndexPublicationRepository publications,KnowledgeIndexCatalogRepository catalog,KnowledgeIndexJobRepository jobs,KnowledgeIndexObjectStore objects,ObjectProvider<VectorIndexGateway> gateways,ObjectMapper json){
        this.repairs=repairs;this.access=access;this.bases=bases;this.publications=publications;this.catalog=catalog;this.jobs=jobs;this.objects=objects;this.gateways=gateways;this.json=json;}
    @Transactional public View request(KnowledgeBaseApplicationApi.Actor actor,String base,String doc,String gen,String key){
        if(key==null || !key.matches("[A-Za-z0-9_.:-]{1,80}") || actor==null || actor.organizationId()==null)throw new BusinessException("Invalid repair request",HttpStatus.BAD_REQUEST,"KNOWLEDGE_REPAIR_INVALID");
        bases.lock(base);access.authorize(actor,base,KnowledgeBase.Permission.MANAGE);current(base,doc,gen);
        try{return view(repairs.enqueue(base,doc,gen,actor.organizationId(),actor.userId(),key));}catch(IllegalStateException e){throw new BusinessException("Repair key or queue conflict",HttpStatus.CONFLICT,"KNOWLEDGE_REPAIR_CONFLICT");}
    }
    public View get(KnowledgeBaseApplicationApi.Actor actor,String id){var r=repairs.find(id).orElseThrow(()->new BusinessException("Repair not found",HttpStatus.NOT_FOUND));access.authorize(actor,r.baseId(),KnowledgeBase.Permission.READ);return view(r);}
    public boolean runOne(){var gateway=gateways.getIfAvailable();if(gateway==null)return false;var claim=repairs.claim();if(claim.isEmpty())return false;var r=claim.get();
        try{
            var actor=new KnowledgeBaseApplicationApi.Actor(r.actorId(),r.tenantId());var base=access.authorize(actor,r.baseId(),KnowledgeBase.Permission.MANAGE).base();current(r.baseId(),r.documentId(),r.generationId());
            var gen=catalog.findGeneration(r.baseId(),r.generationId()).orElseThrow();var model=catalog.findSpace(r.baseId(),gen.spaceId()).orElseThrow();
            var job=jobs.find(repairs.completedIndexJob(gen.id()).orElseThrow()).orElseThrow();
            var space=new VectorIndexGateway.Space(model.id(),model.fingerprint(),model.dimensions(),job.input().storageSchema());
            var scope=new VectorIndexGateway.Scope(base.scope()==KnowledgeBase.Scope.PERSONAL?"user:"+base.ownerId():"org:"+base.organizationId(),base.id(),List.of(gen.id()));
            Map<String,KnowledgeIndexPublicationRepository.Chunk> chunks=new HashMap<>();publications.chunks(gen.id()).forEach(c->chunks.put(c.id(),c));
            var manifest=jobs.manifest(job.id(),KnowledgeIndexJob.Stage.EMBEDDING).orElseThrow();
            var batches=jobs.batches(job.id(),KnowledgeIndexJob.Stage.EMBEDDING,0,512);if(batches.size()!=manifest.batchCount() || chunks.isEmpty())throw new IllegalStateException();
            gateway.ensureSpace(space);Set<String> seen=new HashSet<>();
            for(var batch:batches){
                if(!repairs.renew(r) || batch.state()!=KnowledgeIndexJob.BatchState.COMPLETED)throw new IllegalStateException();
                access.authorize(actor,r.baseId(),KnowledgeBase.Permission.MANAGE);current(r.baseId(),r.documentId(),r.generationId());
                var output=json.readValue(objects.read(batch.outputReference(),batch.outputHash()),KnowledgeEmbeddedBatch.class);
                if(!output.spaceId().equals(model.id()) || !output.spaceFingerprint().equals(model.fingerprint()))throw new IllegalStateException();
                List<VectorIndexGateway.Entry> entries=new ArrayList<>();
                for(var vector:output.chunks()){var c=chunks.get(vector.chunkId());if(c==null || !seen.add(c.id()) || !c.contentHash().equals(vector.contentHash()))throw new IllegalStateException();model.validateVector(vector.vector());
                    entries.add(new VectorIndexGateway.Entry(scope.key(),base.id(),r.documentId(),gen.id(),c.id(),c.contentHash(),vector.vector(),c.content()));}
                gateway.upsertBatch(space,entries);if(!gateway.verifyBatch(space,entries))throw new IllegalStateException();
                if(gateway.search(space,scope,entries.getFirst().vector(),1).isEmpty())throw new IllegalStateException();
            }
            current(r.baseId(),r.documentId(),r.generationId());if(seen.size()!=chunks.size())throw new IllegalStateException();return repairs.finish(r,null);
        }catch(Exception e){return repairs.finish(r,"INDEX_REPAIR_NOT_CONFIRMED");}
    }
    private void current(String base,String doc,String gen){publications.head(base,doc).filter(h->h.state().equals("ACTIVE") && gen.equals(h.activeGenerationId())).orElseThrow(()->new BusinessException("Active generation changed",HttpStatus.CONFLICT,"KNOWLEDGE_REPAIR_STALE"));}
    private static View view(KnowledgeIndexRepairRepository.Repair r){return new View(r.id(),r.baseId(),r.documentId(),r.generationId(),r.state(),r.safeCode());}
}

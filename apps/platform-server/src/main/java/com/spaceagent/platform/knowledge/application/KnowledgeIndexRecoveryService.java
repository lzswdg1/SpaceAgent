package com.spaceagent.platform.knowledge.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.spaceagent.platform.inference.api.EmbeddingBatchApplicationApi;
import com.spaceagent.platform.knowledge.api.*;
import com.spaceagent.platform.knowledge.domain.*;
import com.spaceagent.shared.exception.BusinessException;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static com.spaceagent.platform.knowledge.domain.KnowledgeIndexJob.*;

@Service @ConditionalOnProperty(prefix="platform",name="persistence",havingValue="postgres")
public class KnowledgeIndexRecoveryService implements KnowledgeIndexRecoveryApplicationApi {
    private final KnowledgeAccessApplicationApi access;private final KnowledgeIndexJobRepository jobs;
    private final KnowledgeIndexRecoveryRepository recovery;private final KnowledgeIndexPublicationRepository publications;
    private final KnowledgeIndexCatalogRepository catalog;private final EmbeddingBatchApplicationApi inference;
    private final KnowledgeIndexObjectStore objects;private final KnowledgeIndexJobApplicationApi views;private final ObjectMapper json;
    public KnowledgeIndexRecoveryService(KnowledgeAccessApplicationApi access,KnowledgeIndexJobRepository jobs,KnowledgeIndexRecoveryRepository recovery,
            KnowledgeIndexPublicationRepository publications,KnowledgeIndexCatalogRepository catalog,EmbeddingBatchApplicationApi inference,
            KnowledgeIndexObjectStore objects,KnowledgeIndexJobApplicationApi views,ObjectMapper json){this.access=access;this.jobs=jobs;this.recovery=recovery;this.publications=publications;this.catalog=catalog;this.inference=inference;this.objects=objects;this.views=views;this.json=json;}
    public KnowledgeIndexJobApplicationApi.JobView reconcile(KnowledgeBaseApplicationApi.Actor actor,String id,long revision){
        var original=jobs.find(id).orElseThrow(()->new BusinessException("Index job not found",HttpStatus.NOT_FOUND));
        access.authorize(actor,original.input().baseId(),KnowledgeBase.Permission.MANAGE);
        var source=publications.source(original.input().generationId()).orElseThrow();
        var head=publications.head(source.baseId(),source.documentId()).orElseThrow();
        if(!head.state().equals("ACTIVE") || head.revision()!=source.documentRevision())throw new BusinessException("Source changed",HttpStatus.CONFLICT,"INDEX_SOURCE_CHANGED");
        var job=recovery.claim(id,revision,"reconcile-"+UUID.randomUUID()).orElseThrow(()->new BusinessException("Index revision changed",HttpStatus.CONFLICT,"INDEX_RECOVERY_CONFLICT"));
        try {
            var generation=catalog.findGeneration(job.input().baseId(),job.input().generationId()).orElseThrow();
            var space=catalog.findSpace(job.input().baseId(),generation.spaceId()).orElseThrow();
            var chunks=publications.chunks(generation.id()).stream().map(c->new KnowledgeEmbeddingStage.Chunk(c.id(),c.content())).toList();
            List<List<KnowledgeEmbeddingStage.Chunk>> batches=job.progress().stage()==Stage.EMBEDDING?KnowledgeEmbeddingStage.split(chunks,space.dimensions()):List.of();
            for(var b:jobs.batches(id,job.progress().stage(),0,512)) {
                if(b.state()!=BatchState.UNKNOWN && b.state()!=BatchState.IN_FLIGHT)continue;
                if(!jobs.renew(job.lease(),90))throw new IllegalStateException("Recovery lease expired");
                access.authorize(actor,source.baseId(),KnowledgeBase.Permission.MANAGE);
                if(b.stage()==Stage.EMBEDDING) {
                    var input=batches.get(b.ordinal());
                    String hash=KnowledgeIndexBuildCoordinator.hash(json.writeValueAsBytes(List.of(space.fingerprint(),input)));
                    if(!hash.equals(b.inputHash()) || b.itemCount()!=input.size())throw new IllegalStateException("Recovery inputs changed");
                    var outcome=inference.reconcile(new EmbeddingBatchApplicationApi.Request(job.input().organizationId(),job.input().requestedBy(),
                            job.id()+":embedding:"+b.ordinal()+":attempt:"+job.progress().attempts(),space.providerId(),space.modelId(),
                            space.providerFingerprint(),space.fingerprint(),space.dimensions(),input.stream().map(KnowledgeEmbeddingStage.Chunk::text).toList()));
                    if(outcome.status()==EmbeddingBatchApplicationApi.Status.SUCCEEDED) {
                        if(outcome.vectors().size()!=input.size())throw new IllegalStateException("Recovery output count changed");
                        List<KnowledgeEmbeddedBatch.ChunkVector> data=new ArrayList<>();
                        for(int i=0;i<input.size();i++){space.validateVector(outcome.vectors().get(i));data.add(new KnowledgeEmbeddedBatch.ChunkVector(input.get(i).id(),
                                KnowledgeIndexBuildCoordinator.hash(input.get(i).text().getBytes(StandardCharsets.UTF_8)),outcome.vectors().get(i)));}
                        byte[] bytes=json.writeValueAsBytes(new KnowledgeEmbeddedBatch(space.id(),space.fingerprint(),data));String digest=KnowledgeIndexBuildCoordinator.hash(bytes);
                        String ref=objects.put(id,digest,bytes);recovery.resolve(job.lease(),new Batch(id,b.stage(),b.ordinal(),b.inputHash(),b.itemCount(),BatchState.COMPLETED,ref,digest));
                    } else if(outcome.status()==EmbeddingBatchApplicationApi.Status.REJECTED) reject(job,b);
                    else throw new IllegalStateException("Model outcome remains unconfirmed");
                } else {
                    // Local transforms and identical-content vector writes are replayable; never change their pinned inputs.
                    reject(job,b);
                }
            }
            var current=jobs.find(id).orElseThrow();
            var failed=jobs.fail(current.lease(),Failure.PERMANENT,"INDEX_RECOVERY_VERIFIED");
            if(failed.progress().attempts()<failed.input().maxAttempts())jobs.retry(id,failed.progress().revision());
            recovery.audit(id,actor.userId(),"VERIFIED_REPLAY_READY");
        } catch(Exception e){if(jobs.renew(job.lease(),90))jobs.fail(job.lease(),Failure.UNKNOWN,"INDEX_RECOVERY_UNCONFIRMED");recovery.audit(id,actor.userId(),"OUTCOME_STILL_UNKNOWN");}
        return views.get(actor,id);
    }
    private void reject(KnowledgeIndexJob job,Batch b){recovery.resolve(job.lease(),new Batch(b.jobId(),b.stage(),b.ordinal(),b.inputHash(),b.itemCount(),BatchState.REJECTED,null,null));}
}

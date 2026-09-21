package com.spaceagent.platform.knowledge.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.spaceagent.platform.identity.api.IdentityActivityApplicationApi;
import com.spaceagent.platform.identity.api.IdentityApplicationApi;
import com.spaceagent.platform.inference.api.EmbeddingBatchApplicationApi;
import com.spaceagent.platform.knowledge.api.*;
import com.spaceagent.platform.knowledge.domain.*;
import com.spaceagent.shared.exception.BusinessException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import static com.spaceagent.platform.knowledge.domain.KnowledgeIndexJob.*;

/** Knowledge-owned stage coordinator, not an automatic scheduler or an ingestion HTTP bypass. */
@Service
public class KnowledgeEmbeddingStage {
    private final KnowledgeIndexJobRepository jobs;
    private final KnowledgeIndexCatalogRepository catalog;
    private final KnowledgeAccessApplicationApi access;
    private final IdentityApplicationApi identity;
    private final IdentityActivityApplicationApi activity;
    private final EmbeddingBatchApplicationApi embeddings;
    private final KnowledgeIndexObjectStore outputs;
    private final ObjectMapper json;
    public KnowledgeEmbeddingStage(KnowledgeIndexJobRepository jobs,KnowledgeIndexCatalogRepository catalog,
            KnowledgeAccessApplicationApi access,IdentityApplicationApi identity,IdentityActivityApplicationApi activity,
            EmbeddingBatchApplicationApi embeddings,KnowledgeIndexObjectStore outputs,ObjectMapper json) {
        this.jobs=jobs; this.catalog=catalog; this.access=access; this.identity=identity; this.activity=activity;
        this.embeddings=embeddings; this.outputs=outputs; this.json=json;
    }
    public record Chunk(String id,String text) {
        public Chunk {
            if(id==null || !id.matches("[A-Za-z0-9_.:-]{1,96}")) throw new IllegalArgumentException("Invalid chunk ID");
            EmbeddingBatchApplicationApi.conservativeInputTokens(text);
        }
        @Override public String toString() { return "Chunk[content redacted]"; }
    }
    @Transactional(propagation=Propagation.NOT_SUPPORTED)
    public KnowledgeIndexJob execute(Lease lease,List<Chunk> chunks) {
        var job=jobs.find(lease.jobId()).orElseThrow(); authorize(job);
        if(job.progress().stage()!=Stage.EMBEDDING || !jobs.renew(lease,90)) throw new IllegalStateException("Embedding lease unavailable");
        var generation=catalog.findGeneration(job.input().baseId(),job.input().generationId()).orElseThrow();
        var space=catalog.findSpace(job.input().baseId(),generation.spaceId()).orElseThrow();
        var capabilities = EmbeddingBatchApplicationApi.modelCapabilities(space.modelId());
        List<List<Chunk>> batches=split(chunks,space.dimensions(),capabilities.maximumItems());
        jobs.planStage(lease,new Manifest(job.id(),Stage.EMBEDDING,hash(jsonBytes(List.of(space.fingerprint(),chunks))),batches.size()));
        for(int ordinal=0;ordinal<batches.size();ordinal++) {
            var input=batches.get(ordinal);
            String inputHash=hash(jsonBytes(List.of(space.fingerprint(),input)));
            authorize(job);
            var batch=jobs.beginBatch(lease,Stage.EMBEDDING,ordinal,inputHash,input.size());
            try {
                if(batch.state()==BatchState.COMPLETED) {
                    validateOutput(json.readValue(outputs.read(batch.outputReference(),batch.outputHash()),KnowledgeEmbeddedBatch.class),space,input);
                    continue;
                }
                // This is the last admission check before the trusted Inference API; no long DB transaction spans the POST.
                if(!jobs.renew(lease,90)) throw new IllegalStateException("Embedding lease expired");
                authorize(job);
                var result=embeddings.embed(new EmbeddingBatchApplicationApi.Request(job.input().organizationId(),job.input().requestedBy(),
                        job.id()+":embedding:"+ordinal+":attempt:"+job.progress().attempts(),space.providerId(),space.modelId(),
                        space.providerFingerprint(),space.fingerprint(),space.dimensions(),input.stream().map(Chunk::text).toList()));
                if(result.status()==EmbeddingBatchApplicationApi.Status.REJECTED) {
                    jobs.rejectBatch(lease,Stage.EMBEDDING,ordinal);
                    return jobs.fail(lease,Failure.PERMANENT,"INDEX_EMBEDDING_REJECTED");
                }
                if(result.status()!=EmbeddingBatchApplicationApi.Status.SUCCEEDED)
                    return jobs.fail(lease,Failure.UNKNOWN,"INDEX_EMBEDDING_UNKNOWN");
                if(!jobs.renew(lease,90)) throw new IllegalStateException("Embedding result arrived after lease invalidation");
                if(result.vectors().size()!=input.size()) throw new IllegalStateException("Embedding result count mismatch");
                List<KnowledgeEmbeddedBatch.ChunkVector> vectors=new ArrayList<>();
                for(int i=0;i<input.size();i++) vectors.add(new KnowledgeEmbeddedBatch.ChunkVector(input.get(i).id(),
                        hash(input.get(i).text().getBytes(StandardCharsets.UTF_8)),result.vectors().get(i)));
                var output=new KnowledgeEmbeddedBatch(space.id(),space.fingerprint(),vectors); validateOutput(output,space,input);
                byte[] bytes=jsonBytes(output); String outputHash=hash(bytes);
                String reference=outputs.put(job.id(),outputHash,bytes);
                jobs.completeBatch(lease,new Batch(job.id(),Stage.EMBEDDING,ordinal,inputHash,input.size(),BatchState.COMPLETED,reference,outputHash));
            } catch(BusinessException e) {
                // Public Inference validation rejects before dispatch; remote outcomes are returned as typed results.
                jobs.rejectBatch(lease,Stage.EMBEDDING,ordinal);
                return jobs.fail(lease,Failure.PERMANENT,"INDEX_EMBEDDING_ACCESS_OR_INPUT_REJECTED");
            } catch(Exception e) {
                return jobs.fail(lease,Failure.UNKNOWN,"INDEX_EMBEDDING_OUTPUT_UNCONFIRMED");
            }
        }
        return jobs.advance(lease,Stage.EMBEDDING);
    }
    private void authorize(KnowledgeIndexJob job) {
        String org=job.input().organizationId(),actor=job.input().requestedBy();
        if(!activity.isUserActive(actor) || org==null || identity.findTenant(org).filter(t->"ACTIVE".equals(t.status().name())).isEmpty()
                || identity.findTenantMembership(org,actor).filter(m->"ACTIVE".equals(m.status().name())).isEmpty())
            throw new BusinessException("Index actor is unavailable",HttpStatus.FORBIDDEN,"INDEX_ACTOR_UNAVAILABLE");
        access.authorize(new KnowledgeBaseApplicationApi.Actor(actor,org),job.input().baseId(),KnowledgeBase.Permission.WRITE);
    }
    static List<List<Chunk>> split(List<Chunk> chunks,int dimensions) {
        return split(chunks, dimensions, 64);
    }

    static List<List<Chunk>> split(List<Chunk> chunks,int dimensions,int modelMaximumItems) {
        if(chunks==null || chunks.isEmpty() || chunks.size()>32768) throw new IllegalArgumentException("Invalid chunk manifest");
        if (modelMaximumItems < 1 || modelMaximumItems > 64) throw new IllegalArgumentException("Invalid model batch limit");
        int maxItems=Math.min(modelMaximumItems,262144/dimensions), tokens=0,total=0;
        Set<String> ids=new HashSet<>(); List<List<Chunk>> batches=new ArrayList<>(); List<Chunk> current=new ArrayList<>();
        for(var chunk:chunks) {
            if(!ids.add(chunk.id())) throw new IllegalArgumentException("Duplicate chunk ID");
            int size=EmbeddingBatchApplicationApi.conservativeInputTokens(chunk.text()); total=Math.addExact(total,size);
            if(total>10_000_000) throw new IllegalArgumentException("Embedding stage size limit exceeded");
            if(!current.isEmpty() && (current.size()>=maxItems || tokens+size>32768)) {
                batches.add(List.copyOf(current)); current.clear(); tokens=0;
            }
            current.add(chunk); tokens+=size;
        }
        if(!current.isEmpty()) batches.add(List.copyOf(current));
        if(batches.size()>512) throw new IllegalArgumentException("Embedding batch count limit exceeded");
        return List.copyOf(batches);
    }
    private void validateOutput(KnowledgeEmbeddedBatch output,KnowledgeEmbeddingSpace space,List<Chunk> input) {
        if(!output.spaceId().equals(space.id()) || !output.spaceFingerprint().equals(space.fingerprint()) || output.chunks().size()!=input.size())
            throw new IllegalStateException("Embedding output space mismatch");
        for(int i=0;i<input.size();i++) {
            var vector=output.chunks().get(i);
            if(!vector.chunkId().equals(input.get(i).id()) || !vector.contentHash().equals(hash(input.get(i).text().getBytes(StandardCharsets.UTF_8))))
                throw new IllegalStateException("Embedding output input mismatch");
            space.validateVector(vector.vector());
        }
    }
    private byte[] jsonBytes(Object value) { try {return json.writeValueAsBytes(value);} catch(Exception e) {throw new IllegalStateException("Index serialization failed");} }
    private static String hash(byte[] bytes) {try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));}catch(Exception e){throw new IllegalStateException("Index hashing failed");}}
}

package com.spaceagent.platform.knowledge.application;

import com.spaceagent.platform.knowledge.api.*;
import com.spaceagent.platform.knowledge.domain.*;
import com.spaceagent.shared.exception.BusinessException;
import com.spaceagent.shared.id.IdGenerator;
import com.spaceagent.shared.time.TimeProvider;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.function.Supplier;
import static com.spaceagent.platform.knowledge.domain.KnowledgeBase.Permission.*;

@Service
public class KnowledgeIndexJobApplicationService implements KnowledgeIndexJobApplicationApi {
    private final KnowledgeIndexJobRepository jobs;
    private final KnowledgeIndexCatalogRepository catalog;
    private final KnowledgeAccessApplicationApi access;
    private final IdGenerator ids;
    private final TimeProvider time;
    private final String schema;
    public KnowledgeIndexJobApplicationService(KnowledgeIndexJobRepository jobs,KnowledgeIndexCatalogRepository catalog,
            KnowledgeAccessApplicationApi access,IdGenerator ids,TimeProvider time,@org.springframework.beans.factory.annotation.Value("${platform.knowledge.vector-store.schema:hybrid_v2}") String schema) {
        this.jobs=jobs; this.catalog=catalog; this.access=access; this.ids=ids; this.time=time;
        this.schema=schema;
    }
    @Override @Transactional
    public JobView enqueue(KnowledgeBaseApplicationApi.Actor actor,String base,String generation,String key) {
        access.authorize(actor,base,WRITE);
        var target=catalog.findGeneration(base,generation).orElseThrow(KnowledgeIndexJobApplicationService::missing);
        // Generation fingerprint already pins content, parser/chunker and immutable embedding space.
        String hash=digest(target.fingerprint()+"\n"+schema);
        return translate(()->view(jobs.enqueue(KnowledgeIndexJob.queued(ids.nextId(),new KnowledgeIndexJob.Input(base,generation,
                actor.userId(),actor.organizationId(),key,hash,schema,5),time.now()))));
    }
    @Override public JobView get(KnowledgeBaseApplicationApi.Actor actor,String id) { return view(authorize(actor,id,READ)); }
    @Override public List<JobView> list(KnowledgeBaseApplicationApi.Actor actor,String base,int offset,int limit) {
        access.authorize(actor,base,READ); page(offset,limit);
        return jobs.list(base,offset,limit).stream().map(KnowledgeIndexJobApplicationService::view).toList();
    }
    @Override public List<BatchView> batches(KnowledgeBaseApplicationApi.Actor actor,String id,KnowledgeIndexJob.Stage stage,int offset,int limit) {
        authorize(actor,id,READ); page(offset,limit);
        if(stage==null) throw new BusinessException("Index stage is required",HttpStatus.BAD_REQUEST,"KNOWLEDGE_INDEX_INPUT_INVALID");
        return jobs.batches(id,stage,offset,limit).stream().map(b->new BatchView(b.stage(),b.ordinal(),b.itemCount(),b.state())).toList();
    }
    @Override @Transactional public JobView cancel(KnowledgeBaseApplicationApi.Actor actor,String id,long revision) {
        authorize(actor,id,MANAGE); return translate(()->view(jobs.cancel(id,revision)));
    }
    @Override @Transactional public JobView retry(KnowledgeBaseApplicationApi.Actor actor,String id,long revision) {
        authorize(actor,id,MANAGE); return translate(()->view(jobs.retry(id,revision)));
    }
    private KnowledgeIndexJob authorize(KnowledgeBaseApplicationApi.Actor actor,String id,KnowledgeBase.Permission permission) {
        var job=jobs.find(id).orElseThrow(KnowledgeIndexJobApplicationService::missing);
        access.authorize(actor,job.input().baseId(),permission); return job;
    }
    private static JobView view(KnowledgeIndexJob j) {
        var p=j.progress(); return new JobView(j.id(),j.input().baseId(),j.input().generationId(),p.stage(),p.state(),p.revision(),
                p.attempts(),j.input().maxAttempts(),p.nextAttemptAt(),p.errorCode(),j.createdAt(),j.updatedAt());
    }
    private static <T> T translate(Supplier<T> operation) {
        try { return operation.get(); }
        catch(IllegalArgumentException e) { throw new BusinessException("Invalid index job input",HttpStatus.BAD_REQUEST,"KNOWLEDGE_INDEX_INPUT_INVALID"); }
        catch(IllegalStateException e) { throw new BusinessException("Index job state, lease or idempotency conflict",HttpStatus.CONFLICT,"KNOWLEDGE_INDEX_CONFLICT"); }
    }
    private static void page(int offset,int limit) {
        if(offset<0 || offset>100000 || limit<1 || limit>100)
            throw new BusinessException("Invalid index job page",HttpStatus.BAD_REQUEST,"KNOWLEDGE_INDEX_INPUT_INVALID");
    }
    private static BusinessException missing() { return new BusinessException("Index resource not found",HttpStatus.NOT_FOUND,"KNOWLEDGE_INDEX_NOT_FOUND"); }
    private static String digest(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch(java.security.NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
}

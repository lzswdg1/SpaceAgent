package com.spaceagent.platform.knowledge.application;

import com.spaceagent.platform.knowledge.domain.*;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import java.util.List;

@Service @ConditionalOnProperty(prefix="platform",name="persistence",havingValue="postgres")
public class KnowledgeIndexMaintenanceWorker {
    private final KnowledgeIndexDeletionRepository deletion;private final KnowledgeIndexObjectStore objects;
    private final KnowledgeObjectInventory inventory;private final ObjectProvider<VectorIndexGateway> vectors;private final boolean enabled;
    private final com.spaceagent.platform.inference.api.EmbeddingBatchApplicationApi inference;
    @org.springframework.beans.factory.annotation.Autowired(required=false) private KnowledgeOperationalRepository operations;
    public KnowledgeIndexMaintenanceWorker(KnowledgeIndexDeletionRepository deletion,KnowledgeIndexObjectStore objects,
            KnowledgeObjectInventory inventory,ObjectProvider<VectorIndexGateway> vectors,
            @Value("${platform.knowledge.indexing.worker-enabled:false}") boolean enabled,com.spaceagent.platform.inference.api.EmbeddingBatchApplicationApi inference){this.deletion=deletion;this.objects=objects;this.inventory=inventory;this.vectors=vectors;this.enabled=enabled;this.inference=inference;}
    @Scheduled(fixedDelayString="${platform.knowledge.indexing.maintenance-delay-ms:5000}")
    public void tick(){if(enabled){if(operations!=null)operations.expireQueries();deletion.retireSuperseded();sweepOne();for(String ref:inventory.orphanCandidates(32))try{inventory.deleteIfOrphan(ref,objects);}catch(RuntimeException ignored){/* Durable inventory is retried next tick. */}}}
    public boolean sweepOne(){var gateway=vectors.getIfAvailable();if(gateway==null)return false;var claim=deletion.claim();if(claim.isEmpty())return false;
        var c=claim.get();boolean clean=false;
        try{
            if(c.jobId()!=null && c.requestTenant()!=null && !inference.eraseJobCache(c.requestTenant(),c.requestActor(),c.jobId()))return deletion.finish(c,false,"INDEX_CACHE_DELETE_PENDING");
            var scope=new VectorIndexGateway.Scope(c.scopeKey(),c.baseId(),List.of(c.generationId()));
            if(gateway.spaceExists(c.space())){gateway.deleteGeneration(c.space(),scope,c.generationId());if(!gateway.generationDeleted(c.space(),scope,c.generationId()))return deletion.finish(c,false,"INDEX_VECTOR_DELETE_UNCONFIRMED");}
            clean=(c.jobId()==null || objects.deleteOwner(c.jobId(),256)) && (!c.kind().equals("DOCUMENT") || objects.deleteOwner(c.documentId(),256));
            return deletion.finish(c,clean,clean?null:"INDEX_OBJECT_DELETE_PENDING");
        }catch(RuntimeException e){return deletion.finish(c,false,"INDEX_DELETE_UNCONFIRMED");}
    }
}

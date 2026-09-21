package com.spaceagent.platform.knowledge.application;
import com.spaceagent.platform.knowledge.domain.*;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

@Service @ConditionalOnProperty(prefix="platform",name="persistence",havingValue="postgres")
public class KnowledgeIndexWorker {
    private final KnowledgeIndexJobRepository jobs;private final KnowledgeIndexBuildCoordinator build;
    private final ObjectProvider<VectorIndexGateway> vectors;private final boolean enabled;
    private final String worker="knowledge-"+java.util.UUID.randomUUID();
    @org.springframework.beans.factory.annotation.Autowired private KnowledgeIndexRepairService repairs;
    @org.springframework.beans.factory.annotation.Autowired(required=false) private io.micrometer.core.instrument.MeterRegistry metrics;
    public KnowledgeIndexWorker(KnowledgeIndexJobRepository jobs,KnowledgeIndexBuildCoordinator build,ObjectProvider<VectorIndexGateway> vectors,
            @Value("${platform.knowledge.indexing.worker-enabled:false}") boolean enabled){this.jobs=jobs;this.build=build;this.vectors=vectors;this.enabled=enabled;}
    @Scheduled(fixedDelayString="${platform.knowledge.indexing.poll-delay-ms:1000}")
    public void tick(){if(enabled && vectors.getIfAvailable()!=null){jobs.claimNext(worker,90).ifPresent(job->{long start=System.nanoTime();var result=build.execute(job.lease());
        if(metrics!=null)metrics.timer("spaceagent.rag.index.duration","state",result.progress().state().name()).record(System.nanoTime()-start,java.util.concurrent.TimeUnit.NANOSECONDS);});repairs.runOne();}}
}

package com.spaceagent.platform.knowledge.infrastructure;
import com.spaceagent.platform.knowledge.domain.KnowledgeOperationalRepository;
import com.spaceagent.platform.knowledge.domain.KnowledgeIndexJob;
import io.micrometer.core.instrument.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;

/** Constant-cardinality metrics: never user IDs, source text, document IDs or queries. */
@Component @ConditionalOnProperty(prefix="platform",name="persistence",havingValue="postgres")
public class KnowledgeRagMetrics {
    private final KnowledgeOperationalRepository repository;private final Map<String,AtomicLong> values=new LinkedHashMap<>();
    public KnowledgeRagMetrics(KnowledgeOperationalRepository repository,MeterRegistry metrics){this.repository=repository;
        for(var state:KnowledgeIndexJob.State.values()){String key="jobs."+state.name();var value=new AtomicLong();values.put(key,value);Gauge.builder("spaceagent.rag.index.jobs",value,AtomicLong::get).tag("state",state.name()).register(metrics);}
        Map<String,String> names=Map.of("oldestPendingSeconds","index.oldest.pending.seconds","deletionsPending","deletions.pending","sourceBytes","source.bytes","legacySizeUnknown","legacy.size.unknown");
        names.forEach((key,name)->{var value=new AtomicLong();values.put(key,value);Gauge.builder("spaceagent.rag."+name,value,AtomicLong::get).register(metrics);});
    }
    @Scheduled(fixedDelayString="${platform.knowledge.metrics-delay-ms:15000}") public void refresh(){
        try{var snapshot=repository.statistics();values.forEach((key,value)->value.set(snapshot.getOrDefault(key,0L)));}catch(RuntimeException ignored){/* Scrape retains last values; database health is separate. */}
    }
}

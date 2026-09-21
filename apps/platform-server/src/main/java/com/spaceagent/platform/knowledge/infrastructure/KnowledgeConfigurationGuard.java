package com.spaceagent.platform.knowledge.infrastructure;
import jakarta.annotation.PostConstruct;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;
import java.util.Set;

@Component public class KnowledgeConfigurationGuard {
    private final Environment env;public KnowledgeConfigurationGuard(Environment env){this.env=env;}
    @PostConstruct public void validate(){
        String role=env.getProperty("platform.runtime.role","api");
        if(!Set.of("api","knowledge-worker").contains(role))throw new IllegalStateException("Unknown platform runtime role");
        if(role.equals("knowledge-worker") && !env.getProperty("platform.persistence","memory").equals("postgres"))throw new IllegalStateException("Knowledge Worker requires authoritative PostgreSQL");
        if(!Set.of("compatibility","disabled").contains(env.getProperty("platform.knowledge.legacy-mode","compatibility")))throw new IllegalStateException("Unknown legacy Knowledge mode");
        String backend=env.getProperty("platform.knowledge.vector-store.mode","none");
        if(!Set.of("none","milvus","pgvector").contains(backend))throw new IllegalStateException("Unknown Knowledge vector backend");
        if(!backend.equals("none") && !env.getProperty("platform.persistence","memory").equals("postgres"))throw new IllegalStateException("Knowledge vector backends require PostgreSQL");
        if(backend.equals("none") && env.getProperty("platform.knowledge.indexing.worker-enabled",Boolean.class,false))throw new IllegalStateException("Knowledge indexing worker requires a selected vector backend");
        range("platform.knowledge.pgvector.query-timeout-seconds",5,1,30);
        range("platform.knowledge.limits.documents-per-base",10000,1,1000000);
        range("platform.knowledge.limits.pending-per-tenant",64,1,1024);
        range("platform.knowledge.limits.source-bytes-per-tenant",1073741824L,1,1099511627776L);
    }
    private void range(String key,long fallback,long min,long max){long value=env.getProperty(key,Long.class,fallback);if(value<min||value>max)throw new IllegalStateException("Invalid Knowledge capacity configuration: "+key);}
}

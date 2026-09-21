package com.spaceagent.platform.integration.infrastructure.persistence;
import com.spaceagent.platform.integration.domain.ProjectStartRequestRepository;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Repository;
import java.time.Instant;
import java.util.*;
@Repository @ConditionalOnProperty(prefix="platform",name="persistence",havingValue="memory",matchIfMissing=true)
public class InMemoryProjectStartRequestRepository implements ProjectStartRequestRepository {
    private final Map<String,Request> rows=new HashMap<>();
    private final Map<String,String> keys=new HashMap<>();
    public synchronized Optional<Request> find(String t,String u,String k,String h){return Optional.ofNullable(rows.get(keys.get(t+":"+u+":"+k+":"+h)));}
    public synchronized Optional<Request> pendingRoot(String t,String u,String h){return rows.values().stream().filter(r->r.tenantId().equals(t)&&r.ownerId().equals(u)&&r.kind().equals("GITHUB_ROOT")&&r.requestHash().equals(h)&&r.state().equals("PENDING")).findFirst();}
    public synchronized boolean insert(Request r){if(find(r.tenantId(),r.ownerId(),r.kind(),r.keyHash()).isPresent()||r.kind().equals("GITHUB_ROOT")&&pendingRoot(r.tenantId(),r.ownerId(),r.requestHash()).isPresent())return false;rows.put(r.id(),r);alias(r,r.keyHash());return true;}
    public synchronized boolean alias(Request r,String key){return keys.putIfAbsent(r.tenantId()+":"+r.ownerId()+":"+r.kind()+":"+key,r.id())==null;}
    public synchronized void bindProject(String id,String project,Instant at){var r=rows.get(id);rows.put(id,new Request(r.id(),r.tenantId(),r.ownerId(),r.kind(),r.keyHash(),r.requestHash(),r.state(),project,r.result(),r.createdAt(),at));}
    public synchronized void complete(String id,String result,Instant at){var r=rows.get(id);rows.put(id,new Request(r.id(),r.tenantId(),r.ownerId(),r.kind(),r.keyHash(),r.requestHash(),"COMPLETED",r.projectId(),result,r.createdAt(),at));}
}

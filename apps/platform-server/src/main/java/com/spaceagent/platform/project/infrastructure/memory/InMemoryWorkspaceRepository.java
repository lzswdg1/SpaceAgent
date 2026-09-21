package com.spaceagent.platform.project.infrastructure.memory;
import com.spaceagent.platform.project.domain.*;import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;import org.springframework.stereotype.Repository;import java.time.Instant;import java.util.*;import java.util.concurrent.ConcurrentHashMap;import java.util.concurrent.atomic.AtomicBoolean;
@Repository @ConditionalOnProperty(prefix="platform",name="persistence",havingValue="memory",matchIfMissing=true)
public class InMemoryWorkspaceRepository implements WorkspaceRepository{
 private final Map<String,Workspace> values=new ConcurrentHashMap<>();public void save(Workspace v){values.put(v.id(),v);}public Optional<Workspace> findById(String id){return Optional.ofNullable(values.get(id));}
 public List<Workspace> findByProjectId(String id){return values.values().stream().filter(v->v.projectId().equals(id)).sorted(Comparator.comparing(Workspace::createdAt)).toList();}
 public boolean hasActiveWritable(String task,String source,String isolationKey){return values.values().stream().anyMatch(v->v.taskId().equals(task)&&v.sourceRepositoryId().equals(source)&&v.isolationKey().equals(isolationKey)&&v.writable()&&!List.of(WorkspaceState.ARCHIVED,WorkspaceState.CLEANED_UP,WorkspaceState.FAILED).contains(v.state()));}
 public List<Workspace> findStaleProvisioning(Instant before,int limit){return values.values().stream().filter(v->v.state()==WorkspaceState.PROVISIONING&&!v.updatedAt().isAfter(before)).sorted(Comparator.comparing(Workspace::updatedAt)).limit(limit).toList();}
 public boolean failProvisioning(String id,long revision,String reason,Instant at){AtomicBoolean changed=new AtomicBoolean();values.computeIfPresent(id,(key,current)->{if(current.state()!=WorkspaceState.PROVISIONING||current.revision()!=revision)return current;changed.set(true);return current.fail(reason,at);});return changed.get();}
}

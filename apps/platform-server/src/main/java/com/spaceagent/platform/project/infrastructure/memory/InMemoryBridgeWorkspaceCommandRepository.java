package com.spaceagent.platform.project.infrastructure.memory;
import com.spaceagent.platform.project.domain.*;import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;import org.springframework.stereotype.Repository;import java.time.Instant;import java.util.*;import java.util.concurrent.ConcurrentHashMap;
@Repository @ConditionalOnProperty(prefix="platform",name="persistence",havingValue="memory",matchIfMissing=true)
public class InMemoryBridgeWorkspaceCommandRepository implements BridgeWorkspaceCommandRepository{
 private final Map<String,BridgeWorkspaceCommand> values=new ConcurrentHashMap<>();public void save(BridgeWorkspaceCommand v){values.put(v.id(),v);}public Optional<BridgeWorkspaceCommand> findById(String id){return Optional.ofNullable(values.get(id));}
 public List<BridgeWorkspaceCommand> findPendingByBridgeId(String id){return values.values().stream().filter(v->v.bridgeId().equals(id)&&v.state()==BridgeWorkspaceCommandState.PENDING).toList();}
 public boolean complete(String id,BridgeWorkspaceCommandState s,Instant at){BridgeWorkspaceCommand v=values.get(id);if(v==null||v.state()!=BridgeWorkspaceCommandState.PENDING)return false;values.put(id,new BridgeWorkspaceCommand(v.id(),v.workspaceId(),v.bridgeId(),v.rootHandle(),v.baseRef(),v.branchName(),v.worktreeKey(),s,v.createdAt(),at));return true;}
}

package com.spaceagent.platform.knowledge.infrastructure.memory;

import com.spaceagent.platform.knowledge.domain.*;
import com.spaceagent.shared.time.TimeProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Repository;
import java.time.Instant;
import java.util.*;

@Repository
@ConditionalOnProperty(prefix="platform", name="persistence", havingValue="memory", matchIfMissing=true)
public class InMemoryDocumentWorkspaceRepository implements DocumentWorkspaceRepository {
    private final Map<String,DocumentWorkspace> workspaces=new HashMap<>();
    private final Map<String,String> createKeys=new HashMap<>();
    private final Map<String,DocumentWorkspaceOperation> operations=new HashMap<>();
    private final Map<String,FileState> files=new HashMap<>();
    private final TimeProvider time;
    public InMemoryDocumentWorkspaceRepository(TimeProvider time){this.time=time;}
    public Instant currentTime(){return time.now();}
    public synchronized void insert(DocumentWorkspace w,String key){String k=createKey(w.tenantId(),w.ownerId(),key);if(workspaces.putIfAbsent(w.id(),w)!=null||createKeys.putIfAbsent(k,w.id())!=null)throw new IllegalStateException("Document Workspace exists");}
    public synchronized Optional<DocumentWorkspace> findByCreateKey(String t,String o,String k){return Optional.ofNullable(createKeys.get(createKey(t,o,k))).map(workspaces::get);}
    public synchronized Optional<DocumentWorkspace> findById(String t,String id){return Optional.ofNullable(workspaces.get(id)).filter(v->v.tenantId().equals(t));}
    public synchronized List<DocumentWorkspace> list(String t){return workspaces.values().stream().filter(v->v.tenantId().equals(t)).sorted(Comparator.comparing(DocumentWorkspace::createdAt)).toList();}
    public synchronized Optional<DocumentWorkspace> update(DocumentWorkspace v,long r){var c=workspaces.get(v.id());if(c==null||c.revision()!=r||v.revision()!=r+1)return Optional.empty();workspaces.put(v.id(),v);return Optional.of(v);}
    public synchronized MutationClaim claimMutation(MutationRequest r){DocumentWorkspace w=findById(r.tenantId(),r.workspaceId()).orElseThrow();var same=operations.values().stream().filter(v->v.workspaceId().equals(r.workspaceId())&&(v.toolCallId().equals(r.toolCallId())||v.idempotencyKey().equals(r.idempotencyKey()))).findFirst();if(same.isPresent())return existing(same.get(),r,w);boolean blocked=operations.values().stream().anyMatch(v->v.workspaceId().equals(r.workspaceId())&&v.path().equals(r.path())&&(v.state()==DocumentWorkspaceOperation.State.PENDING||v.state()==DocumentWorkspaceOperation.State.UNKNOWN));if(blocked)return new MutationClaim(ClaimType.BUSY,w,null);FileState previous=files.get(fileKey(r.workspaceId(),r.path()));long previousBytes=previous==null?0:previous.bytes;long reserveFiles=r.type()==DocumentWorkspaceOperation.Type.WRITE&&previous==null?1:0;long reserveBytes=r.type()==DocumentWorkspaceOperation.Type.WRITE?Math.max(0,r.requestedBytes()-previousBytes):0;DocumentWorkspace reserved=r.type()==DocumentWorkspaceOperation.Type.WRITE?w.reserve(reserveFiles,reserveBytes,r.at()):w;var op=new DocumentWorkspaceOperation(r.operationId(),r.tenantId(),r.workspaceId(),r.actorUserId(),r.agentRunId(),r.runStepId(),r.toolCallId(),r.idempotencyKey(),r.inputHash(),r.type(),r.path(),r.requestedBytes(),previousBytes,previous!=null,reserveFiles,reserveBytes,DocumentWorkspaceOperation.State.PENDING,null,null,null,1,r.at(),r.at(),null);workspaces.put(w.id(),reserved);operations.put(op.id(),op);return new MutationClaim(ClaimType.CLAIMED,reserved,op);}
    public synchronized Optional<MutationResult> completeMutation(String t,String id,long bytes,String hash,Instant at){return transition(t,id,at,(w,o)->{if(o.type()==DocumentWorkspaceOperation.Type.WRITE){if(bytes!=o.requestedBytes())return null;long shrink=Math.max(0,o.previousBytes()-bytes);DocumentWorkspace next=shrink==0?w:w.release(0,shrink,at);files.put(fileKey(o.workspaceId(),o.path()),new FileState(bytes,hash));return result(next,copy(o,DocumentWorkspaceOperation.State.SUCCEEDED,bytes,hash,null,at));}files.remove(fileKey(o.workspaceId(),o.path()));DocumentWorkspace next=o.previousFilePresent()?w.release(1,o.previousBytes(),at):w;return result(next,copy(o,DocumentWorkspaceOperation.State.SUCCEEDED,0L,hash,null,at));});}
    public synchronized Optional<MutationResult> failMutation(String t,String id,String code,Instant at){return transition(t,id,at,(w,o)->result(releaseReservation(w,o,at),copy(o,DocumentWorkspaceOperation.State.FAILED,null,null,code,at)));}
    public synchronized Optional<MutationResult> markMutationUnknown(String t,String id,String code,Instant at){return transition(t,id,at,(w,o)->result(w,copy(o,DocumentWorkspaceOperation.State.UNKNOWN,null,null,code,at)));}
    public synchronized Optional<DocumentWorkspaceOperation> findMutation(String t,String run,String call){return operations.values().stream().filter(v->v.tenantId().equals(t)&&v.agentRunId().equals(run)&&v.toolCallId().equals(call)).findFirst();}
    private Optional<MutationResult> transition(String t,String id,Instant at,Transition fn){var o=operations.get(id);if(o==null||!o.tenantId().equals(t))return Optional.empty();if(o.state()!=DocumentWorkspaceOperation.State.PENDING)return Optional.of(new MutationResult(workspaces.get(o.workspaceId()),o));var result=fn.apply(workspaces.get(o.workspaceId()),o);if(result==null)return Optional.empty();workspaces.put(result.workspace().id(),result.workspace());operations.put(id,result.operation());return Optional.of(result);}
    private static MutationClaim existing(DocumentWorkspaceOperation o,MutationRequest r,DocumentWorkspace w){if(!o.sameRequest(r.toolCallId(),r.idempotencyKey(),r.inputHash(),r.type(),r.path()))return new MutationClaim(ClaimType.CONFLICT,w,o);return new MutationClaim(switch(o.state()){case SUCCEEDED->ClaimType.REPLAY;case UNKNOWN->ClaimType.UNKNOWN;case FAILED->ClaimType.FAILED;case PENDING->ClaimType.BUSY;},w,o);}
    private static DocumentWorkspace releaseReservation(DocumentWorkspace w,DocumentWorkspaceOperation o,Instant at){return o.type()!=DocumentWorkspaceOperation.Type.WRITE||(o.reservedFiles()==0&&o.reservedBytes()==0)?w:w.release(o.reservedFiles(),o.reservedBytes(),at);}
    private static MutationResult result(DocumentWorkspace w,DocumentWorkspaceOperation o){return new MutationResult(w,o);}
    private static DocumentWorkspaceOperation copy(DocumentWorkspaceOperation o,DocumentWorkspaceOperation.State s,Long bytes,String hash,String code,Instant at){return new DocumentWorkspaceOperation(o.id(),o.tenantId(),o.workspaceId(),o.actorUserId(),o.agentRunId(),o.runStepId(),o.toolCallId(),o.idempotencyKey(),o.inputHash(),o.type(),o.path(),o.requestedBytes(),o.previousBytes(),o.previousFilePresent(),o.reservedFiles(),o.reservedBytes(),s,bytes,hash,code,o.revision()+1,o.createdAt(),at,s==DocumentWorkspaceOperation.State.SUCCEEDED||s==DocumentWorkspaceOperation.State.FAILED?at:null);}
    private static String createKey(String t,String o,String k){return t+"\0"+o+"\0"+k;}
    private static String fileKey(String w,String p){return w+"\0"+p;}
    private record FileState(long bytes,String hash){}
    private interface Transition{MutationResult apply(DocumentWorkspace w,DocumentWorkspaceOperation o);}
}

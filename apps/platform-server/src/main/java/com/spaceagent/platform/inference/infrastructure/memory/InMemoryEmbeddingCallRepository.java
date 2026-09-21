package com.spaceagent.platform.inference.infrastructure.memory;

import com.spaceagent.platform.inference.domain.*;
import com.spaceagent.shared.time.TimeProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Repository;
import java.util.*;

@Repository
@ConditionalOnProperty(prefix="platform",name="persistence",havingValue="memory",matchIfMissing=true)
public class InMemoryEmbeddingCallRepository implements EmbeddingCallRepository {
    private final Map<String,EmbeddingCall> calls=new HashMap<>();
    private final TimeProvider time;
    private final Set<String> erased=new HashSet<>();
    public InMemoryEmbeddingCallRepository(TimeProvider time) { this.time=time; }
    public synchronized Decision claim(EmbeddingCall c) {
        var existing=calls.values().stream().filter(v->v.tenantId().equals(c.tenantId()) && v.actorId().equals(c.actorId())
                && v.operationKey().equals(c.operationKey())).findFirst();
        if(existing.isPresent()) {
            if(!existing.get().requestHash().equals(c.requestHash())) throw new IllegalStateException("Embedding idempotency conflict");
            return new Decision(find(existing.get().id()).orElseThrow(),false);
        }
        calls.put(c.id(),c); return new Decision(c,true);
    }
    public synchronized Optional<EmbeddingCall> find(String id) {
        var c=calls.get(id);
        if(c!=null && c.state()==EmbeddingCall.State.PREPARED && isErased(c))finish(id,c.state(),EmbeddingCall.State.REJECTED,null,null,null,"EMBEDDING_OUTPUT_ERASED");
        c=calls.get(id);
        if(c!=null && (c.state()==EmbeddingCall.State.PREPARED || c.state()==EmbeddingCall.State.DISPATCHED) && !c.deadline().isAfter(time.now())) {
            finish(id,c.state(),c.state()==EmbeddingCall.State.PREPARED?EmbeddingCall.State.REJECTED:EmbeddingCall.State.UNKNOWN,null,null,null,
                    c.state()==EmbeddingCall.State.PREPARED?"EMBEDDING_DISPATCH_CANCELLED":"EMBEDDING_EXECUTION_EXPIRED");
        }
        return Optional.ofNullable(calls.get(id));
    }
    public synchronized boolean dispatch(String id) {
        return find(id).filter(c->c.state()==EmbeddingCall.State.PREPARED)
                .map(c->finish(id,c.state(),EmbeddingCall.State.DISPATCHED,null,null,null,null)).orElse(false);
    }
    public synchronized boolean finish(String id,EmbeddingCall.State expected,EmbeddingCall.State state,String encrypted,Long tokens,Long cost,String code) {
        var c=calls.get(id); if(c==null || c.state()!=expected) return false;
        calls.put(id,new EmbeddingCall(c.id(),c.tenantId(),c.actorId(),c.operationKey(),c.requestHash(),c.providerId(),c.modelId(),c.dimensions(),
                c.priceId(),c.inputRate(),state,isErased(c)?null:encrypted,tokens,cost,code,c.deadline(),c.createdAt())); return true;
    }
    public synchronized void eraseOutputs(String tenant,String actor,String prefix){erased.add(tenant+"\n"+actor+"\n"+prefix);for(var c:new ArrayList<>(calls.values()))if(isErased(c))finish(c.id(),c.state(),c.state(),null,c.inputTokens(),c.costMicros(),c.safeCode());}
    private boolean isErased(EmbeddingCall c){return erased.stream().anyMatch(p->(c.tenantId()+"\n"+c.actorId()+"\n"+c.operationKey()).startsWith(p));}
}

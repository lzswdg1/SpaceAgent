package com.spaceagent.platform.knowledge.infrastructure.memory;

import com.spaceagent.platform.knowledge.domain.KnowledgeIndexObjectStore;
import com.spaceagent.platform.knowledge.infrastructure.FileSystemKnowledgeIndexObjectStore;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import java.util.concurrent.ConcurrentHashMap;

@Component
@ConditionalOnProperty(prefix="platform",name="persistence",havingValue="memory",matchIfMissing=true)
public class InMemoryKnowledgeIndexObjectStore implements KnowledgeIndexObjectStore {
    private final ConcurrentHashMap<String,byte[]> values=new ConcurrentHashMap<>();
    @Override public boolean deleteOwner(String owner,int limit) {values.keySet().stream().filter(k->k.startsWith("knowledge-index/"+owner+"/")).limit(limit).toList().forEach(values::remove);return values.keySet().stream().noneMatch(k->k.startsWith("knowledge-index/"+owner+"/"));}
    @Override public void deleteReference(String ref) {values.remove(ref);}
    public String put(String job,String hash,byte[] bytes) {
        if(job==null || !job.matches("[A-Za-z0-9_.:-]{1,36}") || bytes==null || bytes.length>8_000_000
                || !FileSystemKnowledgeIndexObjectStore.hash(bytes).equals(hash)) throw new IllegalArgumentException("Invalid index object");
        String ref="knowledge-index/"+job+"/"+hash; values.putIfAbsent(ref,bytes.clone()); return ref;
    }
    public byte[] read(String reference,String hash) {
        byte[] value=values.get(reference);
        if(value==null || !FileSystemKnowledgeIndexObjectStore.hash(value).equals(hash)) throw new IllegalStateException("Index object unavailable");
        return value.clone();
    }
}

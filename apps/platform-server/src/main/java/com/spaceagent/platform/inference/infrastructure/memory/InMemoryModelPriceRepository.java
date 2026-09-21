package com.spaceagent.platform.inference.infrastructure.memory;

import com.spaceagent.platform.inference.domain.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Repository
@ConditionalOnProperty(prefix="platform",name="persistence",havingValue="memory",matchIfMissing=true)
public class InMemoryModelPriceRepository implements ModelPriceRepository {
    private final Map<String,ModelPrice> values=new ConcurrentHashMap<>();
    public synchronized void save(ModelPrice v){values.put(v.id(),v);}
    public synchronized int nextVersion(String id){return values.values().stream().filter(v->v.providerModelId().equals(id)).mapToInt(ModelPrice::version).max().orElse(0)+1;}
    public Optional<ModelPrice> findEffective(String id,Instant at){return values.values().stream().filter(v->v.providerModelId().equals(id)&&!v.effectiveFrom().isAfter(at)&&(v.effectiveUntil()==null||v.effectiveUntil().isAfter(at))).max(Comparator.comparing(ModelPrice::version));}
    public List<ModelPrice> findEffectiveByProviderModelIds(List<String> ids,Instant at){Set<String> selected=Set.copyOf(ids);return selected.stream().map(id->findEffective(id,at).orElse(null)).filter(Objects::nonNull).toList();}
    public List<ModelPrice> findByProviderModelId(String id){return values.values().stream().filter(v->v.providerModelId().equals(id)).sorted(Comparator.comparing(ModelPrice::version)).toList();}
    public synchronized boolean overlaps(String id,Instant from,Instant until){return values.values().stream().filter(v->v.providerModelId().equals(id)).anyMatch(v->(v.effectiveUntil()==null||v.effectiveUntil().isAfter(from))&&(until==null||until.isAfter(v.effectiveFrom())));}
}

package com.spaceagent.platform.knowledge.infrastructure.memory;

import com.spaceagent.platform.knowledge.domain.KnowledgeBase;
import com.spaceagent.platform.knowledge.domain.KnowledgeBaseGrant;
import com.spaceagent.platform.knowledge.domain.KnowledgeBaseRepository;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Repository;
import java.util.*;

@Repository
@ConditionalOnProperty(prefix = "platform", name = "persistence", havingValue = "memory", matchIfMissing = true)
public class InMemoryKnowledgeBaseRepository implements KnowledgeBaseRepository {
    private final Map<String, KnowledgeBase> bases = new HashMap<>();
    private final Map<GrantKey, KnowledgeBaseGrant> grants = new HashMap<>();
    private record GrantKey(String baseId, KnowledgeBaseGrant.SubjectType type, String subjectId) {}

    @Override public synchronized Optional<KnowledgeBase> find(String id) { return Optional.ofNullable(bases.get(id)); }
    @Override public synchronized Optional<KnowledgeBase> lock(String id) { return find(id); }
    @Override public synchronized void insert(KnowledgeBase base) {
        if (bases.putIfAbsent(base.id(), base) != null) throw new IllegalStateException("Duplicate knowledge base");
    }
    @Override public synchronized KnowledgeBase insertIfAbsent(KnowledgeBase base) {
        return bases.computeIfAbsent(base.id(), ignored -> base);
    }
    @Override public synchronized boolean update(KnowledgeBase base, long expected) {
        var old = bases.get(base.id());
        if (old == null || old.revision() != expected) return false;
        bases.put(base.id(), base);
        return true;
    }
    @Override public synchronized List<KnowledgeBase> listVisible(String actor, String organization, String role,
                                                                   int offset, int limit) {
        return bases.values().stream().filter(base -> base.state() == KnowledgeBase.State.ACTIVE)
                .filter(base -> base.scope() == KnowledgeBase.Scope.PERSONAL
                        ? Objects.equals(actor, base.ownerId())
                        : organization != null && organization.equals(base.organizationId()) && role != null
                          && ("OWNER".equals(role) || Objects.equals(actor, base.ownerId())
                              || grants(base.id()).stream().anyMatch(grant ->
                                  grant.subjectType() == KnowledgeBaseGrant.SubjectType.USER
                                      ? actor.equals(grant.subjectId()) : role.equals(grant.subjectId()))))
                .sorted(Comparator.comparing(KnowledgeBase::createdAt).reversed().thenComparing(KnowledgeBase::id))
                .skip(offset).limit(limit).toList();
    }
    @Override public synchronized List<KnowledgeBaseGrant> grants(String baseId) {
        return grants.values().stream().filter(grant -> grant.baseId().equals(baseId))
                .sorted(Comparator.comparing((KnowledgeBaseGrant grant) -> grant.subjectType().name())
                        .thenComparing(KnowledgeBaseGrant::subjectId)).toList();
    }
    @Override public synchronized void putGrant(KnowledgeBaseGrant grant) {
        grants.put(new GrantKey(grant.baseId(), grant.subjectType(), grant.subjectId()), grant);
    }
    @Override public synchronized void removeGrant(String base, KnowledgeBaseGrant.SubjectType type, String subject) {
        grants.remove(new GrantKey(base, type, subject));
    }
}

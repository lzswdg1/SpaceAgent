package com.spaceagent.platform.knowledge.api;

import com.spaceagent.platform.knowledge.domain.KnowledgeBase;
import com.spaceagent.platform.knowledge.domain.KnowledgeBaseGrant;
import java.util.List;

public interface KnowledgeBaseApplicationApi {
    BaseView create(CreateCommand command);
    BaseView get(Actor actor, String baseId);
    Page list(Actor actor, int offset, int limit);
    BaseView update(UpdateCommand command);
    void archive(Actor actor, String baseId, long expectedRevision);
    List<KnowledgeBaseGrant> grants(Actor actor, String baseId);
    BaseView grant(GrantCommand command);
    BaseView revoke(RevokeCommand command);

    record Actor(String userId, String organizationId) {}
    record BaseView(KnowledgeBase base, KnowledgeBase.Permission permission) {}
    record Page(List<BaseView> items, int offset, int limit, boolean hasMore) {
        public Page { items = List.copyOf(items); }
    }
    record CreateCommand(Actor actor, KnowledgeBase.Scope scope, String name, String description) {}
    record UpdateCommand(Actor actor, String baseId, String name, String description, long expectedRevision) {}
    record GrantCommand(Actor actor, String baseId, KnowledgeBaseGrant.SubjectType subjectType,
                        String subjectId, KnowledgeBase.Permission permission, long expectedRevision) {}
    record RevokeCommand(Actor actor, String baseId, KnowledgeBaseGrant.SubjectType subjectType,
                         String subjectId, long expectedRevision) {}
}

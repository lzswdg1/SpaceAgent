package com.spaceagent.platform.knowledge.api;

import com.spaceagent.platform.knowledge.domain.KnowledgeBase;

/** Re-evaluates current membership and grants; Agent bindings never confer authority. */
public interface KnowledgeAccessApplicationApi {
    java.util.Optional<KnowledgeBaseApplicationApi.BaseView> findAccessible(KnowledgeBaseApplicationApi.Actor actor,
                                                                          String baseId, KnowledgeBase.Permission required);
    KnowledgeBaseApplicationApi.BaseView authorize(KnowledgeBaseApplicationApi.Actor actor,
                                                   String baseId, KnowledgeBase.Permission required);
}

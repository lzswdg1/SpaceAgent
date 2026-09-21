package com.spaceagent.platform.knowledge.api;

import java.util.List;

public record KnowledgeRetrievalView(
        String query,
        List<KnowledgeRetrievalMatchView> matches,boolean partial,boolean degraded,List<String> warnings,java.util.Map<String,Long> stageMillis) {
    public KnowledgeRetrievalView(String query,List<KnowledgeRetrievalMatchView> matches){this(query,matches,false,false,List.of(),java.util.Map.of());}

    public KnowledgeRetrievalView {
        matches = matches == null ? List.of() : List.copyOf(matches);
        warnings=warnings==null?List.of():List.copyOf(warnings);stageMillis=stageMillis==null?java.util.Map.of():java.util.Map.copyOf(stageMillis);
    }
}

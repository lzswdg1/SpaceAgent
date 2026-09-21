package com.spaceagent.platform.knowledge.api;

import java.util.List;

public record KnowledgeProcessingView(
        KnowledgeDocumentView document,
        List<KnowledgeChunkView> chunks) {

    public KnowledgeProcessingView {
        chunks = chunks == null ? List.of() : List.copyOf(chunks);
    }
}

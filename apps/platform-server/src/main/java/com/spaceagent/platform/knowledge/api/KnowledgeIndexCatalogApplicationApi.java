package com.spaceagent.platform.knowledge.api;

import com.spaceagent.platform.knowledge.domain.*;
import java.util.List;

public interface KnowledgeIndexCatalogApplicationApi {
    SpaceView registerSpace(RegisterSpaceCommand command);
    List<SpaceView> spaces(KnowledgeBaseApplicationApi.Actor actor, String baseId, int offset, int limit);
    List<KnowledgeDocumentScope> documents(KnowledgeBaseApplicationApi.Actor actor, String baseId, int offset, int limit);
    List<GenerationView> generations(KnowledgeBaseApplicationApi.Actor actor, String baseId, String documentId, int offset, int limit);
    /** Internal input from a parser/indexing application, not a public readiness or publication command. */
    GenerationView stage(StageGenerationCommand command);

    record RegisterSpaceCommand(KnowledgeBaseApplicationApi.Actor actor, String baseId, String providerId,
                               String modelId, String modelRevision, int dimensions, String preprocessingHash) {}
    record StageGenerationCommand(KnowledgeBaseApplicationApi.Actor actor, String baseId, String documentId,
                                  String spaceId, String contentHash, String parserFingerprint, String chunkingFingerprint,long documentRevision) {
        public StageGenerationCommand(KnowledgeBaseApplicationApi.Actor actor,String baseId,String documentId,String spaceId,
                String contentHash,String parserFingerprint,String chunkingFingerprint) {
            this(actor,baseId,documentId,spaceId,contentHash,parserFingerprint,chunkingFingerprint,0);
        }
    }
    record SpaceView(KnowledgeEmbeddingSpace space, String status, String distance) {}
    record GenerationView(KnowledgeIndexGeneration generation, String status, boolean searchable) {}
}

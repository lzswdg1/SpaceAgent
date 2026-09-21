package com.spaceagent.platform.knowledge.domain;

import java.util.List;
import java.util.Optional;

/** PostgreSQL publication boundary. Vector storage never decides which document generation is active. */
public interface KnowledgeIndexPublicationRepository {
    Source registerSource(String generationId,long expectedDocumentRevision,String sourceReference);
    default Source registerSource(String generationId,long revision,String reference,String media,String charset){return registerSource(generationId,revision,reference);}
    default Source registerSource(String generationId,long revision,String reference,String media,String charset,KnowledgeProcessingPolicy policy){return registerSource(generationId,revision,reference,media,charset);}
    void saveParsingMetadata(KnowledgeIndexJob.Lease lease,String reference,String hash);
    Optional<Source> source(String generationId);
    Optional<Head> head(String baseId,String documentId);
    void saveChunks(KnowledgeIndexJob.Lease lease,List<Chunk> chunks);
    List<Chunk> chunks(String generationId);
    Head activate(KnowledgeIndexJob.Lease lease);
    Head invalidate(String baseId,String documentId,long expectedRevision);

    record Source(String generationId,String baseId,String documentId,long documentRevision,
                  String contentHash,String sourceReference,String mediaType,String charset,String parseMetadataReference,String parseMetadataHash,KnowledgeProcessingPolicy policy) {}
    record Head(String baseId,String documentId,long revision,String state,String contentHash,String activeGenerationId) {}
    record Chunk(String id,int ordinal,String content,String contentHash,java.util.Map<String,Object> metadata) {
        public Chunk(String id,int ordinal,String content,String hash){this(id,ordinal,content,hash,java.util.Map.of());}
        public Chunk {
            metadata=java.util.Map.copyOf(metadata);
            if(id==null || !id.matches("[A-Za-z0-9_:.-]{1,36}") || ordinal<0 || ordinal>=32768
                    || content==null || content.isBlank() || content.length()>8192
                    || contentHash==null || !contentHash.matches("[a-f0-9]{64}")) throw new IllegalArgumentException("Invalid indexed chunk");
        }
        @Override public String toString() {return "IndexChunk["+id+", content redacted]";}
    }
}

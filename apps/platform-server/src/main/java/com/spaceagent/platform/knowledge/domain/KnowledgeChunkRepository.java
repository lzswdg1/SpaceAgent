package com.spaceagent.platform.knowledge.domain;

import java.util.List;

/**
 * Domain port for knowledge chunk persistence.
 */
public interface KnowledgeChunkRepository {
    List<KnowledgeChunk> findByDocumentId(String documentId);

    List<KnowledgeChunk> findByDocumentIds(List<String> documentIds);

    default List<SimilarityMatch> findNearestByDocumentIds(
            List<String> documentIds,
            List<Double> queryVector,
            int topK) {
        if (documentIds == null || documentIds.isEmpty() || queryVector == null
                || queryVector.isEmpty() || topK < 1) {
            return List.of();
        }
        java.util.PriorityQueue<SimilarityMatch> best = new java.util.PriorityQueue<>(
                java.util.Comparator.comparingDouble(SimilarityMatch::score));
        for (KnowledgeChunk chunk : findByDocumentIds(documentIds)) {
            if (chunk.embedding().isEmpty()) continue;
            double score = cosine(queryVector, chunk.embedding());
            if (best.size() < topK || score > best.peek().score()) {
                best.add(new SimilarityMatch(
                        chunk.documentId(), chunk.id(), chunk.sequence(), chunk.content(),
                        score, chunk.embeddingModel()));
                if (best.size() > topK) best.poll();
            }
        }
        return best.stream()
                .sorted(java.util.Comparator.comparingDouble(SimilarityMatch::score).reversed())
                .toList();
    }

    void save(KnowledgeChunk chunk);

    void deleteByDocumentId(String documentId);

    record SimilarityMatch(
            String documentId,
            String chunkId,
            int sequence,
            String content,
            double score,
            String embeddingModel) {
    }

    private static double cosine(List<Double> left, List<Double> right) {
        if (left.size() != right.size() || left.isEmpty()) return 0.0;
        double dot = 0;
        double leftNorm = 0;
        double rightNorm = 0;
        for (int index = 0; index < left.size(); index++) {
            dot += left.get(index) * right.get(index);
            leftNorm += left.get(index) * left.get(index);
            rightNorm += right.get(index) * right.get(index);
        }
        return leftNorm == 0 || rightNorm == 0
                ? 0.0 : dot / (Math.sqrt(leftNorm) * Math.sqrt(rightNorm));
    }
}

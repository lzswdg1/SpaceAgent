package com.spaceagent.platform.knowledge.application;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

@Service
public class KnowledgeChunkingService {

    private final int configuredChunkSize;
    private final int configuredOverlap;

    public KnowledgeChunkingService(
            @Value("${platform.knowledge.chunk-size:800}") int configuredChunkSize,
            @Value("${platform.knowledge.chunk-overlap:100}") int configuredOverlap) {
        this.configuredChunkSize = configuredChunkSize;
        this.configuredOverlap = configuredOverlap;
    }

    public List<String> chunk(String content) {
        String normalized = content == null ? "" : content.trim();
        if (normalized.isEmpty()) {
            return List.of();
        }
        int chunkSize = Math.max(100, configuredChunkSize);
        int overlap = Math.max(0, Math.min(configuredOverlap, chunkSize / 2));
        List<String> chunks = new ArrayList<>();
        int start = 0;
        while (start < normalized.length()) {
            int end = Math.min(normalized.length(), start + chunkSize);
            if (end < normalized.length() && Character.isHighSurrogate(normalized.charAt(end - 1))
                    && Character.isLowSurrogate(normalized.charAt(end))) end++;
            chunks.add(normalized.substring(start, end));
            if (end == normalized.length()) {
                break;
            }
            start = end - overlap;
            if (start > 0 && Character.isLowSurrogate(normalized.charAt(start))
                    && Character.isHighSurrogate(normalized.charAt(start - 1))) start--;
        }
        return chunks;
    }
}

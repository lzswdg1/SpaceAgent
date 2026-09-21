package com.spaceagent.platform.inference.domain;

/** Explicit compatible model contracts; unknown models do not receive dimensions blindly. */
public record EmbeddingModelCapabilities(int maximumItems, boolean dimensionsParameterSupported) {
    public static EmbeddingModelCapabilities forModel(String modelId) {
        if ("text-embedding-v3".equals(modelId) || "text-embedding-v4".equals(modelId))
            return new EmbeddingModelCapabilities(10, true);
        return new EmbeddingModelCapabilities(64, modelId != null && modelId.startsWith("text-embedding-3-"));
    }
}

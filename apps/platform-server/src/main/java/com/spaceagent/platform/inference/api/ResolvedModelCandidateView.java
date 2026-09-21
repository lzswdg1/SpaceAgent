package com.spaceagent.platform.inference.api;

public record ResolvedModelCandidateView(
        String memberId,
        String providerId,
        String providerModelId,
        String modelId,
        int priority,
        int weight,
        Integer healthLatencyMs,
        String priceId,
        Long inputMicrosPerMillionTokens,
        Long outputMicrosPerMillionTokens) {
    public ResolvedModelCandidateView(
            String memberId,String providerId,String providerModelId,String modelId,
            int priority,int weight){this(memberId,providerId,providerModelId,modelId,
            priority,weight,null,null,null,null);}
}

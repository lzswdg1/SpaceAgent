package com.spaceagent.platform.inference.domain;

import java.nio.charset.StandardCharsets;
import java.util.List;

/** Conservative UTF-8 byte budget, not a claim of tokenizer-exact counts for arbitrary providers. */
public final class EmbeddingBounds {
    private EmbeddingBounds() {}
    public static int inputUpperBound(String input) {
        if(input==null || input.isBlank() || input.length()>8192) throw new IllegalArgumentException("Invalid embedding input");
        int bytes=input.getBytes(StandardCharsets.UTF_8).length;
        if(bytes+8>8192) throw new IllegalArgumentException("Embedding item exceeds conservative token bound");
        return bytes+8;
    }
    public static int validate(List<String> inputs,int dimensions,int modelContext) {
        if(inputs==null || inputs.isEmpty() || inputs.size()>64 || dimensions<1 || dimensions>32768
                || (long)inputs.size()*dimensions>262144) throw new IllegalArgumentException("Embedding batch bounds exceeded");
        int total=0;
        for(String input:inputs) { int n=inputUpperBound(input); if(n>modelContext) throw new IllegalArgumentException("Model context bound exceeded"); total+=n; }
        if(total>32768) throw new IllegalArgumentException("Embedding batch token bound exceeded");
        return total;
    }
    public static void vectors(List<List<Double>> vectors,int count,int dimensions) {
        if(vectors==null || vectors.size()!=count) throw new IllegalArgumentException("Embedding count mismatch");
        for(var vector:vectors) {
            if(vector==null || vector.size()!=dimensions || vector.stream().anyMatch(v->v==null || !Double.isFinite(v) || !Float.isFinite(v.floatValue()))
                    || vector.stream().allMatch(v->v.floatValue()==0)) throw new IllegalArgumentException("Invalid embedding vector");
        }
    }
}

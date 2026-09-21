package com.spaceagent.platform.runtime.application;

final class RepositoryAnswerContinuation {
    private RepositoryAnswerContinuation() { }
    static boolean limited(String finishReason) {
        return "length".equalsIgnoreCase(finishReason) || "max_tokens".equalsIgnoreCase(finishReason);
    }
    static String suffix(String existing, String continuation) {
        for (int overlap = Math.min(4096, Math.min(existing.length(), continuation.length())); overlap >= 32; overlap--) {
            if (existing.regionMatches(existing.length() - overlap, continuation, 0, overlap)) {
                return continuation.substring(overlap);
            }
        }
        return continuation;
    }
}

package com.spaceagent.platform.inference.domain;

import java.util.Optional;

public interface EmbeddingCallRepository {
    Decision claim(EmbeddingCall candidate);
    Optional<EmbeddingCall> find(String id);
    void eraseOutputs(String tenantId,String actorId,String prefix);
    boolean dispatch(String id);
    boolean finish(String id,EmbeddingCall.State expected,EmbeddingCall.State state,String encryptedResponse,
                   Long inputTokens,Long costMicros,String safeCode);
    record Decision(EmbeddingCall call,boolean created) {}
}

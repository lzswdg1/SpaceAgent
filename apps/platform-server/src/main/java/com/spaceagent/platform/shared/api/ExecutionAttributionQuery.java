package com.spaceagent.platform.shared.api;
import java.util.Optional;
/** Read-only integration-resolved execution ownership; shared owns no business state. */
public interface ExecutionAttributionQuery {
    Optional<Scope> resolve(String runId);
    record Scope(String tenantId,String actorId){}
}

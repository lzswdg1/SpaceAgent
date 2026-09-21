package com.spaceagent.platform.runtime.domain;

import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;

/** Java-owned durable graph cursor boundary; TypeScript never owns this state. */
public record RuntimeGraphSession(
        String id, String tenantId, String ownerUserId, String agentRunId, String bundleHash,
        long cursorSequence, List<String> completedCommandIds, String pendingCommandId,
        String pendingInputHash, GraphSessionState state, long revision, Instant createdAt, Instant updatedAt) {
    public RuntimeGraphSession {
        text(id); text(tenantId); text(ownerUserId); text(agentRunId); hash(bundleHash);
        if (cursorSequence < 0 || revision <= 0) throw new IllegalArgumentException("cursor/revision");
        completedCommandIds = List.copyOf(Objects.requireNonNull(completedCommandIds));
        if (completedCommandIds.size() > 256 || new LinkedHashSet<>(completedCommandIds).size() != completedCommandIds.size()) throw new IllegalArgumentException("completed commands");
        completedCommandIds.forEach(RuntimeGraphSession::text); state=Objects.requireNonNull(state);
        Objects.requireNonNull(createdAt); Objects.requireNonNull(updatedAt);
        boolean pending = pendingCommandId != null || pendingInputHash != null;
        if ((state == GraphSessionState.WAITING_FOR_COMMAND) != pending) throw new IllegalArgumentException("pending command boundary");
        if (pending) { text(pendingCommandId); hash(pendingInputHash); if (completedCommandIds.contains(pendingCommandId)) throw new IllegalArgumentException("pending already completed"); }
        if ((state == GraphSessionState.BLOCKED || state == GraphSessionState.COMPLETED) && pending) throw new IllegalArgumentException("terminal pending command");
    }
    public static RuntimeGraphSession start(String id,String tenant,String owner,String run,String bundle,Instant now){return new RuntimeGraphSession(id,tenant,owner,run,bundle,0,List.of(),null,null,GraphSessionState.ACTIVE,1,now,now);}
    public RuntimeGraphSession propose(String commandId,String inputHash,Instant now){require(GraphSessionState.ACTIVE,"propose");text(commandId);hash(inputHash);if(completedCommandIds.contains(commandId))throw new IllegalArgumentException("command already completed");return copy(cursorSequence+1,completedCommandIds,commandId,inputHash,GraphSessionState.WAITING_FOR_COMMAND,now);}
    public RuntimeGraphSession confirm(String commandId,String inputHash,Instant now){require(GraphSessionState.WAITING_FOR_COMMAND,"confirm");if(!pendingCommandId.equals(commandId)||!pendingInputHash.equals(inputHash))throw new IllegalArgumentException("command boundary mismatch");var completed=new java.util.ArrayList<>(completedCommandIds);completed.add(commandId);return copy(cursorSequence,List.copyOf(completed),null,null,GraphSessionState.ACTIVE,now);}
    public RuntimeGraphSession blockUnknown(Instant now){if(state==GraphSessionState.BLOCKED)return this;if(state==GraphSessionState.COMPLETED)throw new IllegalStateException("completed graph");return copy(cursorSequence,completedCommandIds,null,null,GraphSessionState.BLOCKED,now);}
    public RuntimeGraphSession complete(Instant now){require(GraphSessionState.ACTIVE,"complete");return copy(cursorSequence,completedCommandIds,null,null,GraphSessionState.COMPLETED,now);}
    private RuntimeGraphSession copy(long seq,List<String> completed,String pending,String input,GraphSessionState next,Instant now){return new RuntimeGraphSession(id,tenantId,ownerUserId,agentRunId,bundleHash,seq,completed,pending,input,next,revision+1,createdAt,Objects.requireNonNull(now));}
    private void require(GraphSessionState expected,String action){if(state!=expected)throw new IllegalStateException("cannot "+action+" from "+state);} private static void text(String v){if(v==null||v.isBlank())throw new IllegalArgumentException("text");} private static void hash(String v){if(v==null||!v.matches("sha256:[0-9a-f]{64}"))throw new IllegalArgumentException("hash");}
}

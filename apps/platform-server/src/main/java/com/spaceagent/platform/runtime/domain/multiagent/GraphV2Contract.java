package com.spaceagent.platform.runtime.domain.multiagent;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Framework-independent graph/v2 boundary; Java will persist its cursor and command ledger in later units. */
public final class GraphV2Contract {
    public static final String VERSION = "graph/v2";
    private GraphV2Contract() {}
    public record Request(String contractVersion,String requestId,String graphSessionId,String agentRunId,String tenantId,String ownerUserId,String bundleHash,Cursor cursor,Limits limits){public Request{version(contractVersion); text(requestId);text(graphSessionId);text(agentRunId);text(tenantId);text(ownerUserId);hash(bundleHash);Objects.requireNonNull(cursor);Objects.requireNonNull(limits);}}
    public record Result(String contractVersion,String requestId,String graphSessionId,String bundleHash,Cursor nextCursor,Command command,boolean ephemeral){public Result{version(contractVersion);text(requestId);text(graphSessionId);hash(bundleHash);Objects.requireNonNull(nextCursor);Objects.requireNonNull(command);if(!ephemeral)throw new IllegalArgumentException("TypeScript graph state must be ephemeral");if(nextCursor.pendingCommandId()==null||!nextCursor.pendingCommandId().equals(command.commandId()))throw new IllegalArgumentException("result cursor must pin command");}}
    public record Cursor(int sequence,List<String> completedCommandIds,String pendingCommandId){public Cursor{if(sequence<0)throw new IllegalArgumentException("sequence");completedCommandIds=List.copyOf(Objects.requireNonNull(completedCommandIds));if(completedCommandIds.size()>256)throw new IllegalArgumentException("completed commands");completedCommandIds.forEach(GraphV2Contract::text);if(pendingCommandId!=null)text(pendingCommandId);}}
    public record Limits(int maxDepth,int maxAgents,int remainingTokenBudget){public Limits{if(maxDepth<1||maxDepth>32||maxAgents<1||maxAgents>32||remainingTokenBudget<0)throw new IllegalArgumentException("limits");}}
    public record Command(String commandId,Kind kind,String inputHash,Map<String,Object> payload){public Command{text(commandId);kind=Objects.requireNonNull(kind);hash(inputHash);payload=Map.copyOf(Objects.requireNonNull(payload));if(payload.size()>32)throw new IllegalArgumentException("payload");}}
    public enum Kind{MODEL_REQUESTED,TOOL_REQUESTED,DELEGATE_SUBTASK,HANDOFF_PROPOSED,REVIEW_REQUIRED,WAIT_FOR_APPROVAL,COMPLETED}
    private static void version(String value){if(!VERSION.equals(value))throw new IllegalArgumentException("version");} private static void text(String value){if(value==null||value.isBlank())throw new IllegalArgumentException("text");} private static void hash(String value){if(value==null||!value.matches("sha256:[0-9a-f]{64}"))throw new IllegalArgumentException("hash");}
}

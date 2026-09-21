package com.spaceagent.admin.platformclient;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;

/** Explicit redacted wire DTOs. Never expose arbitrary upstream JSON on this surface. */
public final class BusinessEvidenceWire {
    private BusinessEvidenceWire(){}
    public record AuditEntry(String id,String actorId,String tenantId,String actorKind,String method,String route,String resourceKind,String resourceId,String outcome,Integer httpStatus,Instant admittedAt,Instant observedAt){}
    public record AuditPage(List<AuditEntry> items,long total,String coverage){}
    public record Capability(String resource,String mode,List<String> commands,List<String> constraints){}
    public record Review(String id,String agentId,String tenantId,String agentOwnerId,String requestedBy,String closedBy,String state,long baseAgentRevision,Long appliedAgentRevision,long revision,Instant createdAt,Instant closedAt){}
    public record ReviewPage(List<Review> items,long total){}
    public record UsageCall(String kind,String id,String tenantId,String userId,String runId,String providerId,String modelOrTool,String status,Long inputTokens,Long outputTokens,BigDecimal costMicros,String currency,Long durationMs,Instant startedAt){}
    public record UsageSummary(String kind,long calls,Map<String,Long> states,Long knownInputTokens,Long knownOutputTokens,BigDecimal knownCostMicros,String currency,long missingUsageCalls,long missingCostCalls,long missingDurationCalls,Long knownDurationMs,long unattributedCallsInWindow,String coverage){}
    public record UsageHistory(List<UsageCall> items,long total){}
    public record ResourceObservations(long observations,BigDecimal knownCpuUsageNanos,Long maximumObservedMemoryBytes,
        BigDecimal knownNetworkRxBytes,BigDecimal knownNetworkTxBytes,BigDecimal latestObservedWorkspaceApparentBytes,
        long missingCpuObservations,long missingMemoryObservations,long missingNetworkObservations,long missingWorkspaceObservations,
        String coverage,Instant from,Instant to,Instant generatedAt){}
    public record UsageOverview(Instant from,Instant to,List<UsageSummary> summaries,String coverage,String userId,String organizationId,Instant generatedAt){
        public UsageOverview(Instant from,Instant to,List<UsageSummary> summaries,String coverage){this(from,to,summaries,coverage,null,null,to);}
    }
}

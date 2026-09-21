package com.spaceagent.platform.shared.api;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;

/** Common immutable wire vocabulary, without persistence or business authority. */
public final class AdministrationUsage {
    private AdministrationUsage(){}
    public record Filter(String userId,String tenantId,Instant from,Instant to,String status,int page,int pageSize){
        public Filter{
            if(from==null||to==null||!from.isBefore(to)||Duration.between(from,to).compareTo(Duration.ofDays(90))>0
                || page<0||page>10000||pageSize<1||pageSize>100||userId!=null&&!userId.matches("[A-Za-z0-9_.:-]{1,36}")
                ||tenantId!=null&&!tenantId.matches("[A-Za-z0-9_.:-]{1,36}")||status!=null&&!status.matches("[A-Z_]{1,32}"))throw new IllegalArgumentException("Invalid usage filter");
        }
    }
    public record Call(String kind,String id,String tenantId,String userId,String runId,String providerId,String modelOrTool,
                       String status,Long inputTokens,Long outputTokens,BigDecimal costMicros,String currency,Long durationMs,Instant startedAt){}
    public record Summary(String kind,long calls,Map<String,Long> states,Long knownInputTokens,Long knownOutputTokens,
                          BigDecimal knownCostMicros,String currency,long missingUsageCalls,long missingCostCalls,long missingDurationCalls,
                          Long knownDurationMs,long unattributedCallsInWindow,String coverage){public Summary{states=Map.copyOf(states);}}
    public record History(List<Call> items,long total){public History{items=List.copyOf(items);}}
}

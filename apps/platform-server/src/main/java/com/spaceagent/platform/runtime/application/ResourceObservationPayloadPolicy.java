package com.spaceagent.platform.runtime.application;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.Set;

/** No arbitrary text/paths/arguments enter operational resource evidence. */
final class ResourceObservationPayloadPolicy {
    private static final Set<String> ROOT=Set.of("executionId","toolCallId","workspaceId","resourceMetrics");
    private static final Set<String> METRICS=Set.of("cpuUsageNanos","maxObservedMemoryBytes","networkRxBytes","networkTxBytes","workspaceApparentBytes","coverage");
    static void validate(JsonNode payload) {
        if(!payload.isObject()||!id(payload.get("executionId"))||!id(payload.get("toolCallId")))throw invalid();
        payload.fieldNames().forEachRemaining(name->{if(!ROOT.contains(name))throw invalid();});
        if(payload.has("workspaceId")&&(!payload.get("workspaceId").isTextual()||!payload.get("workspaceId").asText().matches("[0-9a-fA-F-]{36}")))throw invalid();
        var metrics=payload.get("resourceMetrics");if(metrics==null||!metrics.isObject())throw invalid();
        metrics.fields().forEachRemaining(entry->{
            if(!METRICS.contains(entry.getKey()))throw invalid();
            var value=entry.getValue();
            if("coverage".equals(entry.getKey())){
                if(!value.isTextual()||!(value.asText().equals("UNAVAILABLE")||value.asText().equals(
                    "RUNNING_CGROUP_SAMPLES_AND_POST_EXECUTION_APPARENT_BYTES; NOT_FULL_CPU_OR_PEAK_RSS_OR_ALLOCATED_DISK")))throw invalid();
            }else if(!value.isNull()&&(!value.isIntegralNumber()||!value.canConvertToLong()||value.longValue()<0))throw invalid();
        });
    }
    private static boolean id(JsonNode node){return node!=null&&node.isTextual()&&node.asText().matches("[A-Za-z0-9_.:-]{1,120}");}
    private static IllegalArgumentException invalid(){return new IllegalArgumentException("Invalid redacted resource observation");}
}

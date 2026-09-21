package com.spaceagent.platform.integration.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.spaceagent.platform.runtime.api.*;
import com.spaceagent.platform.runtime.domain.RunEventType;
import com.spaceagent.platform.tooling.domain.*;
import org.springframework.stereotype.Component;
import java.util.*;

@Component
public class RuntimeSandboxResourceObservation implements SandboxResourceObservationPort {
    private final RuntimeApplicationApi runtime;
    private final ObjectMapper json;
    public RuntimeSandboxResourceObservation(RuntimeApplicationApi runtime, ObjectMapper json) { this.runtime=runtime;this.json=json; }
    @Override public void record(SandboxExecutionRequest request, SandboxResourceMetrics metrics) {
        if(runtime.findRun(request.agentRunId()).isEmpty()) return; // Intake/pre-Run compute is explicitly outside this coverage.
        Map<String,Object> payload=new LinkedHashMap<>();
        payload.put("executionId",request.executionId());payload.put("toolCallId",request.toolCallId());payload.put("resourceMetrics",metrics);
        String workspace=request.workspaceRef();
        if(workspace!=null&&workspace.matches("(?:workspaces|document-workspaces)/[0-9a-fA-F-]{36}"))
            payload.put("workspaceId",workspace.substring(workspace.lastIndexOf('/')+1));
        try {runtime.recordEvent(new RecordRunEventCommand(request.agentRunId(),RunEventType.RESOURCE_OBSERVED,json.writeValueAsString(payload)));}
        catch(com.fasterxml.jackson.core.JsonProcessingException e){throw new IllegalStateException("Resource observation serialization failed");}
    }
}

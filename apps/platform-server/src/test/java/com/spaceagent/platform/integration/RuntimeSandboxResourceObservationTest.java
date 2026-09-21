package com.spaceagent.platform.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.spaceagent.platform.integration.application.RuntimeSandboxResourceObservation;
import com.spaceagent.platform.runtime.api.*;
import com.spaceagent.platform.runtime.domain.RunEventType;
import com.spaceagent.platform.tooling.domain.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class RuntimeSandboxResourceObservationTest {
    @Test void trustedCountersAreBoundToOriginalRequestWithoutPayloadOrPaths() throws Exception {
        var runtime=mock(RuntimeApplicationApi.class);when(runtime.findRun("run")).thenReturn(Optional.of(mock(AgentRunView.class)));
        var json=new ObjectMapper();var sink=new RuntimeSandboxResourceObservation(runtime,json);
        var request=new SandboxExecutionRequest("exec","run","call","workspaces/00000000-0000-4000-8000-000000000001",null,
                "command","echo",List.of("PRIVATE_PAYLOAD"),1,new SandboxResourcePolicy(100,1,1000000,false,List.of(".")),"{}",null,null);
        sink.record(request,new SandboxResourceMetrics(123L,456L,null,null,789L,"UNAVAILABLE"));
        var captured=org.mockito.ArgumentCaptor.forClass(RecordRunEventCommand.class);verify(runtime).recordEvent(captured.capture());
        assertThat(captured.getValue().type()).isEqualTo(RunEventType.RESOURCE_OBSERVED);
        var payload=json.readTree(captured.getValue().payload());
        assertThat(payload.path("executionId").asText()).isEqualTo("exec");
        assertThat(payload.path("resourceMetrics").path("cpuUsageNanos").asLong()).isEqualTo(123);
        assertThat(payload.path("resourceMetrics").path("networkRxBytes").isNull()).isTrue();
        assertThat(captured.getValue().payload()).doesNotContain("PRIVATE_PAYLOAD","/data/","token","command");
    }
}

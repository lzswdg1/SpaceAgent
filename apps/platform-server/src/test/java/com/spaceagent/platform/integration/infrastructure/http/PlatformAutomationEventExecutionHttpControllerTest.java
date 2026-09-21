package com.spaceagent.platform.integration.infrastructure.http;

import com.spaceagent.platform.automation.api.AutomationEventExecutionApplicationApi;
import com.spaceagent.shared.auth.TenantAuthenticationDetails;
import com.spaceagent.shared.exception.GlobalExceptionHandler;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class PlatformAutomationEventExecutionHttpControllerTest {
    @Test
    void ownerResumesExactBlockedOccurrenceWithApprovalAndRevision() throws Exception {
        var executions = mock(AutomationEventExecutionApplicationApi.class);
        when(executions.resumeApproval(org.mockito.ArgumentMatchers.any())).thenReturn(
                new AutomationEventExecutionApplicationApi.DispatchView(
                        "occurrence", "delivery", "QUEUED", "run", "continuation", null, null));
        var mvc = MockMvcBuilders.standaloneSetup(
                        new PlatformAutomationEventExecutionHttpController(executions))
                .setControllerAdvice(new GlobalExceptionHandler()).build();
        var authentication = new UsernamePasswordAuthenticationToken("owner", null, List.of());
        authentication.setDetails(new TenantAuthenticationDetails("tenant", "MEMBER"));

        mvc.perform(post("/api/v1/automation-events/occurrence/resume-approval")
                        .principal(authentication).contentType("application/json")
                        .content("{\"approvalId\":\"approval\",\"expectedOccurrenceRevision\":7}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("QUEUED"));
        verify(executions).resumeApproval(argThat(command -> command.tenantId().equals("tenant")
                && command.ownerId().equals("owner") && command.occurrenceId().equals("occurrence")
                && command.approvalId().equals("approval")
                && command.expectedOccurrenceRevision() == 7));
    }
}

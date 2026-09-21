package com.spaceagent.platform.automation.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.spaceagent.platform.automation.api.AutomationApplicationApi;
import com.spaceagent.platform.automation.api.AutomationEventExecutionApplicationApi;
import com.spaceagent.platform.runtime.api.RuntimeContinuationHandler;
import com.spaceagent.platform.runtime.domain.RuntimeContinuationType;
import org.springframework.stereotype.Component;

@Component
public class AutomationContinuationHandler implements RuntimeContinuationHandler {

    private final AutomationApplicationApi automation;
    private final AutomationEventExecutionApplicationApi events;
    private final ObjectMapper json;

    public AutomationContinuationHandler(
            AutomationApplicationApi automation,
            AutomationEventExecutionApplicationApi events,
            ObjectMapper json) {
        this.automation = automation;
        this.events = events;
        this.json = json;
    }

    @Override
    public RuntimeContinuationType type() {
        return RuntimeContinuationType.AUTOMATION_EXECUTION;
    }

    @Override
    public void handle(ContinuationHandlerContext context) {
        try {
            var payload = json.readTree(context.continuation().payload());
            String occurrenceId = payload.path("eventOccurrenceId").asText(null);
            if (occurrenceId != null && !occurrenceId.isBlank()) {
                events.execute(new AutomationEventExecutionApplicationApi.ExecuteCommand(
                        occurrenceId, payload.path("deliveryId").asText(),
                        context.continuation().agentRunId(), payload.path("conversationId").asText(),
                        null, context.lease().leaseToken(),
                        context.lease().fencingToken(), context.lease().leaseUntil()));
                return;
            }
            String executionId = payload.path("executionId").asText(null);
            if (executionId == null || executionId.isBlank()) {
                throw new IllegalArgumentException(
                        "Automation continuation payload requires executionId");
            }
            automation.executeDispatched(new AutomationApplicationApi.ExecuteDispatchedCommand(
                    executionId, context.continuation().agentRunId(),
                    context.lease().leaseToken(), context.lease().fencingToken()));
        } catch (RuntimeException error) {
            throw error;
        } catch (Exception error) {
            throw new IllegalArgumentException("Invalid Automation continuation payload", error);
        }
    }
}

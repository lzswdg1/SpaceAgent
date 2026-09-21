package com.spaceagent.platform.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.spaceagent.platform.identity.api.AddTenantMembershipCommand;
import com.spaceagent.platform.identity.api.IdentityApplicationApi;
import com.spaceagent.platform.identity.domain.TenantRole;
import com.spaceagent.platform.integration.application.ProjectIntakeCoordinator;
import com.spaceagent.platform.integration.application.ProjectCodingCoordinator;
import com.spaceagent.platform.integration.application.ProjectRunHandoffCoordinator;
import com.spaceagent.platform.integration.api.ProjectPlanExecutionApplicationApi;
import com.spaceagent.platform.project.api.ProjectIntakeApplicationApi;
import com.spaceagent.platform.project.domain.ProjectIntakeState;
import com.spaceagent.platform.runtime.api.ProjectCodingJobApplicationApi;
import com.spaceagent.platform.runtime.domain.ProjectCodingJobState;
import com.spaceagent.platform.runtime.api.ProjectRecoveryApplicationApi;
import com.spaceagent.platform.project.domain.WorkspaceMode;
import com.spaceagent.platform.project.domain.WorkspaceState;
import com.spaceagent.platform.runtime.domain.AgentRunState;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;
import java.util.Map;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.when;

@SpringBootTest
@AutoConfigureMockMvc
class PlatformProjectHttpTest {

    private static final String JWT_SECRET = "local-dev-jwt-secret-should-be-overridden-12345";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private IdentityApplicationApi identityApi;

    @MockitoBean
    private ProjectRecoveryApplicationApi projectRecovery;

    @MockitoBean
    private ProjectIntakeCoordinator projectIntake;

    @MockitoBean
    private ProjectCodingCoordinator projectCoding;

    @MockitoBean
    private ProjectRunHandoffCoordinator projectHandoff;

    @MockitoBean
    private com.spaceagent.platform.runtime.api.ProjectPlanExecutionApplicationApi planExecution;

    @Test
    void projectRunHandoffRouteRequiresIdempotencyAndAcceptsExplicitTarget() throws Exception {
        Identity owner = register("project-handoff-"
                + java.util.UUID.randomUUID().toString().substring(0, 8) + "@x.io");
        String projectId = java.util.UUID.randomUUID().toString();
        String planId = java.util.UUID.randomUUID().toString();
        String stepId = java.util.UUID.randomUUID().toString();
        String jobId = java.util.UUID.randomUUID().toString();
        String body = objectMapper.writeValueAsString(Map.of(
                "targetConversationId", java.util.UUID.randomUUID().toString(),
                "targetAgentId", java.util.UUID.randomUUID().toString(),
                "reviewerAgentId", java.util.UUID.randomUUID().toString()));

        mockMvc.perform(post("/api/v1/projects/{projectId}/task-plans/{planId}/steps/{stepId}/coding-jobs/{jobId}/handoffs",
                        projectId, planId, stepId, jobId)
                        .header("Authorization", bearer(owner.token()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest());
        mockMvc.perform(post("/api/v1/projects/{projectId}/task-plans/{planId}/steps/{stepId}/coding-jobs/{jobId}/handoffs",
                        projectId, planId, stepId, jobId)
                        .header("Authorization", bearer(owner.token()))
                        .header("Idempotency-Key", "project-handoff-http-001")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isAccepted());
    }

    @Test
    void projectRoutesEnforceTenantMembershipAndProjectRoles() throws Exception {
        Identity owner = register("project-owner@example.com");
        Identity collaborator = register("project-collaborator@example.com");
        identityApi.addTenantMembership(new AddTenantMembershipCommand(
                owner.tenantId(), collaborator.userId(), TenantRole.MEMBER));
        String collaboratorProjectTenantToken = tokenFor(
                collaborator.userId(), "project-collaborator@example.com",
                owner.tenantId(), "MEMBER");

        JsonNode created = data(mockMvc.perform(post("/api/v1/projects")
                        .header("Authorization", bearer(owner.token()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "name", "HTTP Project",
                                "description", "Tenant scoped"))))
                .andExpect(status().isCreated())
                .andReturn());
        String projectId = created.path("id").asText();
        assertThat(created.path("tenantId").asText()).isEqualTo(owner.tenantId());
        assertThat(created.path("ownerId").asText()).isEqualTo(owner.userId());
        assertThat(created.path("status").asText()).isEqualTo("ACTIVE");

        mockMvc.perform(post("/api/v1/projects/" + projectId + "/members")
                        .header("Authorization", bearer(owner.token()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "userId", collaborator.userId(),
                                "role", "VIEWER"))))
                .andExpect(status().isCreated());

        JsonNode visible = data(mockMvc.perform(get("/api/v1/projects")
                        .header("Authorization", bearer(collaboratorProjectTenantToken)))
                .andExpect(status().isOk())
                .andReturn());
        assertThat(visible.findValuesAsText("id")).containsExactly(projectId);
        mockMvc.perform(get("/api/v1/projects/" + projectId)
                        .header("Authorization", bearer(collaboratorProjectTenantToken)))
                .andExpect(status().isOk());

        mockMvc.perform(patch("/api/v1/projects/" + projectId)
                        .header("Authorization", bearer(collaboratorProjectTenantToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"viewer-edit\"}"))
                .andExpect(status().isForbidden());

        mockMvc.perform(post("/api/v1/projects/" + projectId + "/members")
                        .header("Authorization", bearer(owner.token()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "userId", collaborator.userId(),
                                "role", "ADMIN"))))
                .andExpect(status().isCreated());
        JsonNode updated = data(mockMvc.perform(patch("/api/v1/projects/" + projectId)
                        .header("Authorization", bearer(collaboratorProjectTenantToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Admin Updated\",\"description\":null}"))
                .andExpect(status().isOk())
                .andReturn());
        assertThat(updated.path("name").asText()).isEqualTo("Admin Updated");
        assertThat(updated.path("description").isNull()).isTrue();

        mockMvc.perform(delete("/api/v1/projects/" + projectId)
                        .header("Authorization", bearer(collaboratorProjectTenantToken)))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/v1/projects/" + projectId + "/members")
                        .header("Authorization", bearer(collaboratorProjectTenantToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "userId", collaborator.userId(),
                                "role", "MEMBER"))))
                .andExpect(status().isForbidden());

        JsonNode members = data(mockMvc.perform(get("/api/v1/projects/" + projectId + "/members")
                        .header("Authorization", bearer(owner.token())))
                .andExpect(status().isOk())
                .andReturn());
        assertThat(members).hasSize(2);
        mockMvc.perform(delete("/api/v1/projects/" + projectId + "/members/" + collaborator.userId())
                        .header("Authorization", bearer(owner.token())))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/projects/" + projectId)
                        .header("Authorization", bearer(collaboratorProjectTenantToken)))
                .andExpect(status().isForbidden());

        mockMvc.perform(get("/api/v1/projects/" + projectId)
                        .header("Authorization", bearer(collaborator.token())))
                .andExpect(status().isNotFound());

        JsonNode archived = data(mockMvc.perform(delete("/api/v1/projects/" + projectId)
                        .header("Authorization", bearer(owner.token())))
                .andExpect(status().isOk())
                .andReturn());
        assertThat(archived.path("status").asText()).isEqualTo("ARCHIVED");
        mockMvc.perform(patch("/api/v1/projects/" + projectId)
                        .header("Authorization", bearer(owner.token()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"too-late\"}"))
                .andExpect(status().isConflict());
    }

    @Test
    void projectDirectoryGroupsItsConversations() throws Exception {
        Identity owner = register("project-dir-"
                + java.util.UUID.randomUUID().toString().substring(0, 8) + "@x.io");
        JsonNode project = data(mockMvc.perform(post("/api/v1/projects")
                        .header("Authorization", bearer(owner.token()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Directory HTTP\"}"))
                .andExpect(status().isCreated()).andReturn());
        String projectId = project.path("id").asText();
        JsonNode directories = data(mockMvc.perform(get(
                        "/api/v1/projects/{projectId}/directories", projectId)
                        .header("Authorization", bearer(owner.token())))
                .andExpect(status().isOk()).andReturn());
        assertThat(directories).singleElement();
        String directoryId = directories.get(0).path("id").asText();
        assertThat(directories.get(0).path("defaultDirectory").asBoolean()).isTrue();

        JsonNode conversation = data(mockMvc.perform(post("/api/v1/chat/conversations")
                        .header("Authorization", bearer(owner.token()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "agentId", "agent-directory",
                                "name", "Directory conversation",
                                "projectId", projectId,
                                "projectDirectoryId", directoryId))))
                .andExpect(status().isOk()).andReturn());
        assertThat(conversation.path("projectDirectoryId").asText()).isEqualTo(directoryId);

        JsonNode grouped = data(mockMvc.perform(get(
                        "/api/v1/projects/{projectId}/directories/{directoryId}/conversations",
                        projectId, directoryId)
                        .header("Authorization", bearer(owner.token())))
                .andExpect(status().isOk()).andReturn());
        assertThat(grouped.at("/items/0/projectDirectoryId").asText()).isEqualTo(directoryId);
        assertThat(grouped.at("/items/0/id").asText())
                .isEqualTo(conversation.path("conversationId").asText());
    }

    @Test
    void codingRecoveryRoutesRequireIdempotencyAndReturnTypedSnapshot() throws Exception {
        Identity owner = register("project-recovery-"
                + java.util.UUID.randomUUID().toString().substring(0, 8) + "@x.io");
        String projectId = java.util.UUID.randomUUID().toString();
        String runId = java.util.UUID.randomUUID().toString();
        String snapshotId = java.util.UUID.randomUUID().toString();
        var context = new ProjectRecoveryApplicationApi.RecoveryContext(
                new ProjectRecoveryApplicationApi.ProjectContext(
                        projectId, java.util.UUID.randomUUID().toString(), "Repository", ".",
                        java.util.UUID.randomUUID().toString()),
                null, null,
                new ProjectRecoveryApplicationApi.WorkspaceGitContext(
                        java.util.UUID.randomUUID().toString(), java.util.UUID.randomUUID().toString(),
                        WorkspaceMode.MANAGED_GIT, WorkspaceState.READY, 1, "main", "branch",
                        "a".repeat(40), "a".repeat(40), "", List.of(), null, ""),
                new ProjectRecoveryApplicationApi.RuntimeContext(
                        runId, "agent", java.util.UUID.randomUUID().toString(),
                        AgentRunState.IN_PROGRESS, 1, "coding", null, null, null,
                        null, 0, null, 4),
                new ProjectRecoveryApplicationApi.ConversationContext(
                        java.util.UUID.randomUUID().toString(), null, null, null,
                        null, null, null, null),
                List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(),
                "RESUME_PLAN_STEP", List.of("PROJECT_BLUEPRINT_MISSING"));
        var view = new ProjectRecoveryApplicationApi.CodingRecoveryPackageView(
                snapshotId, "sha256:" + "b".repeat(64), Instant.now(), context);
        when(projectRecovery.capture(any())).thenReturn(view);
        when(projectRecovery.latest(any())).thenReturn(view);
        when(projectRecovery.get(any())).thenReturn(view);

        JsonNode captured = data(mockMvc.perform(post(
                        "/api/v1/projects/{projectId}/runs/{runId}/recovery-packages",
                        projectId, runId)
                        .header("Authorization", bearer(owner.token()))
                        .header("Idempotency-Key", "recovery-http-001"))
                .andExpect(status().isCreated()).andReturn());
        assertThat(captured.path("snapshotId").asText()).isEqualTo(snapshotId);
        assertThat(captured.at("/context/workspace/mode").asText()).isEqualTo("MANAGED_GIT");
        assertThat(captured.at("/context/blockers/0").asText())
                .isEqualTo("PROJECT_BLUEPRINT_MISSING");

        mockMvc.perform(post("/api/v1/projects/{projectId}/runs/{runId}/recovery-packages",
                        projectId, runId)
                        .header("Authorization", bearer(owner.token())))
                .andExpect(status().isBadRequest());
        mockMvc.perform(get(
                        "/api/v1/projects/{projectId}/runs/{runId}/recovery-packages/latest",
                        projectId, runId)
                        .header("Authorization", bearer(owner.token())))
                .andExpect(status().isOk());
        mockMvc.perform(get(
                        "/api/v1/projects/{projectId}/runs/{runId}/recovery-packages/{snapshotId}",
                        projectId, runId, snapshotId)
                        .header("Authorization", bearer(owner.token())))
                .andExpect(status().isOk());
    }

    @Test
    void projectIntakeRouteRequiresIdempotencyAndReturnsDurableJob() throws Exception {
        Identity owner = register("project-intake-"
                + java.util.UUID.randomUUID().toString().substring(0, 8) + "@x.io");
        String projectId = java.util.UUID.randomUUID().toString();
        String directoryId = java.util.UUID.randomUUID().toString();
        String jobId = java.util.UUID.randomUUID().toString();
        when(projectIntake.enqueue(any())).thenReturn(new ProjectIntakeApplicationApi.JobView(
                jobId, owner.tenantId(), owner.userId(), projectId, directoryId,
                java.util.UUID.randomUUID().toString(), java.util.UUID.randomUUID().toString(),
                java.util.UUID.randomUUID().toString(),
                "Understand this repository", ProjectIntakeState.PENDING, 0, null, null, null,
                null, null, null, null, null, null, null, null, null, 1, Instant.now(), null,
                Instant.now(), null, null));
        String body = objectMapper.writeValueAsString(Map.of(
                "conversationId", java.util.UUID.randomUUID().toString(),
                "agentId", java.util.UUID.randomUUID().toString(),
                "goal", "Understand this repository"));

        JsonNode accepted = data(mockMvc.perform(post(
                        "/api/v1/projects/{projectId}/directories/{directoryId}/intakes",
                        projectId, directoryId)
                        .header("Authorization", bearer(owner.token()))
                        .header("Idempotency-Key", "project-intake-http-001")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isAccepted()).andReturn());
        assertThat(accepted.path("id").asText()).isEqualTo(jobId);
        assertThat(accepted.path("state").asText()).isEqualTo("PENDING");
        mockMvc.perform(post(
                        "/api/v1/projects/{projectId}/directories/{directoryId}/intakes",
                        projectId, directoryId)
                        .header("Authorization", bearer(owner.token()))
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest());
    }

    @Test
    void projectCodingRouteRequiresIdempotencyAndReturnsQueuedJob() throws Exception {
        Identity owner = register("project-coding-"
                + java.util.UUID.randomUUID().toString().substring(0, 8) + "@x.io");
        String projectId = java.util.UUID.randomUUID().toString();
        String planId = java.util.UUID.randomUUID().toString();
        String stepId = java.util.UUID.randomUUID().toString();
        String jobId = java.util.UUID.randomUUID().toString();
        when(projectCoding.enqueue(any())).thenReturn(new ProjectCodingJobApplicationApi.JobView(
                jobId, owner.tenantId(), owner.userId(), projectId,
                java.util.UUID.randomUUID().toString(), java.util.UUID.randomUUID().toString(),
                java.util.UUID.randomUUID().toString(), java.util.UUID.randomUUID().toString(),
                java.util.UUID.randomUUID().toString(), planId, stepId,
                java.util.UUID.randomUUID().toString(), java.util.UUID.randomUUID().toString(),
                java.util.UUID.randomUUID().toString(), "main", ProjectCodingJobState.PENDING,
                null, null, null, 0, 0, null, null, null, null, null, null, null,
                0, 1, Instant.now(), null, Instant.now(), null));
        String body = objectMapper.writeValueAsString(Map.of(
                "projectDirectoryId", java.util.UUID.randomUUID().toString(),
                "conversationId", java.util.UUID.randomUUID().toString(),
                "sourceRepositoryId", java.util.UUID.randomUUID().toString(),
                "rootTaskId", java.util.UUID.randomUUID().toString(),
                "taskId", java.util.UUID.randomUUID().toString(),
                "agentId", java.util.UUID.randomUUID().toString(),
                "reviewerAgentId", java.util.UUID.randomUUID().toString(),
                "baseRef", "main"));
        JsonNode response = data(mockMvc.perform(post(
                        "/api/v1/projects/{projectId}/task-plans/{planId}/steps/{stepId}/coding-jobs",
                        projectId, planId, stepId)
                        .header("Authorization", bearer(owner.token()))
                        .header("Idempotency-Key", "project-coding-http-001")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isAccepted()).andReturn());
        assertThat(response.path("id").asText()).isEqualTo(jobId);
        assertThat(response.path("state").asText()).isEqualTo("PENDING");
        mockMvc.perform(post(
                        "/api/v1/projects/{projectId}/task-plans/{planId}/steps/{stepId}/coding-jobs",
                        projectId, planId, stepId)
                        .header("Authorization", bearer(owner.token()))
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest());
    }

    @Test
    void projectPlanExecutionRouteUsesAuthenticatedOwnerAndReturnsActiveJob()
            throws Exception {
        Identity owner = register("project-plan-execute-"
                + java.util.UUID.randomUUID().toString().substring(0, 8) + "@x.io");
        String projectId = java.util.UUID.randomUUID().toString();
        String rootTaskId = java.util.UUID.randomUUID().toString();
        String planId = java.util.UUID.randomUUID().toString();
        String stepId = java.util.UUID.randomUUID().toString();
        String jobId = java.util.UUID.randomUUID().toString();
        String directoryId = java.util.UUID.randomUUID().toString();
        String conversationId = java.util.UUID.randomUUID().toString();
        String sourceId = java.util.UUID.randomUUID().toString();
        String agentId = java.util.UUID.randomUUID().toString();
        String configurationHash = "a".repeat(64);
        String reviewerAgentId = java.util.UUID.randomUUID().toString();
        var job = new ProjectCodingJobApplicationApi.JobView(
                jobId, owner.tenantId(), owner.userId(), projectId, directoryId,
                conversationId, sourceId, rootTaskId,
                java.util.UUID.randomUUID().toString(), planId, stepId, agentId,
                configurationHash, null, "main", ProjectCodingJobState.PENDING,
                null, null, null, 0, 0, null, null, null, null, null, null, null,
                0, 1, Instant.now(), null, Instant.now(), null);
        when(projectCoding.dispatch(any())).thenReturn(
                new ProjectPlanExecutionApplicationApi.DispatchView(
                        planId, "ACTIVE", job, true));
        String body = objectMapper.writeValueAsString(Map.of(
                "projectDirectoryId", directoryId,
                "conversationId", conversationId,
                "sourceRepositoryId", sourceId,
                "agentId", agentId,
                "reviewerAgentId", reviewerAgentId,
                "baseRef", "main"));

        JsonNode response = data(mockMvc.perform(post(
                        "/api/v1/projects/{projectId}/tasks/{rootTaskId}/plans/{planId}/execute",
                        projectId, rootTaskId, planId)
                        .header("Authorization", bearer(owner.token()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isAccepted()).andReturn());

        assertThat(response.path("taskPlanState").asText()).isEqualTo("ACTIVE");
        assertThat(response.path("materialized").asBoolean()).isTrue();
        assertThat(response.path("activeJob").path("id").asText()).isEqualTo(jobId);
        verify(projectCoding).dispatch(argThat(command ->
                command.tenantId().equals(owner.tenantId())
                        && command.ownerId().equals(owner.userId())
                        && command.projectId().equals(projectId)
                        && command.rootTaskId().equals(rootTaskId)
                        && command.taskPlanId().equals(planId)));
    }

    @Test
    void projectPlanExecutionRuntimeRoutesUseOwnerScopeAndIdempotencyHeader()
            throws Exception {
        Identity owner = register("project-plan-runtime-"
                + java.util.UUID.randomUUID().toString().substring(0, 8) + "@x.io");
        String projectId = java.util.UUID.randomUUID().toString();
        String planId = java.util.UUID.randomUUID().toString();
        String executionId = java.util.UUID.randomUUID().toString();
        Instant now = Instant.now();
        var execution = new com.spaceagent.platform.runtime.api.ProjectPlanExecutionApplicationApi.ExecutionView(
                executionId, owner.tenantId(), owner.userId(), projectId,
                java.util.UUID.randomUUID().toString(), java.util.UUID.randomUUID().toString(),
                java.util.UUID.randomUUID().toString(), java.util.UUID.randomUUID().toString(), planId,
                java.util.UUID.randomUUID().toString(), java.util.UUID.randomUUID().toString(),
                java.util.UUID.randomUUID().toString(), "main",
                com.spaceagent.platform.runtime.domain.ProjectPlanExecutionState.READY,
                null, 0, 1, now, null, now, null, List.of());
        when(projectCoding.dispatch(any())).thenReturn(
                new ProjectPlanExecutionApplicationApi.DispatchView(
                        executionId, planId, "ACTIVE", null, false));
        when(planExecution.get(any())).thenReturn(execution);
        when(planExecution.list(any())).thenReturn(
                new com.spaceagent.platform.runtime.api.ProjectPlanExecutionApplicationApi.ExecutionPage(
                        List.of(execution), 1, 20, 1));
        var control = new com.spaceagent.platform.runtime.api.ProjectPlanExecutionApplicationApi.ControlView(
                executionId, com.spaceagent.platform.runtime.domain.ProjectPlanExecutionState.PAUSED,
                com.spaceagent.platform.runtime.domain.ProjectPlanExecutionDesiredState.PAUSED,
                "PROJECT_PLAN_EXECUTION_WAITING", true, 7, 1, now, null);
        when(planExecution.getControl(any())).thenReturn(control);
        when(projectCoding.pause(any())).thenReturn(execution);
        when(projectCoding.resume(any(
                com.spaceagent.platform.runtime.api.ProjectPlanExecutionApplicationApi.ResumeCommand.class)))
                .thenReturn(execution);
        when(projectCoding.cancel(any())).thenReturn(execution);
        String body = objectMapper.writeValueAsString(Map.of(
                "projectDirectoryId", execution.projectDirectoryId(),
                "conversationId", execution.conversationId(),
                "sourceRepositoryId", execution.sourceRepositoryId(),
                "rootTaskId", execution.rootTaskId(),
                "agentId", execution.agentId(),
                "reviewerAgentId", execution.reviewerAgentId(),
                "baseRef", "main"));

        JsonNode started = data(mockMvc.perform(post(
                        "/api/v1/projects/{projectId}/task-plans/{planId}/executions",
                        projectId, planId)
                        .header("Authorization", bearer(owner.token()))
                        .header("Idempotency-Key", "plan-execution-http-001")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isAccepted()).andReturn());
        assertThat(started.path("id").asText()).isEqualTo(executionId);

        JsonNode fetched = data(mockMvc.perform(get(
                        "/api/v1/projects/{projectId}/task-plans/{planId}/executions/{executionId}",
                        projectId, planId, executionId)
                        .header("Authorization", bearer(owner.token())))
                .andExpect(status().isOk()).andReturn());
        assertThat(fetched.path("state").asText()).isEqualTo("READY");
        JsonNode page = data(mockMvc.perform(get(
                        "/api/v1/projects/{projectId}/task-plans/{planId}/executions",
                        projectId, planId)
                        .header("Authorization", bearer(owner.token())))
                .andExpect(status().isOk()).andReturn());
        assertThat(page.path("items")).hasSize(1);
        JsonNode controlResponse = data(mockMvc.perform(get(
                        "/api/v1/projects/{projectId}/task-plans/{planId}/executions/{executionId}/control",
                        projectId, planId, executionId)
                        .header("Authorization", bearer(owner.token())))
                .andExpect(status().isOk()).andReturn());
        assertThat(controlResponse.path("state").asText()).isEqualTo("PAUSED");
        assertThat(controlResponse.path("desiredState").asText()).isEqualTo("PAUSED");
        assertThat(controlResponse.has("controlReason")).isFalse();
        JsonNode trace = data(mockMvc.perform(get(
                        "/api/v1/projects/{projectId}/task-plans/{planId}/executions/{executionId}/trace",
                        projectId, planId, executionId)
                        .header("Authorization", bearer(owner.token())))
                .andExpect(status().isOk()).andReturn());
        assertThat(trace.path("safeErrorCode").asText()).isEqualTo("PROJECT_PLAN_EXECUTION_WAITING");
        mockMvc.perform(post(
                        "/api/v1/projects/{projectId}/task-plans/{planId}/executions/{executionId}/pause",
                        projectId, planId, executionId)
                        .header("Authorization", bearer(owner.token()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedRevision\":7,\"reason\":\"maintenance\"}"))
                .andExpect(status().isAccepted());
        mockMvc.perform(post(
                        "/api/v1/projects/{projectId}/task-plans/{planId}/executions/{executionId}/resume",
                        projectId, planId, executionId)
                        .header("Authorization", bearer(owner.token()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedRevision\":7}"))
                .andExpect(status().isAccepted());
        mockMvc.perform(post(
                        "/api/v1/projects/{projectId}/task-plans/{planId}/executions/{executionId}/cancel",
                        projectId, planId, executionId)
                        .header("Authorization", bearer(owner.token()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedRevision\":7,\"reason\":\"stop\"}"))
                .andExpect(status().isAccepted());
        verify(projectCoding).dispatch(argThat(command ->
                command.tenantId().equals(owner.tenantId())
                        && command.ownerId().equals(owner.userId())
                        && command.projectId().equals(projectId)
                        && command.taskPlanId().equals(planId)));
        verify(planExecution, org.mockito.Mockito.never()).start(any());
        verify(planExecution, atLeastOnce()).get(argThat(query ->
                planId.equals(query.taskPlanId()) && executionId.equals(query.executionId())));
        verify(planExecution, atLeastOnce()).getControl(argThat(query ->
                owner.tenantId().equals(query.tenantId()) && owner.userId().equals(query.ownerId())
                        && planId.equals(query.taskPlanId()) && executionId.equals(query.executionId())));
        verify(projectCoding).pause(any());
        verify(projectCoding).resume(any(
                com.spaceagent.platform.runtime.api.ProjectPlanExecutionApplicationApi.ResumeCommand.class));
        verify(projectCoding).cancel(any());

        mockMvc.perform(post(
                        "/api/v1/projects/{projectId}/task-plans/{planId}/executions",
                        projectId, planId)
                        .header("Authorization", bearer(owner.token()))
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest());
    }

    private Identity register(String username) throws Exception {
        JsonNode auth = data(mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "username", username,
                                "password", "password123",
                                "displayName", username))))
                .andExpect(status().isOk())
                .andReturn());
        return new Identity(
                auth.path("userId").asText(),
                auth.path("tenantId").asText(),
                auth.path("token").asText());
    }

    private String tokenFor(
            String userId,
            String username,
            String tenantId,
            String tenantRole) {
        SecretKey key = Keys.hmacShaKeyFor(JWT_SECRET.getBytes(StandardCharsets.UTF_8));
        Instant now = Instant.now();
        return Jwts.builder()
                .subject(userId)
                .claim("username", username)
                .claim("role", "USER")
                .claim("tenant_id", tenantId)
                .claim("tenant_role", tenantRole)
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plus(1, ChronoUnit.HOURS)))
                .signWith(key)
                .compact();
    }

    private JsonNode data(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsByteArray()).path("data");
    }

    private static String bearer(String token) {
        return "Bearer " + token;
    }

    private record Identity(String userId, String tenantId, String token) {
    }
}

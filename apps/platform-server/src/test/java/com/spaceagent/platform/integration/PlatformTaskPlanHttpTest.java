package com.spaceagent.platform.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.spaceagent.platform.identity.api.AddTenantMembershipCommand;
import com.spaceagent.platform.identity.api.IdentityApplicationApi;
import com.spaceagent.platform.identity.domain.TenantRole;
import com.spaceagent.platform.project.api.CreateChatRootTaskCommand;
import com.spaceagent.platform.project.api.CreateChatTaskPlanProposalCommand;
import com.spaceagent.platform.project.api.TaskApplicationApi;
import com.spaceagent.platform.project.api.TaskPlanApplicationApi;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class PlatformTaskPlanHttpTest {

    private static final String JWT_SECRET = "local-dev-jwt-secret-should-be-overridden-12345";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private IdentityApplicationApi identityApi;

    @Autowired
    private TaskApplicationApi taskApi;

    @Autowired
    private TaskPlanApplicationApi taskPlanApi;

    @Test
    void taskPlanApprovalAndConversationActiveTaskRespectProjectAndParticipantRoles()
            throws Exception {
        Identity owner = register("taskplan-owner@example.com");
        Identity admin = joinTenant(register("taskplan-admin@example.com"), owner.tenantId());
        Identity member = joinTenant(register("taskplan-member@example.com"), owner.tenantId());
        String projectId = createProject(owner);
        addProjectMember(owner, projectId, admin.userId(), "ADMIN");
        addProjectMember(owner, projectId, member.userId(), "MEMBER");
        String rootTaskId = createTask(owner, projectId, null, "Root");
        String inspectTaskId = createTask(owner, projectId, rootTaskId, "Inspect");
        String implementTaskId = createTask(owner, projectId, rootTaskId, "Implement");

        String plansPath = plansPath(projectId, rootTaskId);
        mockMvc.perform(post(plansPath)
                        .header("Authorization", bearer(member.projectTenantToken()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(planJson(inspectTaskId, implementTaskId)))
                .andExpect(status().isForbidden());
        JsonNode plan = data(mockMvc.perform(post(plansPath)
                        .header("Authorization", bearer(owner.token()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(planJson(inspectTaskId, implementTaskId)))
                .andExpect(status().isCreated())
                .andReturn());
        String planId = plan.path("id").asText();
        assertThat(plan.path("status").asText()).isEqualTo("DRAFT");
        assertThat(plan.path("steps").get(1).path("dependencyStepIds").get(0).asText())
                .isEqualTo(plan.path("steps").get(0).path("id").asText());

        assertPlanState(action(owner.token(), plansPath, planId, "propose"), "PROPOSED");
        assertPlanState(action(admin.projectTenantToken(), plansPath, planId, "approve"), "APPROVED");
        assertPlanState(action(admin.projectTenantToken(), plansPath, planId, "activate"), "ACTIVE");
        JsonNode root = data(mockMvc.perform(get(
                                "/api/v1/projects/" + projectId + "/tasks/" + rootTaskId)
                        .header("Authorization", bearer(owner.token())))
                .andExpect(status().isOk())
                .andReturn());
        assertThat(root.path("currentTaskPlanId").asText()).isEqualTo(planId);

        String agentId = data(mockMvc.perform(post("/api/v1/agents")
                        .header("Authorization", bearer(owner.token()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"taskplan-agent\"}"))
                .andExpect(status().isCreated())
                .andReturn()).path("id").asText();
        JsonNode conversation = data(mockMvc.perform(post("/api/v1/chat/conversations")
                        .header("Authorization", bearer(owner.token()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "agentId", agentId,
                                "name", "Planning",
                                "projectId", projectId,
                                "activeTaskId", rootTaskId))))
                .andExpect(status().isOk())
                .andReturn());
        String conversationId = conversation.path("conversationId").asText();
        assertThat(conversation.path("activeTaskId").asText()).isEqualTo(rootTaskId);
        JsonNode focused = data(mockMvc.perform(put(
                                "/api/v1/chat/conversations/" + conversationId + "/active-task")
                        .header("Authorization", bearer(owner.token()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "taskId", inspectTaskId))))
                .andExpect(status().isOk())
                .andReturn());
        assertThat(focused.path("activeTaskId").asText()).isEqualTo(inspectTaskId);
        mockMvc.perform(put("/api/v1/chat/conversations/" + conversationId + "/active-task")
                        .header("Authorization", bearer(member.projectTenantToken()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"taskId\":null}"))
                .andExpect(status().isNotFound());
        JsonNode cleared = data(mockMvc.perform(put(
                                "/api/v1/chat/conversations/" + conversationId + "/active-task")
                        .header("Authorization", bearer(owner.token()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"taskId\":null}"))
                .andExpect(status().isOk())
                .andReturn());
        assertThat(cleared.path("activeTaskId").isNull()).isTrue();
    }

    @Test
    void chatPlanReviewEndpointsAreOwnerScopedAndDoNotCreatePlans() throws Exception {
        Identity owner = register("chat-plan-owner@example.com");
        Identity outsider = register("chat-plan-outsider@example.com");
        String conversationId = "chat-plan-conversation-" + UUID.randomUUID();
        var root = taskApi.createOrGetChatRootTask(new CreateChatRootTaskCommand(
                owner.tenantId(), owner.userId(), conversationId,
                "chat-plan-message-" + UUID.randomUUID(), "Plan", "Plan the answer"));
        var plan = taskPlanApi.createChatProposal(new CreateChatTaskPlanProposalCommand(
                owner.tenantId(), owner.userId(), conversationId, root.id(),
                UUID.randomUUID().toString(), null, "Inspect then answer", List.of(
                new CreateChatTaskPlanProposalCommand.StepProposal(
                        "inspect", "Inspect evidence", List.of()),
                new CreateChatTaskPlanProposalCommand.StepProposal(
                        "answer", "Answer with evidence", List.of("inspect")))));
        String path = "/api/v1/chat/conversations/" + conversationId
                + "/tasks/" + root.id() + "/plans";

        JsonNode listed = data(mockMvc.perform(get(path)
                        .header("Authorization", bearer(owner.token())))
                .andExpect(status().isOk()).andReturn());
        assertThat(listed).hasSize(1);
        assertThat(listed.get(0).path("scope").asText()).isEqualTo("CHAT");
        assertThat(listed.get(0).path("strategySummary").asText())
                .isEqualTo("Inspect then answer");
        mockMvc.perform(get(path)
                        .header("Authorization", bearer(outsider.token())))
                .andExpect(status().isNotFound());

        assertPlanState(action(owner.token(), path, plan.id(), "approve"), "APPROVED");
        assertPlanState(action(owner.token(), path, plan.id(), "activate"), "ACTIVE");
    }

    private JsonNode action(String token, String path, String planId, String action) throws Exception {
        return data(mockMvc.perform(post(path + "/" + planId + "/" + action)
                        .header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andReturn());
    }

    private void assertPlanState(JsonNode plan, String expected) {
        assertThat(plan.path("status").asText()).isEqualTo(expected);
    }

    private String planJson(String inspectTaskId, String implementTaskId) throws Exception {
        return objectMapper.writeValueAsString(Map.of("steps", List.of(
                Map.of(
                        "stepKey", "inspect",
                        "childTaskId", inspectTaskId,
                        "expectedOutput", "Inspection report",
                        "acceptanceCriteria", List.of("modules identified"),
                        "approvalRequired", false),
                Map.of(
                        "stepKey", "implement",
                        "childTaskId", implementTaskId,
                        "dependsOnStepKeys", List.of("inspect"),
                        "expectedOutput", "Verified patch",
                        "acceptanceCriteria", List.of("tests pass"),
                        "approvalRequired", true))));
    }

    private String createProject(Identity owner) throws Exception {
        return data(mockMvc.perform(post("/api/v1/projects")
                        .header("Authorization", bearer(owner.token()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"TaskPlan HTTP Project\"}"))
                .andExpect(status().isCreated())
                .andReturn()).path("id").asText();
    }

    private String createTask(
            Identity owner,
            String projectId,
            String parentTaskId,
            String title) throws Exception {
        java.util.LinkedHashMap<String, Object> request = new java.util.LinkedHashMap<>();
        request.put("title", title);
        request.put("goal", "Deliver " + title);
        request.put("parentTaskId", parentTaskId);
        request.put("acceptanceCriteria", List.of("done"));
        return data(mockMvc.perform(post("/api/v1/projects/" + projectId + "/tasks")
                        .header("Authorization", bearer(owner.token()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andReturn()).path("id").asText();
    }

    private void addProjectMember(
            Identity owner,
            String projectId,
            String userId,
            String role) throws Exception {
        mockMvc.perform(post("/api/v1/projects/" + projectId + "/members")
                        .header("Authorization", bearer(owner.token()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "userId", userId, "role", role))))
                .andExpect(status().isCreated());
    }

    private Identity joinTenant(Identity identity, String tenantId) {
        identityApi.addTenantMembership(new AddTenantMembershipCommand(
                tenantId, identity.userId(), TenantRole.MEMBER));
        return new Identity(
                identity.userId(), identity.tenantId(), identity.token(),
                tokenFor(identity.userId(), tenantId));
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
                auth.path("userId").asText(), auth.path("tenantId").asText(),
                auth.path("token").asText(), null);
    }

    private String tokenFor(String userId, String tenantId) {
        SecretKey key = Keys.hmacShaKeyFor(JWT_SECRET.getBytes(StandardCharsets.UTF_8));
        Instant now = Instant.now();
        return Jwts.builder()
                .subject(userId)
                .claim("username", userId)
                .claim("role", "USER")
                .claim("tenant_id", tenantId)
                .claim("tenant_role", "MEMBER")
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plus(1, ChronoUnit.HOURS)))
                .signWith(key)
                .compact();
    }

    private JsonNode data(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsByteArray()).path("data");
    }

    private static String plansPath(String projectId, String rootTaskId) {
        return "/api/v1/projects/" + projectId + "/tasks/" + rootTaskId + "/plans";
    }

    private static String bearer(String token) {
        return "Bearer " + token;
    }

    private record Identity(
            String userId,
            String tenantId,
            String token,
            String projectTenantToken) {
    }
}

package com.spaceagent.platform.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.spaceagent.platform.identity.api.AddTenantMembershipCommand;
import com.spaceagent.platform.identity.api.IdentityApplicationApi;
import com.spaceagent.platform.identity.domain.TenantRole;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class PlatformTaskHttpTest {

    private static final String JWT_SECRET = "local-dev-jwt-secret-should-be-overridden-12345";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private IdentityApplicationApi identityApi;

    @Test
    void taskRoutesEnforcePlanningWorkAndTenantBoundaries() throws Exception {
        Identity owner = register("task-owner@example.com");
        Identity admin = joinTenant(register("task-admin@example.com"), owner.tenantId());
        Identity member = joinTenant(register("task-member@example.com"), owner.tenantId());
        Identity viewer = joinTenant(register("task-viewer@example.com"), owner.tenantId());
        String projectId = createProject(owner);
        addProjectMember(owner, projectId, admin.userId(), "ADMIN");
        addProjectMember(owner, projectId, member.userId(), "MEMBER");
        addProjectMember(owner, projectId, viewer.userId(), "VIEWER");

        mockMvc.perform(post(tasksPath(projectId))
                        .header("Authorization", bearer(member.projectTenantToken()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(taskJson("member-plan")))
                .andExpect(status().isForbidden());

        JsonNode created = data(mockMvc.perform(post(tasksPath(projectId))
                        .header("Authorization", bearer(owner.token()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(taskJson("HTTP Task")))
                .andExpect(status().isCreated())
                .andReturn());
        String taskId = created.path("id").asText();
        assertThat(created.path("projectId").asText()).isEqualTo(projectId);
        assertThat(created.path("state").asText()).isEqualTo("PENDING");
        assertThat(created.path("constraints").get(0).asText()).isEqualTo("no external writes");

        JsonNode edited = data(mockMvc.perform(patch(taskPath(projectId, taskId))
                        .header("Authorization", bearer(admin.projectTenantToken()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"title":"Admin Planned","description":null,
                                 "acceptanceCriteria":["all tests pass"]}
                                """))
                .andExpect(status().isOk())
                .andReturn());
        assertThat(edited.path("title").asText()).isEqualTo("Admin Planned");
        assertThat(edited.path("description").isNull()).isTrue();

        mockMvc.perform(patch(taskPath(projectId, taskId))
                        .header("Authorization", bearer(member.projectTenantToken()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"goal\":\"member rewrite\"}"))
                .andExpect(status().isForbidden());
        mockMvc.perform(post(taskPath(projectId, taskId) + "/ready")
                        .header("Authorization", bearer(member.projectTenantToken())))
                .andExpect(status().isForbidden());

        assertState(transition(owner, projectId, taskId, "ready"), "READY");
        assertState(transition(member, projectId, taskId, "start"), "IN_PROGRESS");
        assertState(transition(member, projectId, taskId, "complete"), "COMPLETED");
        mockMvc.perform(patch(taskPath(projectId, taskId))
                        .header("Authorization", bearer(admin.projectTenantToken()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"too late\"}"))
                .andExpect(status().isConflict());

        JsonNode visible = data(mockMvc.perform(get(tasksPath(projectId))
                        .header("Authorization", bearer(viewer.projectTenantToken())))
                .andExpect(status().isOk())
                .andReturn());
        assertThat(visible.findValuesAsText("id")).contains(taskId);
        mockMvc.perform(get(taskPath(projectId, taskId))
                        .header("Authorization", bearer(viewer.projectTenantToken())))
                .andExpect(status().isOk());
        mockMvc.perform(post(taskPath(projectId, taskId) + "/start")
                        .header("Authorization", bearer(viewer.projectTenantToken())))
                .andExpect(status().isForbidden());
        mockMvc.perform(get(taskPath(projectId, taskId))
                        .header("Authorization", bearer(member.token())))
                .andExpect(status().isNotFound());

        String cancellable = data(mockMvc.perform(post(tasksPath(projectId))
                        .header("Authorization", bearer(owner.token()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(taskJson("Cancel Me")))
                .andExpect(status().isCreated())
                .andReturn()).path("id").asText();
        assertState(transition(admin, projectId, cancellable, "cancel"), "CANCELLED");
    }

    private Identity joinTenant(Identity identity, String tenantId) {
        identityApi.addTenantMembership(new AddTenantMembershipCommand(
                tenantId, identity.userId(), TenantRole.MEMBER));
        return new Identity(
                identity.userId(), identity.tenantId(), identity.token(),
                tokenFor(identity.userId(), tenantId));
    }

    private String createProject(Identity owner) throws Exception {
        return data(mockMvc.perform(post("/api/v1/projects")
                        .header("Authorization", bearer(owner.token()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Task HTTP Project\"}"))
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
                                "userId", userId,
                                "role", role))))
                .andExpect(status().isCreated());
    }

    private JsonNode transition(
            Identity identity,
            String projectId,
            String taskId,
            String action) throws Exception {
        String token = identity.projectTenantToken() == null
                ? identity.token()
                : identity.projectTenantToken();
        return data(mockMvc.perform(post(taskPath(projectId, taskId) + "/" + action)
                        .header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andReturn());
    }

    private void assertState(JsonNode task, String state) {
        assertThat(task.path("state").asText()).isEqualTo(state);
    }

    private String taskJson(String title) throws Exception {
        return objectMapper.writeValueAsString(Map.of(
                "title", title,
                "goal", "Deliver " + title,
                "description", "Task HTTP coverage",
                "constraints", List.of("no external writes"),
                "acceptanceCriteria", List.of("tests pass")));
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
                auth.path("token").asText(),
                null);
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

    private static String tasksPath(String projectId) {
        return "/api/v1/projects/" + projectId + "/tasks";
    }

    private static String taskPath(String projectId, String taskId) {
        return tasksPath(projectId) + "/" + taskId;
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

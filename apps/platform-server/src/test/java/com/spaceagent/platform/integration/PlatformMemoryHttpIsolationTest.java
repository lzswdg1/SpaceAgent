package com.spaceagent.platform.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.spaceagent.platform.project.api.ProjectOwnershipPort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Import(PlatformMemoryHttpIsolationTest.ProjectOwnershipTestConfiguration.class)
class PlatformMemoryHttpIsolationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private TestProjectOwnershipPort projectOwnership;

    @BeforeEach
    void resetProjectOwnership() {
        projectOwnership.reset();
    }

    @Test
    void candidateAndRecallAreIsolatedByAuthenticatedUser() throws Exception {
        Identity owner = register("memory-owner@example.com");
        Identity foreign = register("memory-foreign@example.com");

        mockMvc.perform(post("/api/v1/memory/candidates")
                        .header("Authorization", bearer(foreign.token()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(candidateRequest(
                                "USER",
                                owner.userId(),
                                "foreign-owned-source",
                                "Cross-user memory pollution must be rejected"))))
                .andExpect(status().isNotFound());

        JsonNode candidate = data(mockMvc.perform(post("/api/v1/memory/candidates")
                        .header("Authorization", bearer(owner.token()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "scope", Map.of("scope", "USER", "scopeId", owner.userId()),
                                "kind", "PREFERENCE",
                                "sourceId", "explicit-memory-test",
                                "sourceType", "TEST",
                                "content", "Prefer Java for backend services",
                                "confidence", 0.95))))
                .andExpect(status().isOk())
                .andReturn());
        String candidateId = candidate.path("id").asText();
        assertThat(candidateId).isNotBlank();

        JsonNode pending = data(mockMvc.perform(get("/api/v1/memory/candidates")
                        .queryParam("scope", "USER")
                        .queryParam("limit", "25")
                        .header("Authorization", bearer(owner.token())))
                .andExpect(status().isOk())
                .andReturn());
        assertThat(pending).hasSize(1);
        assertThat(pending.get(0).path("id").asText()).isEqualTo(candidateId);
        mockMvc.perform(get("/api/v1/memory/candidates")
                        .queryParam("scope", "USER")
                        .queryParam("scopeId", owner.userId())
                        .header("Authorization", bearer(foreign.token())))
                .andExpect(status().isNotFound());

        mockMvc.perform(get("/api/v1/memory/candidates/" + candidateId)
                        .header("Authorization", bearer(owner.token())))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/memory/candidates/" + candidateId)
                        .header("Authorization", bearer(foreign.token())))
                .andExpect(status().isNotFound());
        mockMvc.perform(post("/api/v1/memory/candidates/" + candidateId + "/review")
                        .header("Authorization", bearer(foreign.token()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"decision\":\"ACCEPTED\"}"))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/v1/memory")
                        .queryParam("scope", "USER")
                        .queryParam("scopeId", owner.userId())
                        .header("Authorization", bearer(foreign.token())))
                .andExpect(status().isNotFound());

        JsonNode unchanged = data(mockMvc.perform(get("/api/v1/memory/candidates/" + candidateId)
                        .header("Authorization", bearer(owner.token())))
                .andExpect(status().isOk())
                .andReturn());
        assertThat(unchanged.path("state").asText()).isEqualTo("PENDING");
    }

    @Test
    void projectAndTaskScopesRequireCanonicalProjectMembership() throws Exception {
        Identity userA = register("memory-project-user-a@example.com");
        Identity userB = register("memory-project-user-b@example.com");
        String projectA = "project-a";
        String projectB = "project-b";
        String taskB = "task-b";
        projectOwnership.grant(projectA, userA.userId(), userA.tenantId());
        projectOwnership.grant(projectB, userB.userId(), userB.tenantId());
        projectOwnership.linkTask(taskB, projectB);

        JsonNode projectCandidate = data(mockMvc.perform(post("/api/v1/memory/candidates")
                        .header("Authorization", bearer(userB.token()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(candidateRequest(
                                "PROJECT", projectB, "project-b-source", "Project B preference"))))
                .andExpect(status().isOk())
                .andReturn());
        String projectCandidateId = projectCandidate.path("id").asText();

        JsonNode taskCandidate = data(mockMvc.perform(post("/api/v1/memory/candidates")
                        .header("Authorization", bearer(userB.token()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "scope", Map.of("scope", "TASK", "scopeId", taskB),
                                "kind", "DECISION",
                                "sourceId", "task-b-source",
                                "sourceType", "TEST",
                                "content", "Use PostgreSQL as the durable task state store",
                                "confidence", 0.95))))
                .andExpect(status().isOk())
                .andReturn());
        String taskCandidateId = taskCandidate.path("id").asText();

        mockMvc.perform(post("/api/v1/memory/candidates")
                        .header("Authorization", bearer(userA.token()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(candidateRequest(
                                "PROJECT", projectB, "user-a-source", "Foreign project pollution"))))
                .andExpect(status().isNotFound());
        mockMvc.perform(post("/api/v1/memory/candidates")
                        .header("Authorization", bearer(userA.token()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(candidateRequest(
                                "TASK", taskB, "user-a-source", "Foreign task pollution"))))
                .andExpect(status().isNotFound());
        mockMvc.perform(post("/api/v1/memory/candidates/" + projectCandidateId + "/review")
                        .header("Authorization", bearer(userA.token()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"decision\":\"ACCEPTED\"}"))
                .andExpect(status().isNotFound());
        mockMvc.perform(post("/api/v1/memory/candidates/" + taskCandidateId + "/review")
                        .header("Authorization", bearer(userA.token()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"decision\":\"ACCEPTED\"}"))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/v1/memory")
                        .queryParam("scope", "PROJECT")
                        .queryParam("scopeId", projectB)
                        .header("Authorization", bearer(userA.token())))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/v1/memory")
                        .queryParam("scope", "TASK")
                        .queryParam("scopeId", taskB)
                        .header("Authorization", bearer(userA.token())))
                .andExpect(status().isNotFound());
        mockMvc.perform(post("/api/v1/memory/consolidate")
                        .header("Authorization", bearer(userA.token()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "taskId", taskB,
                                "projectId", projectA,
                                "userId", userA.userId(),
                                "acceptThreshold", 0.8,
                                "promotionThreshold", 0.9))))
                .andExpect(status().isNotFound());

        mockMvc.perform(post("/api/v1/memory/consolidate")
                        .header("Authorization", bearer(userB.token()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "taskId", taskB,
                                "projectId", projectB,
                                "userId", userB.userId(),
                                "acceptThreshold", 0.8,
                                "promotionThreshold", 0.9))))
                .andExpect(status().isOk());
        JsonNode projectMemories = data(mockMvc.perform(get("/api/v1/memory")
                        .queryParam("scope", "PROJECT")
                        .queryParam("scopeId", projectB)
                        .header("Authorization", bearer(userB.token())))
                .andExpect(status().isOk())
                .andReturn());
        JsonNode taskMemories = data(mockMvc.perform(get("/api/v1/memory")
                        .queryParam("scope", "TASK")
                        .queryParam("scopeId", taskB)
                        .header("Authorization", bearer(userB.token())))
                .andExpect(status().isOk())
                .andReturn());
        assertThat(projectMemories.size()).isGreaterThan(0);
        assertThat(taskMemories.size()).isGreaterThan(0);
    }

    private Map<String, Object> candidateRequest(
            String scope,
            String scopeId,
            String sourceId,
            String content) {
        return Map.of(
                "scope", Map.of("scope", scope, "scopeId", scopeId),
                "kind", "PREFERENCE",
                "sourceId", sourceId,
                "sourceType", "TEST",
                "content", content,
                "confidence", 0.95);
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
        return new Identity(auth.path("userId").asText(), auth.path("token").asText(), auth.path("tenantId").asText());
    }

    private JsonNode data(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsByteArray()).path("data");
    }

    private String bearer(String token) {
        return "Bearer " + token;
    }

    private record Identity(String userId, String token, String tenantId) {
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class ProjectOwnershipTestConfiguration {

        @Bean
        @Primary
        TestProjectOwnershipPort testProjectOwnershipPort() {
            return new TestProjectOwnershipPort();
        }
    }

    static final class TestProjectOwnershipPort implements ProjectOwnershipPort {
        private final Map<String, Set<String>> projectMembers = new HashMap<>();
        private final Map<String, String> taskProjects = new HashMap<>();
        private final Map<String, String> projectTenants = new HashMap<>();

        void reset() {
            projectMembers.clear();
            taskProjects.clear();
            projectTenants.clear();
        }

        void grant(String projectId, String userId, String tenantId) {
            projectTenants.put(projectId, tenantId);
            projectMembers.computeIfAbsent(projectId, ignored -> new HashSet<>()).add(userId);
        }

        void linkTask(String taskId, String projectId) {
            taskProjects.put(taskId, projectId);
        }

        @Override
        public boolean isOwnerOrMember(String projectId, String principalId) {
            return projectMembers.getOrDefault(projectId, Set.of()).contains(principalId);
        }

        @Override
        public Optional<String> findProjectIdByTask(String taskId) {
            return Optional.ofNullable(taskProjects.get(taskId));
        }

        @Override
        public Optional<String> findTenantIdByProject(String projectId) {
            return Optional.ofNullable(projectTenants.get(projectId));
        }
    }
}

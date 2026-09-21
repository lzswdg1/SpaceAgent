package com.spaceagent.platform.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class PlatformSkillRegistryHttpTest {
    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;

    @Test
    void publishesImmutableVersionsAndBindsOnlyCurrentPublishedVersion() throws Exception {
        String token = register("skill-owner");
        JsonNode skill = data(mockMvc.perform(post("/api/v1/skills")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "name", "Repository analyst",
                                "description", "Read-only repository analysis",
                                "instructions", "Inspect evidence before answering.",
                                "requiredToolIds", List.of("echo")))))
                .andExpect(status().isCreated()).andReturn());
        String skillId = skill.path("id").asText();
        String version1 = skill.path("versions").get(0).path("id").asText();
        assertThat(skill.path("versions").get(0).path("status").asText()).isEqualTo("DRAFT");

        createAgent(token, "draft-binding", version1, 409);
        JsonNode published1 = data(mockMvc.perform(post(
                                "/api/v1/skills/" + skillId + "/versions/" + version1 + "/publish")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk()).andReturn());
        assertThat(published1.path("currentVersionId").asText()).isEqualTo(version1);
        mockMvc.perform(post("/api/v1/agents")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "name", "missing-required-tool",
                                "skillIds", List.of(version1)))))
                .andExpect(status().isConflict());
        createAgent(token, "v1", version1, 201);

        JsonNode version2 = data(mockMvc.perform(post("/api/v1/skills/" + skillId + "/versions")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "instructions", "Inspect evidence and summarize the diff.",
                                "requiredToolIds", List.of("echo")))))
                .andExpect(status().isCreated()).andReturn());
        String version2Id = version2.path("id").asText();
        JsonNode published2 = data(mockMvc.perform(post(
                                "/api/v1/skills/" + skillId + "/versions/" + version2Id + "/publish")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk()).andReturn());
        assertThat(published2.path("currentVersionId").asText()).isEqualTo(version2Id);
        assertThat(published2.path("versions").toString())
                .contains("DEPRECATED", "PUBLISHED");
        createAgent(token, "deprecated-v1", version1, 409);
        JsonNode agent = createAgent(token, "v2", version2Id, 201);
        assertThat(agent.path("skillIds").get(0).asText()).isEqualTo(version2Id);

        mockMvc.perform(delete("/api/v1/skills/" + skillId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());
        createAgent(token, "archived-binding", version2Id, 409);
    }

    @Test
    void rejectsUnknownToolsAndCrossTenantReads() throws Exception {
        String owner = register("skill-scope-owner");
        mockMvc.perform(post("/api/v1/skills")
                        .header("Authorization", "Bearer " + owner)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "name", "Unsafe",
                                "instructions", "Do something.",
                                "requiredToolIds", List.of("not-a-runtime-tool")))))
                .andExpect(status().isBadRequest());
        JsonNode skill = data(mockMvc.perform(post("/api/v1/skills")
                        .header("Authorization", "Bearer " + owner)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "name", "Scoped",
                                "instructions", "Use bounded evidence."))))
                .andExpect(status().isCreated()).andReturn());
        String outsider = register("skill-scope-outsider");
        mockMvc.perform(get("/api/v1/skills/" + skill.path("id").asText())
                        .header("Authorization", "Bearer " + outsider))
                .andExpect(status().isNotFound());
    }

    private String register(String prefix) throws Exception {
        JsonNode auth = data(mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "username", prefix + "-"
                                        + UUID.randomUUID().toString().substring(0, 8)
                                        + "@example.com",
                                "password", "password123", "displayName", prefix))))
                .andExpect(status().isOk()).andReturn());
        return auth.path("token").asText();
    }

    private JsonNode createAgent(
            String token, String name, String skillVersionId, int expectedStatus) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/agents")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "name", name,
                                "enabledToolIds", List.of("echo"),
                                "skillIds", List.of(skillVersionId)))))
                .andExpect(status().is(expectedStatus)).andReturn();
        return expectedStatus < 300 ? data(result) : objectMapper.createObjectNode();
    }

    private JsonNode data(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsByteArray()).path("data");
    }
}

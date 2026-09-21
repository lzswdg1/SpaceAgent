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

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class PlatformObservabilityHttpTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void organizationOwnerReadsMonitoringContractAndMemberIsDenied() throws Exception {
        Identity owner = register("observability-owner@example.com");

        JsonNode overview = data(mockMvc.perform(get("/api/v1/monitoring/overview")
                        .queryParam("range", "7d")
                        .header("Authorization", bearer(owner.token())))
                .andExpect(status().isOk())
                .andReturn());
        assertThat(overview.path("overview").path("totalOrganizations").asLong()).isEqualTo(1);
        assertThat(overview.path("overview").path("range").asText()).isEqualTo("7d");
        assertThat(overview.path("overview").path("totalCostUsd").isNull()).isTrue();
        assertThat(overview.path("timeseries").isArray()).isTrue();

        JsonNode usage = data(mockMvc.perform(get("/api/v1/monitoring/usage")
                        .queryParam("range", "today")
                        .queryParam("groupBy", "provider")
                        .header("Authorization", bearer(owner.token())))
                .andExpect(status().isOk())
                .andReturn());
        assertThat(usage.path("groupBy").asText()).isEqualTo("provider");
        assertThat(usage.path("rows").isArray()).isTrue();

        for (String range : java.util.List.of("1d", "7d", "all")) {
            JsonNode ranged = data(mockMvc.perform(get("/api/v1/monitoring/overview")
                            .queryParam("range", range).header("Authorization", bearer(owner.token())))
                    .andExpect(status().isOk()).andReturn());
            assertThat(ranged.path("overview").path("range").asText()).isEqualTo(range);
            mockMvc.perform(get("/api/v1/monitoring/sessions").queryParam("range", range)
                            .header("Authorization", bearer(owner.token())))
                    .andExpect(status().isOk());
        }
        JsonNode agents = data(mockMvc.perform(get("/api/v1/monitoring/usage/agents")
                        .queryParam("range", "today").header("Authorization", bearer(owner.token())))
                .andExpect(status().isOk()).andReturn());
        assertThat(agents.path("range").asText()).isEqualTo("all");
        assertThat(agents.path("groupBy").asText()).isEqualTo("agent");
        for (String endpoint : java.util.List.of("overview", "usage", "sessions")) {
            mockMvc.perform(get("/api/v1/monitoring/" + endpoint).queryParam("range", "invalid")
                            .header("Authorization", bearer(owner.token())))
                    .andExpect(status().isBadRequest());
        }
        mockMvc.perform(get("/api/v1/monitoring/usage/agents")).andExpect(status().isUnauthorized());

        JsonNode realtime = data(mockMvc.perform(get("/api/v1/monitoring/realtime")
                        .header("Authorization", bearer(owner.token())))
                .andExpect(status().isOk())
                .andReturn());
        assertThat(realtime.path("runtimeActive").asLong()).isZero();
        assertThat(realtime.path("ts").asText()).isNotBlank();

        Identity member = register("observability-member@example.com");
        mockMvc.perform(post("/api/v1/organizations/" + owner.tenantId() + "/members")
                        .header("Authorization", bearer(owner.token()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "userId", member.userId(), "role", "MEMBER"))))
                .andExpect(status().isCreated());
        String memberTenantToken = data(mockMvc.perform(post(
                                "/api/v1/organizations/" + owner.tenantId() + "/switch")
                        .header("Authorization", bearer(member.token())))
                .andExpect(status().isOk())
                .andReturn()).path("token").asText();
        mockMvc.perform(get("/api/v1/monitoring/overview")
                        .header("Authorization", bearer(memberTenantToken)))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/v1/monitoring/usage/agents")
                        .header("Authorization", bearer(memberTenantToken)))
                .andExpect(status().isForbidden());
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
                auth.path("token").asText());
    }

    private JsonNode data(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsByteArray()).path("data");
    }

    private String bearer(String token) {
        return "Bearer " + token;
    }

    private record Identity(String userId, String tenantId, String token) {}
}

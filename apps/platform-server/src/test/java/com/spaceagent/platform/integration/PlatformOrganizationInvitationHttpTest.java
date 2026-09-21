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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class PlatformOrganizationInvitationHttpTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void invitationHttpLifecycleIsPublicOnlyForMaskedPreviewAndAcceptsOnce() throws Exception {
        Identity owner = register("http-invite-owner@example.com");
        Identity invitee = register("http-invite-member@example.com");
        Identity stranger = register("http-invite-stranger@example.com");
        String organizationId = createOrganization(
                owner.token(), "HTTP Invitation", "http-invitation");

        JsonNode created = data(mockMvc.perform(post(
                                "/api/v1/organizations/" + organizationId + "/invitations")
                        .header("Authorization", bearer(owner.token()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "email", "HTTP-INVITE-MEMBER@example.com",
                                "role", "MEMBER",
                                "expiresInHours", 24))))
                .andExpect(status().isCreated())
                .andReturn());
        String token = created.path("token").asText();
        String invitationId = created.path("invitation").path("id").asText();
        assertThat(token).hasSize(43);

        JsonNode preview = data(mockMvc.perform(post(
                                "/api/v1/public/organization-invitations/preview")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(tokenJson(token)))
                .andExpect(status().isOk())
                .andReturn());
        assertThat(preview.path("organizationName").asText()).isEqualTo("HTTP Invitation");
        assertThat(preview.path("maskedEmail").asText())
                .isEqualTo("h***@example.com");
        assertThat(preview.has("email")).isFalse();

        mockMvc.perform(post("/api/v1/organization-invitations/accept")
                        .header("Authorization", bearer(stranger.token()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(tokenJson(token)))
                .andExpect(status().isForbidden());

        JsonNode accepted = data(mockMvc.perform(post(
                                "/api/v1/organization-invitations/accept")
                        .header("Authorization", bearer(invitee.token()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(tokenJson(token)))
                .andExpect(status().isOk())
                .andReturn());
        assertThat(accepted.path("invitationId").asText()).isEqualTo(invitationId);
        assertThat(accepted.path("membership").path("role").asText()).isEqualTo("MEMBER");

        mockMvc.perform(post("/api/v1/organization-invitations/accept")
                        .header("Authorization", bearer(invitee.token()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(tokenJson(token)))
                .andExpect(status().isConflict());

        JsonNode list = data(mockMvc.perform(get(
                                "/api/v1/organizations/" + organizationId + "/invitations")
                        .header("Authorization", bearer(owner.token())))
                .andExpect(status().isOk())
                .andReturn());
        assertThat(list).hasSize(1);
        assertThat(list.get(0).path("status").asText()).isEqualTo("ACCEPTED");

        mockMvc.perform(post("/api/v1/organizations/" + organizationId + "/invitations")
                        .header("Authorization", bearer(invitee.token()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "email", "another@example.com", "role", "VIEWER"))))
                .andExpect(status().isForbidden());

        JsonNode revocable = data(mockMvc.perform(post(
                                "/api/v1/organizations/" + organizationId + "/invitations")
                        .header("Authorization", bearer(owner.token()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "email", "revoke@example.com", "role", "VIEWER"))))
                .andExpect(status().isCreated())
                .andReturn());
        mockMvc.perform(delete("/api/v1/organizations/" + organizationId
                        + "/invitations/" + revocable.path("invitation").path("id").asText())
                        .header("Authorization", bearer(owner.token())))
                .andExpect(status().isOk());
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
        return new Identity(auth.path("userId").asText(), auth.path("token").asText());
    }

    private String createOrganization(String token, String name, String slug) throws Exception {
        return data(mockMvc.perform(post("/api/v1/organizations")
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "name", name, "slug", slug))))
                .andExpect(status().isCreated())
                .andReturn()).path("id").asText();
    }

    private String tokenJson(String token) throws Exception {
        return objectMapper.writeValueAsString(Map.of("token", token));
    }

    private JsonNode data(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsByteArray()).path("data");
    }

    private static String bearer(String token) {
        return "Bearer " + token;
    }

    private record Identity(String userId, String token) {
    }
}

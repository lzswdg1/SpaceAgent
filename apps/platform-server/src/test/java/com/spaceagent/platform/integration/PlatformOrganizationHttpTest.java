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
import com.spaceagent.platform.conversation.api.ConversationApplicationApi;
import com.spaceagent.platform.conversation.api.StartConversationCommand;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class PlatformOrganizationHttpTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private ConversationApplicationApi conversations;

    @Test
    void organizationLifecycleSupportsCreateSwitchTransferLeaveAndEmptyCleanup() throws Exception {
        Identity alice = register("organization-alice@example.com");
        Identity bob = register("organization-bob@example.com");
        Identity charlie = register("organization-charlie@example.com");

        JsonNode initial = data(mockMvc.perform(get("/api/v1/organizations")
                        .header("Authorization", bearer(alice.token())))
                .andExpect(status().isOk())
                .andReturn());
        assertThat(initial).hasSize(1);
        assertThat(initial.get(0).path("organization").path("creatorUserId").asText())
                .isEqualTo(alice.userId());
        assertThat(initial.get(0).path("membership").path("role").asText())
                .isEqualTo("OWNER");

        String sharedId = createOrganization(alice.token(), "Shared Org", "shared-org");
        mockMvc.perform(post("/api/v1/organizations/" + sharedId + "/members")
                        .header("Authorization", bearer(alice.token()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(memberJson(bob.userId(), "MEMBER")))
                .andExpect(status().isConflict());

        String aliceSharedToken = switchOrganization(alice.token(), sharedId);
        addMember(aliceSharedToken, sharedId, bob.userId(), "MEMBER");
        addMember(aliceSharedToken, sharedId, charlie.userId(), "ADMIN");

        String bobSharedToken = switchOrganization(bob.token(), sharedId);
        mockMvc.perform(post("/api/v1/organizations/" + sharedId + "/members")
                        .header("Authorization", bearer(bobSharedToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(memberJson(charlie.userId(), "VIEWER")))
                .andExpect(status().isForbidden());

        JsonNode transferred = data(mockMvc.perform(post(
                                "/api/v1/organizations/" + sharedId + "/transfer-owner")
                        .header("Authorization", bearer(aliceSharedToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "newOwnerUserId", bob.userId()))))
                .andExpect(status().isOk())
                .andReturn());
        assertThat(transferred.path("creatorUserId").asText()).isEqualTo(bob.userId());

        mockMvc.perform(get("/api/v1/organizations/" + sharedId)
                        .header("Authorization", bearer(bobSharedToken)))
                .andExpect(status().isUnauthorized());
        String bobOwnerToken = switchOrganization(bob.token(), sharedId);
        mockMvc.perform(post("/api/v1/organizations/" + sharedId + "/leave")
                        .header("Authorization", bearer(bobOwnerToken)))
                .andExpect(status().isConflict());
        JsonNode aliceLeft = data(mockMvc.perform(post(
                                "/api/v1/organizations/" + sharedId + "/leave")
                        .header("Authorization", bearer(alice.token())))
                .andExpect(status().isOk())
                .andReturn());
        assertThat(aliceLeft.path("remainingActiveMembers").asLong()).isEqualTo(2L);

        String soloId = createOrganization(alice.token(), "Disposable Org", "disposable-org");
        String soloToken = switchOrganization(alice.token(), soloId);
        JsonNode deleted = data(mockMvc.perform(post(
                                "/api/v1/organizations/" + soloId + "/leave")
                        .header("Authorization", bearer(soloToken)))
                .andExpect(status().isOk())
                .andReturn());
        assertThat(deleted.path("organizationStatus").asText()).isEqualTo("DELETING");
        assertThat(deleted.path("remainingActiveMembers").asLong()).isZero();

        mockMvc.perform(post("/api/v1/organizations/" + soloId + "/switch")
                        .header("Authorization", bearer(alice.token())))
                .andExpect(status().isConflict());
    }

    @Test
    void loginFallsBackToAnotherActiveOrganizationAfterPrimaryMigration() throws Exception {
        String username = "organization-migrate@example.com";
        Identity user = register(username);
        String destinationId = createOrganization(
                user.token(), "Destination Org", "destination-org");
        String destinationToken = switchOrganization(user.token(), destinationId);

        JsonNode left = data(mockMvc.perform(post(
                                "/api/v1/organizations/" + user.tenantId() + "/leave")
                        .header("Authorization", bearer(destinationToken)))
                .andExpect(status().isOk())
                .andReturn());
        assertThat(left.path("organizationStatus").asText()).isEqualTo("DELETING");

        JsonNode loggedIn = data(mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "username", username,
                                "password", "password123"))))
                .andExpect(status().isOk())
                .andReturn());
        assertThat(loggedIn.path("tenantId").asText()).isEqualTo(destinationId);
    }

    @Test
    void activeOrganizationCannotReadConversationFromAnotherTenant() throws Exception {
        Identity user = register("organization-conversation-isolation@example.com");
        String conversationId = conversations.start(new StartConversationCommand(
                null, null, user.tenantId(), user.userId(), "agent-1", "Private tenant chat")).id();
        String otherOrganization = createOrganization(user.token(), "Other Org", "other-chat-org");
        String otherToken = switchOrganization(user.token(), otherOrganization);

        JsonNode listed = data(mockMvc.perform(get("/api/v1/chat/conversations")
                        .header("Authorization", bearer(otherToken)))
                .andExpect(status().isOk()).andReturn());
        assertThat(listed.path("items").toString()).doesNotContain(conversationId);
        mockMvc.perform(get("/api/v1/chat/conversations/" + conversationId)
                        .header("Authorization", bearer(otherToken)))
                .andExpect(status().isNotFound());
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

    private String createOrganization(String token, String name, String slug) throws Exception {
        return data(mockMvc.perform(post("/api/v1/organizations")
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "name", name,
                                "slug", slug))))
                .andExpect(status().isCreated())
                .andReturn()).path("id").asText();
    }

    private String switchOrganization(String token, String organizationId) throws Exception {
        JsonNode switched = data(mockMvc.perform(post(
                                "/api/v1/organizations/" + organizationId + "/switch")
                        .header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andReturn());
        assertThat(switched.path("tenantId").asText()).isEqualTo(organizationId);
        assertThat(switched.path("refreshToken").asText()).isNotBlank();
        return switched.path("token").asText();
    }

    private void addMember(
            String token,
            String organizationId,
            String userId,
            String role) throws Exception {
        mockMvc.perform(post("/api/v1/organizations/" + organizationId + "/members")
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(memberJson(userId, role)))
                .andExpect(status().isCreated());
    }

    private String memberJson(String userId, String role) throws Exception {
        return objectMapper.writeValueAsString(Map.of("userId", userId, "role", role));
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

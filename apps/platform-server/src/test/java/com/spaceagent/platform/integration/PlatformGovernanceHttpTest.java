package com.spaceagent.platform.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.spaceagent.platform.governance.api.GovernanceApplicationApi;
import com.spaceagent.platform.governance.domain.ApprovalState;
import com.spaceagent.platform.governance.domain.GovernanceActionType;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class PlatformGovernanceHttpTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private GovernanceApplicationApi governance;

    @Test
    void organizationAdminsManagePolicyAndSeparateApprovalDecision() throws Exception {
        Identity owner = register("governance-owner@example.com");
        JsonNode defaults = data(mockMvc.perform(get("/api/v1/governance/policy")
                        .header("Authorization", bearer(owner.token())))
                .andExpect(status().isOk())
                .andReturn());
        assertThat(defaults.path("revision").asLong()).isZero();

        JsonNode policy = data(mockMvc.perform(put("/api/v1/governance/policy")
                        .header("Authorization", bearer(owner.token()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "requireCodingFileApproval", true,
                                "requireCommandApproval", true,
                                "requireAutomationApproval", true,
                                "requireNetworkApproval", true,
                                "requireSourceMergeApproval", true,
                                "separationOfDuties", true,
                                "approvalTtlSeconds", 3600,
                                "expectedRevision", 0))))
                .andExpect(status().isOk())
                .andReturn());
        assertThat(policy.path("revision").asLong()).isEqualTo(1);
        assertThat(policy.path("requireCommandApproval").asBoolean()).isTrue();

        var required = governance.authorize(new GovernanceApplicationApi.AuthorizeCommand(
                owner.tenantId(), owner.userId(), GovernanceActionType.COMMAND_EXECUTION,
                "WORKSPACE", "workspace", "sha256:" + "a".repeat(64),
                "Run release command", null));
        String approvalId = required.approval().id();
        JsonNode approvals = data(mockMvc.perform(get("/api/v1/governance/approvals")
                        .queryParam("state", "PENDING")
                        .header("Authorization", bearer(owner.token())))
                .andExpect(status().isOk())
                .andReturn());
        assertThat(approvals).hasSize(1);
        assertThat(approvals.get(0).path("id").asText()).isEqualTo(approvalId);

        mockMvc.perform(post("/api/v1/governance/approvals/" + approvalId + "/decision")
                        .header("Authorization", bearer(owner.token()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "decision", "APPROVED", "note", "self"))))
                .andExpect(status().isForbidden());

        Identity administrator = register("governance-admin@example.com");
        addMember(owner, administrator.userId(), "ADMIN");
        String adminToken = switchOrganization(administrator, owner.tenantId());
        JsonNode decided = data(mockMvc.perform(post(
                                "/api/v1/governance/approvals/" + approvalId + "/decision")
                        .header("Authorization", bearer(adminToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "decision", "APPROVED", "note", "reviewed"))))
                .andExpect(status().isOk())
                .andReturn());
        assertThat(decided.path("state").asText()).isEqualTo(ApprovalState.APPROVED.name());
        assertThat(decided.path("decidedBy").asText()).isEqualTo(administrator.userId());

        Identity member = register("governance-member@example.com");
        addMember(owner, member.userId(), "MEMBER");
        String memberToken = switchOrganization(member, owner.tenantId());
        mockMvc.perform(get("/api/v1/governance/policy")
                        .header("Authorization", bearer(memberToken)))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/v1/governance/approvals")
                        .header("Authorization", bearer(memberToken)))
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

    private void addMember(Identity owner, String userId, String role) throws Exception {
        mockMvc.perform(post("/api/v1/organizations/" + owner.tenantId() + "/members")
                        .header("Authorization", bearer(owner.token()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "userId", userId, "role", role))))
                .andExpect(status().isCreated());
    }

    private String switchOrganization(Identity identity, String tenantId) throws Exception {
        return data(mockMvc.perform(post("/api/v1/organizations/" + tenantId + "/switch")
                        .header("Authorization", bearer(identity.token())))
                .andExpect(status().isOk())
                .andReturn()).path("token").asText();
    }

    private JsonNode data(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsByteArray()).path("data");
    }

    private String bearer(String token) {
        return "Bearer " + token;
    }

    private record Identity(String userId, String tenantId, String token) {}
}

package com.spaceagent.platform.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.spaceagent.platform.identity.api.AddTenantMembershipCommand;
import com.spaceagent.platform.identity.api.IdentityApplicationApi;
import com.spaceagent.platform.identity.domain.TenantRole;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import java.util.Map;
import java.util.UUID;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
class PlatformKnowledgeBaseHttpTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired IdentityApplicationApi identity;
    private static final String BASES = "/api/v1/knowledge/bases";

    @Test void personalCrudIsPrivateRevisionCheckedAndCannotBeShared() throws Exception {
        User owner = register(), stranger = register();
        JsonNode created = create(owner, "PERSONAL");
        String id = created.path("base").path("id").asText();
        assertThat(created.path("permission").asText()).isEqualTo("MANAGE");
        assertThat(created.path("base").path("organizationId").isNull()).isTrue();
        mvc.perform(auth(get(BASES + "/" + id), stranger)).andExpect(status().isNotFound());
        assertThat(data(mvc.perform(auth(get(BASES), stranger)).andExpect(status().isOk()).andReturn())
                .path("items").findValuesAsText("id")).doesNotContain(id);
        mvc.perform(body(put(BASES + "/" + id), owner, Map.of("name", "updated", "expectedRevision", 1)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.base.revision").value(2));
        mvc.perform(body(put(BASES + "/" + id), owner, Map.of("name", "stale", "expectedRevision", 1)))
                .andExpect(status().isConflict());
        mvc.perform(body(put(BASES + "/" + id + "/grants"), owner, Map.of(
                "subjectType", "USER", "subjectId", stranger.id(), "permission", "READ", "expectedRevision", 2)))
                .andExpect(status().isBadRequest());
        mvc.perform(auth(delete(BASES + "/" + id).param("expectedRevision", "2"), owner)).andExpect(status().isOk());
        mvc.perform(auth(get(BASES + "/" + id), owner)).andExpect(status().isNotFound());
    }

    @Test void organizationGrantRevokeAndPaginationUseCurrentPermissions() throws Exception {
        User owner = register(), member = join(register(), owner.org(), TenantRole.MEMBER), outsider = register();
        String id = create(owner, "ORGANIZATION").path("base").path("id").asText();
        mvc.perform(auth(get(BASES + "/" + id), member)).andExpect(status().isNotFound());
        mvc.perform(body(put(BASES + "/" + id + "/grants"), owner, Map.of(
                "subjectType", "USER", "subjectId", outsider.id(), "permission", "READ", "expectedRevision", 1)))
                .andExpect(status().isForbidden());
        grant(owner, id, "USER", member.id(), "WRITE", 1);
        mvc.perform(auth(get(BASES + "/" + id), member)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.permission").value("WRITE"));
        JsonNode visible = data(mvc.perform(auth(get(BASES).param("limit", "1"), member))
                .andExpect(status().isOk()).andReturn());
        assertThat(visible.path("items")).hasSize(1);
        assertThat(visible.path("items").get(0).path("base").path("id").asText()).isEqualTo(id);
        mvc.perform(body(put(BASES + "/" + id), member, Map.of("name", "unauthorized", "expectedRevision", 2)))
                .andExpect(status().isForbidden());
        mvc.perform(auth(delete(BASES + "/" + id + "/grants/USER/" + member.id()).param("expectedRevision", "2"), owner))
                .andExpect(status().isOk());
        mvc.perform(auth(get(BASES + "/" + id), member)).andExpect(status().isNotFound());
        assertThat(data(mvc.perform(auth(get(BASES), member)).andExpect(status().isOk()).andReturn()).path("items")).isEmpty();
    }

    @Test void roleGrantCannotBypassViewerAndManagerCannotChangeScope() throws Exception {
        User owner = register(), member = join(register(), owner.org(), TenantRole.MEMBER);
        User viewer = join(register(), owner.org(), TenantRole.VIEWER);
        String id = create(owner, "ORGANIZATION").path("base").path("id").asText();
        grant(owner, id, "ROLE", "MEMBER", "MANAGE", 1);
        mvc.perform(body(put(BASES + "/" + id), member, Map.of("name", "delegated", "expectedRevision", 2)))
                .andExpect(status().isOk());
        mvc.perform(body(put(BASES + "/" + id + "/grants"), owner, Map.of(
                "subjectType", "ROLE", "subjectId", "VIEWER", "permission", "MANAGE", "expectedRevision", 3)))
                .andExpect(status().isBadRequest());
        grant(owner, id, "ROLE", "VIEWER", "READ", 3);
        mvc.perform(auth(get(BASES + "/" + id), viewer)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.permission").value("READ"));
        mvc.perform(body(post(BASES), viewer, Map.of("scope", "ORGANIZATION", "name", "denied")))
                .andExpect(status().isForbidden());
        // Membership downgrade takes effect even while the caller retains its previous access token.
        identity.addTenantMembership(new AddTenantMembershipCommand(owner.org(), member.id(), TenantRole.VIEWER));
        mvc.perform(body(put(BASES + "/" + id), member, Map.of("name", "stale role", "expectedRevision", 4)))
                .andExpect(status().isUnauthorized());
        mvc.perform(body(put(BASES + "/" + id), viewer, Map.of("name", "viewer write", "expectedRevision", 4)))
                .andExpect(status().isForbidden());
    }

    @Test void organizationOwnerManagesMemberCreatedBaseButOtherOrganizationCannotRead() throws Exception {
        User owner = register(), member = join(register(), owner.org(), TenantRole.MEMBER), other = register();
        String id = create(member, "ORGANIZATION").path("base").path("id").asText();
        mvc.perform(body(put(BASES + "/" + id), owner, Map.of("name", "owner managed", "expectedRevision", 1)))
                .andExpect(status().isOk());
        mvc.perform(auth(get(BASES + "/" + id), other)).andExpect(status().isNotFound());
        // Same member switches away: the new organization context cannot resolve the old organization base.
        User anotherContext = join(member, other.org(), TenantRole.MEMBER);
        mvc.perform(auth(get(BASES + "/" + id), anotherContext)).andExpect(status().isNotFound());
        mvc.perform(auth(get(BASES).param("limit", "101"), owner)).andExpect(status().isBadRequest());
        mvc.perform(get(BASES)).andExpect(status().isUnauthorized());
    }

    private void grant(User actor, String base, String type, String subject, String permission, long revision) throws Exception {
        mvc.perform(body(put(BASES + "/" + base + "/grants"), actor, Map.of("subjectType", type,
                "subjectId", subject, "permission", permission, "expectedRevision", revision))).andExpect(status().isOk());
    }
    private JsonNode create(User user, String scope) throws Exception {
        return data(mvc.perform(body(post(BASES), user, Map.of("scope", scope, "name", "knowledge")))
                .andExpect(status().isCreated()).andReturn());
    }
    private User register() throws Exception {
        String username = "kb-" + UUID.randomUUID() + "@example.test";
        JsonNode auth = data(mvc.perform(post("/api/v1/auth/register").contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("username", username, "password", "password123", "displayName", "KB Test"))))
                .andExpect(status().isOk()).andReturn());
        return new User(auth.path("userId").asText(), auth.path("tenantId").asText(), auth.path("token").asText());
    }
    private User join(User user, String org, TenantRole role) throws Exception {
        identity.addTenantMembership(new AddTenantMembershipCommand(org, user.id(), role));
        JsonNode auth = data(mvc.perform(auth(post("/api/v1/organizations/" + org + "/switch"), user))
                .andExpect(status().isOk()).andReturn());
        return new User(user.id(), org, auth.path("token").asText());
    }
    private MockHttpServletRequestBuilder auth(MockHttpServletRequestBuilder request, User user) {
        return request.header("Authorization", "Bearer " + user.token());
    }
    private MockHttpServletRequestBuilder body(MockHttpServletRequestBuilder request, User user, Object body) throws Exception {
        return auth(request, user).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body));
    }
    private JsonNode data(MvcResult response) throws Exception { return json.readTree(response.getResponse().getContentAsByteArray()).path("data"); }
    private record User(String id, String org, String token) {}
}

package com.spaceagent.platform.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import java.util.Map;
import jakarta.servlet.http.Cookie;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Public HTTP coverage for the platform-owned Identity edge. */
@SpringBootTest
@AutoConfigureMockMvc
class PlatformIdentityHttpTest {

    private static final String JWT_SECRET = "local-dev-jwt-secret-should-be-overridden-12345";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void registerLoginAndCurrentUserPreserveIdentityContract() throws Exception {
        JsonNode registered = register("phase1-alice@example.com", "Alice");

        assertThat(registered.path("token").asText()).isNotBlank();
        assertThat(registered.path("refreshToken").asText()).isNotBlank();
        assertThat(registered.path("tenantId").asText()).isNotBlank();
        assertThat(registered.path("tenantRole").asText()).isEqualTo("OWNER");
        assertThat(registered.path("refreshExpiresAt").asText()).isNotBlank();

        JsonNode loggedIn = json(mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "username", "PHASE1-ALICE@EXAMPLE.COM",
                                "password", "password123"))))
                .andExpect(status().isOk())
                .andReturn()).path("data");

        assertThat(loggedIn.path("userId").asText()).isEqualTo(registered.path("userId").asText());
        assertThat(loggedIn.path("tenantId").asText()).isEqualTo(registered.path("tenantId").asText());

        JsonNode current = json(mockMvc.perform(get("/api/v1/users/me")
                        .header("Authorization", bearer(loggedIn.path("token").asText())))
                .andExpect(status().isOk())
                .andReturn()).path("data");

        assertThat(current.path("userId").asText()).isEqualTo(registered.path("userId").asText());
        assertThat(current.path("username").asText()).isEqualTo("phase1-alice@example.com");
        assertThat(current.path("tenantId").asText()).isEqualTo(registered.path("tenantId").asText());
        assertThat(current.path("tenantRole").asText()).isEqualTo("OWNER");
    }

    @Test
    void refreshRotatesOpaqueTokenAndRejectsReplay() throws Exception {
        JsonNode registered = register("phase1-refresh@example.com", "Refresh User");
        String originalRefreshToken = registered.path("refreshToken").asText();

        JsonNode refreshed = json(mockMvc.perform(post("/api/v1/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "refreshToken", originalRefreshToken))))
                .andExpect(status().isOk())
                .andReturn()).path("data");

        assertThat(refreshed.path("refreshToken").asText())
                .isNotBlank()
                .isNotEqualTo(originalRefreshToken);
        assertThat(refreshed.path("tenantId").asText()).isEqualTo(registered.path("tenantId").asText());

        mockMvc.perform(post("/api/v1/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "refreshToken", originalRefreshToken))))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void logoutRevokesAccessAndRefreshTokens() throws Exception {
        JsonNode registered = register("phase1-logout@example.com", "Logout User");
        String accessToken = registered.path("token").asText();
        String refreshToken = registered.path("refreshToken").asText();

        mockMvc.perform(post("/api/v1/auth/logout")
                        .header("Authorization", bearer(accessToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("refreshToken", refreshToken))))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/v1/users/me")
                        .header("Authorization", bearer(accessToken)))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(post("/api/v1/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("refreshToken", refreshToken))))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void logoutWithoutRefreshBodyRevokesTheWholeCurrentSessionButKeepsSiblingSession() throws Exception {
        JsonNode first = register("phase1-session-logout@example.com", "Session Logout User");
        JsonNode rotated = json(mockMvc.perform(post("/api/v1/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "refreshToken", first.path("refreshToken").asText()))))
                .andExpect(status().isOk())
                .andReturn()).path("data");
        JsonNode sibling = json(mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "username", "phase1-session-logout@example.com",
                                "password", "password123"))))
                .andExpect(status().isOk())
                .andReturn()).path("data");

        mockMvc.perform(get("/api/v1/users/me")
                        .header("Authorization", bearer(first.path("token").asText())))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/v1/auth/logout")
                        .header("Authorization", bearer(rotated.path("token").asText())))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/v1/users/me")
                        .header("Authorization", bearer(first.path("token").asText())))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/v1/users/me")
                        .header("Authorization", bearer(rotated.path("token").asText())))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/v1/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "refreshToken", rotated.path("refreshToken").asText()))))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(get("/api/v1/users/me")
                        .header("Authorization", bearer(sibling.path("token").asText())))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/v1/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "refreshToken", sibling.path("refreshToken").asText()))))
                .andExpect(status().isOk());
    }

    @Test
    void changePasswordRequiresCurrentSecretAndRevokesExistingRefreshSessions() throws Exception {
        JsonNode registered = register("phase1-password@example.com", "Password User");

        mockMvc.perform(post("/api/v1/auth/change-password")
                        .header("Authorization", bearer(registered.path("token").asText()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "oldPassword", "password123",
                                "newPassword", "new-password-456"))))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/v1/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "refreshToken", registered.path("refreshToken").asText()))))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "username", "phase1-password@example.com",
                                "password", "password123"))))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "username", "phase1-password@example.com",
                                "password", "new-password-456"))))
                .andExpect(status().isOk());
    }

    @Test
    void authorizationRejectsMissingAuthenticationAndForeignTenantClaim() throws Exception {
        mockMvc.perform(get("/api/v1/users/me"))
                .andExpect(status().isUnauthorized());

        JsonNode alice = register("phase1-owner@example.com", "Owner");
        JsonNode bob = register("phase1-foreign@example.com", "Foreign");
        String forged = tokenFor(
                alice.path("userId").asText(),
                "phase1-owner@example.com",
                bob.path("tenantId").asText());

        mockMvc.perform(get("/api/v1/users/me")
                        .header("Authorization", bearer(forged)))
                .andExpect(status().isForbidden());
    }

    @Test
    void browserSessionKeepsRefreshTokenOnlyInHttpOnlyCookie() throws Exception {
        MvcResult registeredResult = mockMvc.perform(post("/api/v1/web/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "username", "browser-cookie@example.com",
                                "password", "password123",
                                "displayName", "Browser Cookie"))))
                .andExpect(status().isOk()).andReturn();
        JsonNode registered = json(registeredResult).path("data");
        String setCookie = registeredResult.getResponse().getHeader("Set-Cookie");
        assertThat(registered.has("refreshToken")).isFalse();
        assertThat(setCookie).contains("spaceagent_refresh=", "HttpOnly", "SameSite=Strict",
                "Path=/api/v1/web");
        String refreshToken = setCookie.substring(
                "spaceagent_refresh=".length(), setCookie.indexOf(';'));

        MvcResult refreshedResult = mockMvc.perform(post("/api/v1/web/auth/refresh")
                        .cookie(new Cookie("spaceagent_refresh", refreshToken)))
                .andExpect(status().isOk()).andReturn();
        JsonNode refreshed = json(refreshedResult).path("data");
        assertThat(refreshed.has("refreshToken")).isFalse();
        assertThat(refreshed.path("token").asText()).isNotBlank();
        assertThat(refreshedResult.getResponse().getHeader("Set-Cookie"))
                .contains("spaceagent_refresh=").doesNotContain(refreshToken);

        mockMvc.perform(post("/api/v1/web/auth/logout")
                        .header("Authorization", bearer(refreshed.path("token").asText()))
                        .cookie(new Cookie("spaceagent_refresh", cookieValue(
                                refreshedResult.getResponse().getHeader("Set-Cookie")))))
                .andExpect(status().isOk())
                .andExpect(result -> assertThat(result.getResponse().getHeader("Set-Cookie"))
                        .contains("Max-Age=0"));
    }

    private JsonNode register(String username, String displayName) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "username", username,
                                "password", "password123",
                                "displayName", displayName))))
                .andExpect(status().isOk())
                .andReturn();
        return json(result).path("data");
    }

    private String tokenFor(String userId, String username, String tenantId) {
        SecretKey key = Keys.hmacShaKeyFor(JWT_SECRET.getBytes(StandardCharsets.UTF_8));
        Instant now = Instant.now();
        return Jwts.builder()
                .subject(userId)
                .claim("username", username)
                .claim("role", "USER")
                .claim("tenant_id", tenantId)
                .claim("tenant_role", "OWNER")
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plus(1, ChronoUnit.HOURS)))
                .signWith(key)
                .compact();
    }

    private JsonNode json(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsByteArray());
    }

    private String bearer(String token) {
        return "Bearer " + token;
    }

    private String cookieValue(String setCookie) {
        return setCookie.substring("spaceagent_refresh=".length(), setCookie.indexOf(';'));
    }
}

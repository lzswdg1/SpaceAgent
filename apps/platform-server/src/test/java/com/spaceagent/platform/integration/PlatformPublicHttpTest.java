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

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Public HTTP contract tests for the active platform-server edge.
 *
 * <p>These tests do not compare implementation classes. They exercise the same
 * public HTTP behavior required by first-party clients: identity/auth,
 * agent CRUD, conversation/message state, chat SSE, model-provider configuration,
 * and knowledge document/chunk state.
 */
@SpringBootTest
@AutoConfigureMockMvc
class PlatformPublicHttpTest {
    @Autowired PublishedAgentFixture publishedAgentFixture;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void identityAuthAndCurrentUserAreCompatible() throws Exception {
        String token = registerAndLogin("alice@example.com");

        MvcResult currentUser = mockMvc.perform(get("/api/v1/users/me")
                        .header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode current = json(currentUser).path("data");
        assertThat(current.path("username").asText()).isEqualTo("alice@example.com");
        assertThat(current.path("userId").asText()).isNotBlank();
        assertThat(current.path("role").asText()).isEqualTo("USER");
    }

    @Test
    void agentConversationMessageAndChatSseAreCompatible() throws Exception {
        String token = registerAndLogin("builder@example.com");

        JsonNode provider = createProvider(token);
        String providerId = provider.path("id").asText();
        String modelId = "gpt-4o";

        JsonNode agent = json(mockMvc.perform(post("/api/v1/agents")
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "name", "builder",
                                "modelProviderId", providerId,
                                "modelId", modelId))))
                .andExpect(status().isCreated())
                .andReturn()).path("data");
        String agentId = agent.path("id").asText();
        assertThat(agentId).isNotBlank();
        publishedAgentFixture.publish(agentId);

        JsonNode conversation = json(mockMvc.perform(post("/api/v1/chat/conversations")
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "agentId", agentId,
                                "name", "Compat conversation"))))
                .andExpect(status().isOk())
                .andReturn()).path("data");
        String conversationId = conversation.path("conversationId").asText();
        assertThat(conversationId).isNotBlank();

        Map<String, Object> chatRequest = new LinkedHashMap<>();
        chatRequest.put("conversationId", conversationId);
        chatRequest.put("agentId", agentId);
        chatRequest.put("message", "Hello compatibility");

        JsonNode chat = json(mockMvc.perform(post("/api/v1/chat/messages")
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(chatRequest)))
                .andExpect(status().isOk())
                .andReturn()).path("data");
        assertThat(chat.path("conversationId").asText()).isEqualTo(conversationId);
        assertThat(chat.path("assistantMessage").asText()).contains("noop-inference");

        JsonNode messages = json(mockMvc.perform(get("/api/v1/chat/conversations/" + conversationId + "/messages")
                        .header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andReturn()).path("data");
        assertThat(messages.size()).isEqualTo(2);
        assertThat(messages.get(0).path("role").asText()).isEqualTo("USER");
        assertThat(messages.get(1).path("role").asText()).isEqualTo("ASSISTANT");

        MvcResult stream = mockMvc.perform(post("/api/v1/chat/messages/stream")
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .accept(MediaType.TEXT_EVENT_STREAM)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "conversationId", conversationId,
                                "agentId", agentId,
                                "message", "stream this"))))
                .andExpect(status().isOk())
                .andReturn();
        String streamBody = mockMvc.perform(asyncDispatch(stream))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(streamBody).contains("event:delta");
        assertThat(streamBody).contains("event:done");
        assertThat(streamBody).contains("\"conversationId\":\"" + conversationId + "\"");
    }

    @Test
    void knowledgeDocumentsAndModelProvidersAreCompatible() throws Exception {
        String token = registerAndLogin("knowledge@example.com");

        JsonNode provider = createProvider(token);
        assertThat(provider.path("id").asText()).isNotBlank();
        assertThat(provider.path("apiKey").asText()).isEqualTo("configured");

        JsonNode models = json(mockMvc.perform(get("/api/v1/model-providers/" + provider.path("id").asText() + "/models")
                        .header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andReturn()).path("data");
        assertThat(models.get(0).path("modelId").asText()).isEqualTo("gpt-4o");

        JsonNode document = json(mockMvc.perform(post("/api/v1/knowledge/documents")
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "name", "architecture.md",
                                "contentType", "text/markdown",
                                "storageLocation", "storage://architecture.md"))))
                .andExpect(status().isOk())
                .andReturn()).path("data");
        String documentId = document.path("id").asText();
        assertThat(documentId).isNotBlank();

        mockMvc.perform(post("/api/v1/knowledge/documents/" + documentId + "/chunks")
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "sequence", 0,
                                "content", "Java owns state",
                                "embeddingReference", "embedding://0"))))
                .andExpect(status().isOk());

        JsonNode chunks = json(mockMvc.perform(get("/api/v1/knowledge/documents/" + documentId + "/chunks")
                        .header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andReturn()).path("data");
        assertThat(chunks.size()).isEqualTo(1);
        assertThat(chunks.get(0).path("content").asText()).isEqualTo("Java owns state");
    }

    private JsonNode createProvider(String token) throws Exception {
        Map<String, Object> provider = Map.of(
                "name", "OpenAI Compatible",
                "type", "openai",
                "baseUrl", "https://example.com/v1",
                "apiKey", "secret-key",
                "authType", "bearer",
                "models", List.of(Map.of(
                        "modelId", "gpt-4o",
                        "displayName", "GPT-4o",
                        "maxContextTokens", 32768,
                        "isDefault", true)));
        return json(mockMvc.perform(post("/api/v1/model-providers")
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(provider)))
                .andExpect(status().isCreated())
                .andReturn()).path("data");
    }

    private String registerAndLogin(String username) throws Exception {
        MvcResult register = mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "username", username,
                                "password", "password123",
                                "displayName", "Compat User"))))
                .andExpect(status().isOk())
                .andReturn();
        return json(register).path("data").path("token").asText();
    }

    private JsonNode json(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsByteArray());
    }

    private String bearer(String token) {
        return "Bearer " + token;
    }
}

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

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Public HTTP coverage for Knowledge lifecycle and retrieval. */
@SpringBootTest
@AutoConfigureMockMvc
class PlatformKnowledgeHttpTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void documentReferenceProcessingRetrievalAndDeleteArePlatformOwned() throws Exception {
        Identity owner = register("knowledge-core-owner@example.com");
        JsonNode document = data(mockMvc.perform(post("/api/v1/knowledge/documents/upload-reference")
                        .header("Authorization", bearer(owner.token()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "name", "runtime-architecture.md",
                                "contentType", "text/markdown",
                                "storageReference", "object://knowledge/runtime-architecture.md"))))
                .andExpect(status().isOk())
                .andReturn());
        String documentId = document.path("id").asText();
        assertThat(document.path("status").asText()).isEqualTo("UPLOADED");

        String content = "Runtime owns execution and checkpoints. ".repeat(40)
                + "Knowledge owns document chunks and embedding metadata.";
        JsonNode processing = data(mockMvc.perform(post(
                                "/api/v1/knowledge/documents/" + documentId + "/process")
                        .header("Authorization", bearer(owner.token()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("content", content))))
                .andExpect(status().isOk())
                .andReturn());
        assertThat(processing.path("document").path("status").asText()).isEqualTo("READY");
        assertThat(processing.path("chunks").size()).isGreaterThan(1);
        assertThat(processing.path("chunks").get(0).path("contentHash").asText()).hasSize(64);
        assertThat(processing.path("chunks").get(0).path("embeddingModel").asText())
                .isEqualTo("deterministic-sha256-v1");
        assertThat(processing.path("chunks").get(0).path("embeddingDimensions").asInt()).isEqualTo(16);

        JsonNode retrieval = data(mockMvc.perform(post("/api/v1/knowledge/retrieve")
                        .header("Authorization", bearer(owner.token()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "documentIds", List.of(documentId),
                                "query", "Who owns runtime checkpoints?",
                                "topK", 3))))
                .andExpect(status().isOk())
                .andReturn());
        assertThat(retrieval.path("matches").size()).isBetween(1, 3);
        assertThat(retrieval.path("matches").get(0).path("documentId").asText()).isEqualTo(documentId);

        mockMvc.perform(delete("/api/v1/knowledge/documents/" + documentId)
                        .header("Authorization", bearer(owner.token())))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/knowledge/documents/" + documentId)
                        .header("Authorization", bearer(owner.token())))
                .andExpect(status().isNotFound());
    }

    @Test
    void ownershipIsolationProtectsDocumentProcessingAndRetrieval() throws Exception {
        Identity alice = register("knowledge-core-alice@example.com");
        Identity bob = register("knowledge-core-bob@example.com");
        String documentId = data(mockMvc.perform(post("/api/v1/knowledge/documents")
                        .header("Authorization", bearer(alice.token()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "name", "alice.md",
                                "contentType", "text/markdown",
                                "storageLocation", "inline:Alice private architecture"))))
                .andExpect(status().isOk())
                .andReturn()).path("id").asText();

        mockMvc.perform(get("/api/v1/knowledge/documents/" + documentId)
                        .header("Authorization", bearer(bob.token())))
                .andExpect(status().isNotFound());
        mockMvc.perform(post("/api/v1/knowledge/documents/" + documentId + "/process")
                        .header("Authorization", bearer(bob.token()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isNotFound());
        mockMvc.perform(post("/api/v1/knowledge/retrieve")
                        .header("Authorization", bearer(bob.token()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "documentIds", List.of(documentId),
                                "query", "private",
                                "topK", 3))))
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
        return new Identity(auth.path("token").asText());
    }

    private JsonNode data(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsByteArray()).path("data");
    }

    private String bearer(String token) {
        return "Bearer " + token;
    }

    private record Identity(String token) {
    }
}

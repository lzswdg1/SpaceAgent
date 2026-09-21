package com.spaceagent.platform.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.spaceagent.platform.observability.domain.TraceDetail;
import com.spaceagent.platform.observability.domain.TraceSpan;
import com.spaceagent.platform.observability.domain.TraceSpanType;
import com.spaceagent.platform.observability.domain.TraceStatus;
import com.spaceagent.platform.observability.domain.TraceSummary;
import com.spaceagent.platform.observability.infrastructure.memory.InMemoryTracingQueryRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class PlatformTracingHttpTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private InMemoryTracingQueryRepository traces;

    @Test
    void ownTraceListDetailStatsAndForeignDeepLinkAreIsolated() throws Exception {
        traces.clear();
        Identity owner = register("trace-owner@example.com");
        String traceId = UUID.randomUUID().toString();
        String rootId = UUID.randomUUID().toString();
        Instant now = Instant.now();
        TraceSummary summary = new TraceSummary(
                traceId, owner.tenantId(), UUID.randomUUID().toString(), "Trace Session",
                UUID.randomUUID().toString(), "Trace Agent", owner.userId(), rootId,
                TraceStatus.SUCCESS, false, null, now.minusMillis(400), now, 400L,
                null, 300, 50, 50, 2, 1, 0,
                12, 8, 20, 0, 0, null, false,
                Map.of("source", "chat", "agentRunId", traceId),
                now.minusMillis(400), now);
        traces.put(new TraceDetail(summary, List.of(new TraceSpan(
                rootId, traceId, null, TraceSpanType.ROOT, "agent-run",
                TraceStatus.SUCCESS, false, null, summary.startTime(), summary.endTime(),
                summary.durationMs(), null, 12L, 8L, 20L, 0L, 0L,
                null, null, summary.metadata(), summary.createdAt(), summary.updatedAt()))));

        JsonNode list = data(mockMvc.perform(get("/api/v1/users/tracing/traces")
                        .queryParam("status", "success")
                        .queryParam("keyword", "Trace Agent")
                        .queryParam("limit", "50")
                        .header("Authorization", bearer(owner.token())))
                .andExpect(status().isOk()).andReturn());
        assertThat(list.path("total").asLong()).isEqualTo(1);
        assertThat(list.path("traces").get(0).path("id").asText()).isEqualTo(traceId);
        assertThat(list.path("traces").get(0).path("costUsd").isNull()).isTrue();
        assertThat(list.path("traces").get(0).path("firstTokenMs").isNull()).isTrue();

        JsonNode detail = data(mockMvc.perform(get("/api/v1/users/tracing/traces/" + traceId)
                        .header("Authorization", bearer(owner.token())))
                .andExpect(status().isOk()).andReturn());
        assertThat(detail.path("spans")).hasSize(1);
        assertThat(detail.path("spans").get(0).path("spanType").asText()).isEqualTo("root");

        JsonNode stats = data(mockMvc.perform(get("/api/v1/users/tracing/stats")
                        .queryParam("range", "7d")
                        .header("Authorization", bearer(owner.token())))
                .andExpect(status().isOk()).andReturn());
        assertThat(stats.path("totalTraces").asLong()).isEqualTo(1);
        assertThat(stats.path("totalCostUsd").isNull()).isTrue();

        Identity outsider = register("trace-outsider@example.com");
        assertThat(data(mockMvc.perform(get("/api/v1/users/tracing/traces")
                        .header("Authorization", bearer(outsider.token())))
                .andExpect(status().isOk()).andReturn()).path("traces")).isEmpty();
        mockMvc.perform(get("/api/v1/users/tracing/traces/" + traceId)
                        .header("Authorization", bearer(outsider.token())))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/v1/users/tracing/traces")
                        .queryParam("minDurationMs", "500")
                        .queryParam("maxDurationMs", "100")
                        .header("Authorization", bearer(owner.token())))
                .andExpect(status().isBadRequest());
    }

    private Identity register(String username) throws Exception {
        JsonNode auth = data(mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "username", username,
                                "password", "password123",
                                "displayName", username))))
                .andExpect(status().isOk()).andReturn());
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

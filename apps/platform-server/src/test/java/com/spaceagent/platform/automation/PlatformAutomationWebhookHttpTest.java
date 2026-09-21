package com.spaceagent.platform.automation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.spaceagent.platform.automation.application.AutomationWebhookApplicationService;
import com.spaceagent.platform.automation.domain.AutomationTrigger;
import com.spaceagent.platform.automation.domain.AutomationTriggerPersistence;
import com.spaceagent.platform.automation.domain.AutomationTriggerSource;
import com.spaceagent.platform.automation.domain.AutomationTriggerState;
import com.spaceagent.platform.automation.domain.AutomationTriggerType;
import com.spaceagent.platform.automation.infrastructure.memory.InMemoryAutomationTriggerRepository;
import com.spaceagent.platform.integration.infrastructure.http.PlatformAutomationWebhookHttpController;
import com.spaceagent.shared.exception.GlobalExceptionHandler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class PlatformAutomationWebhookHttpTest {
    private static final Instant NOW = Instant.parse("2026-09-08T00:00:00Z");
    private static final byte[] PRIMARY = "primary-webhook-signing-key-000001".getBytes(StandardCharsets.UTF_8);
    private static final byte[] ROTATING = "rotating-webhook-signing-key-0001".getBytes(StandardCharsets.UTF_8);

    private MockMvc mvc;
    private String triggerId;
    private String subscriptionId;

    @BeforeEach
    void setUp() {
        var repository = new InMemoryAutomationTriggerRepository();
        triggerId = UUID.randomUUID().toString();
        String lineageId = UUID.randomUUID().toString();
        subscriptionId = UUID.randomUUID().toString();
        var source = new AutomationTriggerSource.Webhook(
                List.of("webhook-key:primary", "webhook-key:rotating"),
                "HMAC_SHA256", 32, 300);
        String hash = AutomationTrigger.calculateConfigSha256(
                "tenant", "owner", "agent", "Webhook", "process", AutomationTriggerType.WEBHOOK, source);
        repository.insertTrigger(new AutomationTrigger(
                triggerId, lineageId, 1, null, "tenant", "owner", "agent", "Webhook", "process",
                AutomationTriggerType.WEBHOOK, source, hash, AutomationTriggerState.ACTIVE,
                1, NOW.minusSeconds(60), NOW.minusSeconds(30), null));
        repository.insertSubscription(new AutomationTriggerPersistence.Subscription(
                subscriptionId, triggerId, lineageId, "tenant", "owner", AutomationTriggerType.WEBHOOK,
                "sha256:" + "a".repeat(64), AutomationTriggerPersistence.SubscriptionState.ACTIVE,
                1, NOW.minusSeconds(30), NOW.minusSeconds(30), null));
        AtomicInteger sequence = new AtomicInteger();
        var service = new AutomationWebhookApplicationService(repository,
                () -> new UUID(0, sequence.incrementAndGet()).toString(), () -> NOW);
        var controller = new PlatformAutomationWebhookHttpController(service, reference -> switch (reference) {
            case "webhook-key:primary" -> Optional.of(PRIMARY.clone());
            case "webhook-key:rotating" -> Optional.of(ROTATING.clone());
            default -> Optional.empty();
        }, () -> NOW);
        mvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler()).build();
    }

    @Test
    void acceptsEitherRotationKeyAndReplaysTheSameOccurrence() throws Exception {
        byte[] body = "{\"event\":\"push\"}".getBytes(StandardCharsets.UTF_8);
        String timestamp = Long.toString(NOW.getEpochSecond());
        String eventId = "delivery-1";
        String primarySignature = signature(PRIMARY, timestamp, eventId, body);
        MvcResult first = request(timestamp, eventId, primarySignature, body)
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.data.status").value("ACCEPTED"))
                .andReturn();
        String occurrenceId = json(first).path("data").path("occurrenceId").asText();

        request(timestamp, eventId, signature(ROTATING, timestamp, eventId, body), body)
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.data.occurrenceId").value(occurrenceId));
        request(timestamp, "delivery-2", signature(ROTATING, timestamp, "delivery-2", body), body)
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.data.occurrenceId").value(org.hamcrest.Matchers.not(occurrenceId)));
    }

    @Test
    void rejectsInvalidSignatureStaleTimestampAndOversizeWithoutEchoingBody() throws Exception {
        byte[] body = "sensitive-payload".getBytes(StandardCharsets.UTF_8);
        String timestamp = Long.toString(NOW.getEpochSecond());
        request(timestamp, "bad-signature", "sha256=" + "0".repeat(64), body)
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTOMATION_WEBHOOK_SIGNATURE_INVALID"));

        String stale = Long.toString(NOW.minusSeconds(301).getEpochSecond());
        request(stale, "stale", signature(PRIMARY, stale, "stale", body), body)
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTOMATION_WEBHOOK_TIMESTAMP_INVALID"));

        MvcResult oversized = request(timestamp, "oversize",
                signature(PRIMARY, timestamp, "oversize", "x".repeat(33).getBytes(StandardCharsets.UTF_8)),
                "x".repeat(33).getBytes(StandardCharsets.UTF_8))
                .andExpect(status().isPayloadTooLarge())
                .andExpect(jsonPath("$.code").value("AUTOMATION_WEBHOOK_PAYLOAD_TOO_LARGE"))
                .andReturn();
        assertThat(oversized.getResponse().getContentAsString()).doesNotContain("sensitive-payload");
    }

    @Test
    void unknownEndpointAndMalformedHeadersFailWithStableSafeCodes() throws Exception {
        byte[] body = "{}".getBytes(StandardCharsets.UTF_8);
        requestTo(UUID.randomUUID().toString(), subscriptionId, "bad", "event", "nope", body)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("AUTOMATION_WEBHOOK_UNAVAILABLE"));
        request("bad", "event", "nope", body)
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTOMATION_WEBHOOK_SIGNATURE_INVALID"));
    }

    private org.springframework.test.web.servlet.ResultActions request(
            String timestamp, String eventId, String signature, byte[] body) throws Exception {
        return requestTo(triggerId, subscriptionId, timestamp, eventId, signature, body);
    }

    private org.springframework.test.web.servlet.ResultActions requestTo(
            String trigger, String subscription, String timestamp, String eventId,
            String signature, byte[] body) throws Exception {
        return mvc.perform(post("/api/v1/public/automation/webhooks/{trigger}/{subscription}",
                        trigger, subscription)
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .header("X-SpaceAgent-Timestamp", timestamp)
                .header("X-SpaceAgent-Event-Id", eventId)
                .header("X-SpaceAgent-Signature", signature)
                .content(body));
    }

    private static String signature(byte[] key, String timestamp, String eventId, byte[] body) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(key, "HmacSHA256"));
        mac.update((timestamp + "." + eventId + ".").getBytes(StandardCharsets.UTF_8));
        return "sha256=" + HexFormat.of().formatHex(mac.doFinal(body));
    }

    private static JsonNode json(MvcResult result) throws Exception {
        return new ObjectMapper().readTree(result.getResponse().getContentAsByteArray());
    }
}

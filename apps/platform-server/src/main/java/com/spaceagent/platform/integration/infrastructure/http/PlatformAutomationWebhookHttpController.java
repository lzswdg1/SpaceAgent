package com.spaceagent.platform.integration.infrastructure.http;

import com.spaceagent.platform.automation.api.AutomationWebhookApplicationApi;
import com.spaceagent.shared.api.ApiResponse;
import com.spaceagent.shared.exception.BusinessException;
import com.spaceagent.shared.time.TimeProvider;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Optional;

@RestController
@RequestMapping("/api/v1/public/automation/webhooks")
public class PlatformAutomationWebhookHttpController {
    private final AutomationWebhookApplicationApi automation;
    private final SigningKeyResolver keys;
    private final TimeProvider time;

    public PlatformAutomationWebhookHttpController(
            AutomationWebhookApplicationApi automation, SigningKeyResolver keys, TimeProvider time) {
        this.automation = automation;
        this.keys = keys;
        this.time = time;
    }

    @PostMapping("/{triggerVersionId}/{subscriptionId}")
    public ResponseEntity<ApiResponse<WebhookResponse>> receive(
            @PathVariable String triggerVersionId,
            @PathVariable String subscriptionId,
            @RequestHeader("X-SpaceAgent-Timestamp") String timestampHeader,
            @RequestHeader("X-SpaceAgent-Event-Id") String eventId,
            @RequestHeader("X-SpaceAgent-Signature") String signature,
            HttpServletRequest request) {
        var policy = automation.prepare(triggerVersionId, subscriptionId);
        validateHeaders(timestampHeader, eventId, signature);
        long contentLength = request.getContentLengthLong();
        if (contentLength > policy.maxBodyBytes()) throw tooLarge();
        byte[] body = readBounded(request, policy.maxBodyBytes());
        Instant occurredAt = validateTimestamp(timestampHeader, policy.maxAgeSeconds());
        byte[] signed = signedBytes(timestampHeader, eventId, body);
        byte[] supplied = HexFormat.of().parseHex(signature.substring("sha256=".length()));
        boolean valid = policy.signingKeyRefs().stream()
                .map(keys::resolve)
                .flatMap(Optional::stream)
                .anyMatch(key -> MessageDigest.isEqual(hmac(key, signed), supplied));
        if (!valid) throw invalidSignature();

        String eventHash = sha256(eventId.getBytes(StandardCharsets.UTF_8));
        String payloadHash = sha256(body);
        var admitted = automation.admit(new AutomationWebhookApplicationApi.VerifiedWebhookEvent(
                policy, eventHash, payloadHash, occurredAt));
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .body(ApiResponse.ok(new WebhookResponse("ACCEPTED", admitted.occurrenceId())));
    }

    private Instant validateTimestamp(String value, int maxAgeSeconds) {
        try {
            Instant timestamp = Instant.ofEpochSecond(Long.parseLong(value));
            long delta = time.now().getEpochSecond() - timestamp.getEpochSecond();
            if (delta < -30 || delta > maxAgeSeconds) throw stale();
            return timestamp;
        } catch (NumberFormatException error) {
            throw stale();
        }
    }

    private static void validateHeaders(String timestamp, String eventId, String signature) {
        if (timestamp == null || !timestamp.matches("[0-9]{1,12}")
                || eventId == null || !eventId.matches("[A-Za-z0-9_.:-]{1,160}")
                || signature == null || !signature.matches("sha256=[0-9a-f]{64}")) {
            throw invalidSignature();
        }
    }

    private static byte[] readBounded(HttpServletRequest request, int maxBytes) {
        try {
            byte[] body = request.getInputStream().readNBytes(maxBytes + 1);
            if (body.length > maxBytes) throw tooLarge();
            return body;
        } catch (IOException error) {
            throw new BusinessException("Webhook body could not be read", HttpStatus.BAD_REQUEST,
                    "AUTOMATION_WEBHOOK_BODY_INVALID");
        }
    }

    private static byte[] signedBytes(String timestamp, String eventId, byte[] body) {
        byte[] prefix = (timestamp + "." + eventId + ".").getBytes(StandardCharsets.UTF_8);
        byte[] signed = new byte[prefix.length + body.length];
        System.arraycopy(prefix, 0, signed, 0, prefix.length);
        System.arraycopy(body, 0, signed, prefix.length, body.length);
        return signed;
    }

    private static byte[] hmac(byte[] key, byte[] value) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return mac.doFinal(value);
        } catch (Exception error) {
            throw new IllegalStateException("HMAC-SHA256 unavailable", error);
        }
    }

    private static String sha256(byte[] value) {
        try {
            return "sha256:" + HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(value));
        } catch (Exception error) {
            throw new IllegalStateException("SHA-256 unavailable", error);
        }
    }

    private static BusinessException invalidSignature() {
        return new BusinessException("Webhook authentication failed", HttpStatus.UNAUTHORIZED,
                "AUTOMATION_WEBHOOK_SIGNATURE_INVALID");
    }

    private static BusinessException stale() {
        return new BusinessException("Webhook timestamp is outside the accepted window",
                HttpStatus.UNAUTHORIZED, "AUTOMATION_WEBHOOK_TIMESTAMP_INVALID");
    }

    private static BusinessException tooLarge() {
        return new BusinessException("Webhook payload exceeds the accepted size",
                HttpStatus.PAYLOAD_TOO_LARGE, "AUTOMATION_WEBHOOK_PAYLOAD_TOO_LARGE");
    }

    public interface SigningKeyResolver {
        Optional<byte[]> resolve(String keyReference);
    }

    public record WebhookResponse(String status, String occurrenceId) {
    }
}

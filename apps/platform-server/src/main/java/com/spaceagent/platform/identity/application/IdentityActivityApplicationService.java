package com.spaceagent.platform.identity.application;

import com.spaceagent.platform.identity.api.IdentityActivityApplicationApi;
import com.spaceagent.platform.identity.domain.IdentityActivityRepository;
import com.spaceagent.shared.time.TimeProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import java.util.Locale;
import java.util.UUID;

@Service
public class IdentityActivityApplicationService implements IdentityActivityApplicationApi {
    private final IdentityActivityRepository repository;
    private final TimeProvider timeProvider;
    private final byte[] hashKey;

    public IdentityActivityApplicationService(
            IdentityActivityRepository repository,
            TimeProvider timeProvider,
            @Value("${platform.identity.activity-hash-key:local-dev-identity-activity-hash-key-change-me}")
            String hashKey) {
        if (hashKey == null || hashKey.length() < 32) {
            throw new IllegalStateException("platform.identity.activity-hash-key must contain at least 32 characters");
        }
        this.repository = repository;
        this.timeProvider = timeProvider;
        this.hashKey = hashKey.getBytes(StandardCharsets.UTF_8);
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordLoginSucceeded(String userId, String subject, String clientType) {
        Instant now = timeProvider.now();
        String normalizedClient = clientType(clientType);
        repository.recordLoginEvent(UUID.randomUUID(), userId, hmac(subject), true,
                normalizedClient, null, null, null, now);
        repository.recordSuccessfulLogin(userId, normalizedClient, null, null, now);
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordLoginFailed(
            String userId,
            String subject,
            String safeErrorCode,
            String clientType) {
        repository.recordLoginEvent(UUID.randomUUID(), userId, hmac(subject), false,
                clientType(clientType), null, null, safeErrorCode, timeProvider.now());
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markSeen(
            String userId,
            String remoteAddress,
            String userAgent,
            String clientType) {
        Instant now = timeProvider.now();
        repository.markSeenIfDue(userId, clientType(clientType), optionalHmac(remoteAddress),
                optionalHmac(userAgent), now, now.minus(5, ChronoUnit.MINUTES));
    }

    @Override
    @Transactional(readOnly = true)
    public boolean isUserActive(String userId) {
        return repository.isUserActive(userId);
    }

    private String optionalHmac(String value) {
        return value == null || value.isBlank() ? null : hmac(value.trim());
    }

    private String hmac(String value) {
        String normalized = value == null ? "" : value.trim();
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(hashKey, "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(normalized.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception error) {
            throw new IllegalStateException("Unable to hash identity activity evidence", error);
        }
    }

    private static String clientType(String value) {
        if (value == null || value.isBlank()) return "UNKNOWN";
        String normalized = value.trim().toUpperCase(Locale.ROOT);
        return switch (normalized) {
            case "WEB", "CLI", "API" -> normalized;
            default -> "UNKNOWN";
        };
    }
}

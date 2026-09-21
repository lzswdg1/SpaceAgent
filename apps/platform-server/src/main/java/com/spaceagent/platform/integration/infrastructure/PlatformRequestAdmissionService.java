package com.spaceagent.platform.integration.infrastructure;

import com.spaceagent.shared.exception.BusinessException;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;

/** Bounded process-local admission control; PostgreSQL business state remains authoritative. */
@Component
public class PlatformRequestAdmissionService {
    private static final long WINDOW_MILLIS = Duration.ofMinutes(1).toMillis();
    private final int maxRateKeys;
    private final int maxSsePerUser;

    private final ConcurrentHashMap<String, Window> rates = new ConcurrentHashMap<>();
    private final Semaphore globalSse;
    private final ConcurrentHashMap<String, Integer> userSse = new ConcurrentHashMap<>();

    public PlatformRequestAdmissionService() { this(new PlatformAdmissionProperties()); }

    @org.springframework.beans.factory.annotation.Autowired
    public PlatformRequestAdmissionService(PlatformAdmissionProperties properties) {
        properties.validate();
        globalSse = new Semaphore(properties.getMaxSseConnections());
        maxSsePerUser = properties.getMaxSseConnectionsPerUser();
        maxRateKeys = properties.getMaxRateKeys();
    }

    public void requireAuthenticationAttempt(
            String operation,
            String subject,
            HttpServletRequest request,
            int limitPerMinute) {
        String remote = request == null || request.getRemoteAddr() == null
                ? "unknown" : request.getRemoteAddr();
        requireRate(operation + ":ip:" + digest(remote), Math.max(60, limitPerMinute * 10));
        String normalizedSubject = subject == null ? ""
                : subject.trim().toLowerCase(java.util.Locale.ROOT);
        requireRate(operation + ":ip-subject:" + digest(remote + "\n" + normalizedSubject),
                limitPerMinute);
    }

    public SseLease acquireSse(String userId) {
        if (!globalSse.tryAcquire()) throw capacity();
        try {
            userSse.compute(userId, (key, current) -> {
                int count = current == null ? 0 : current;
                if (count >= maxSsePerUser) throw capacity();
                return count + 1;
            });
        } catch (RuntimeException error) {
            globalSse.release();
            throw error;
        }
        return new SseLease(this, userId);
    }

    private void requireRate(String key, int limit) {
        long now = System.currentTimeMillis();
        if (rates.size() >= maxRateKeys) {
            rates.entrySet().removeIf(entry -> now - entry.getValue().windowStartedAt >= WINDOW_MILLIS);
            if (rates.size() >= maxRateKeys && !rates.containsKey(key)) {
                throw rateLimited();
            }
        }
        Window window = rates.computeIfAbsent(key, ignored -> new Window(now));
        synchronized (window) {
            if (now - window.windowStartedAt >= WINDOW_MILLIS) {
                window.windowStartedAt = now;
                window.count = 0;
            }
            if (++window.count > limit) throw rateLimited();
        }
    }

    private void release(String userId) {
        // Increment/decrement/removal share one map lock; a last release cannot remove a new lease.
        userSse.computeIfPresent(userId, (key, count) -> count == 1 ? null : count - 1);
    }

    private static String digest(String value) {
        try {
            byte[] bytes = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(bytes, 0, 12);
        } catch (Exception error) {
            throw new IllegalStateException("Unable to hash admission key", error);
        }
    }

    private static BusinessException rateLimited() {
        return new BusinessException(
                "Too many authentication attempts", HttpStatus.TOO_MANY_REQUESTS,
                "AUTH_RATE_LIMITED");
    }

    private static BusinessException capacity() {
        return new BusinessException(
                "Too many active event streams", HttpStatus.TOO_MANY_REQUESTS,
                "SSE_CAPACITY_EXCEEDED");
    }

    private static final class Window {
        private long windowStartedAt;
        private int count;

        private Window(long windowStartedAt) {
            this.windowStartedAt = windowStartedAt;
        }
    }

    public static final class SseLease implements AutoCloseable {
        private final PlatformRequestAdmissionService owner;
        private final String userId;
        private final AtomicBoolean closed = new AtomicBoolean();

        private SseLease(
                PlatformRequestAdmissionService owner,
                String userId) {
            this.owner = owner;
            this.userId = userId;
        }

        @Override
        public void close() {
            if (!closed.compareAndSet(false, true)) return;
            owner.release(userId);
            owner.globalSse.release();
        }
    }
}

package com.spaceagent.admin.identity.application;

import com.spaceagent.admin.security.AdminTokenMaterial;
import com.spaceagent.admin.shared.AdminApiException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

/** Process-local abuse control keyed by network source and login pair, never by account alone. */
@Component
public class AdminLoginAdmissionService {
    private static final long WINDOW_MILLIS = Duration.ofMinutes(15).toMillis();
    private static final int MAX_KEYS = 10_000;

    private final ConcurrentHashMap<String, Window> attempts = new ConcurrentHashMap<>();
    private final LongSupplier clock;

    public AdminLoginAdmissionService() {
        this(System::currentTimeMillis);
    }

    AdminLoginAdmissionService(LongSupplier clock) {
        this.clock = clock;
    }

    public void requireAttempt(String remoteAddress, String loginName, int pairLimit) {
        String remote = remoteAddress == null || remoteAddress.isBlank()
                ? "unknown" : remoteAddress.trim();
        String login = loginName == null ? ""
                : loginName.trim().toLowerCase(Locale.ROOT);
        require("ip:" + hash(remote), Math.max(50, pairLimit * 10));
        require("pair:" + hash(remote + "\n" + login), Math.max(1, pairLimit));
    }

    private void require(String key, int limit) {
        long now = clock.getAsLong();
        if (attempts.size() >= MAX_KEYS) {
            attempts.entrySet().removeIf(entry -> now - entry.getValue().startedAt >= WINDOW_MILLIS);
            if (attempts.size() >= MAX_KEYS && !attempts.containsKey(key)) throw rateLimited();
        }
        Window window = attempts.computeIfAbsent(key, ignored -> new Window(now));
        synchronized (window) {
            if (now - window.startedAt >= WINDOW_MILLIS) {
                window.startedAt = now;
                window.count = 0;
            }
            if (++window.count > limit) throw rateLimited();
        }
    }

    private static String hash(String value) {
        return AdminTokenMaterial.sha256(value).substring(0, 24);
    }

    private static AdminApiException rateLimited() {
        return new AdminApiException(
                HttpStatus.TOO_MANY_REQUESTS,
                "ADMIN_LOGIN_RATE_LIMITED",
                "Administrator login is temporarily rate limited for this network source");
    }

    private static final class Window {
        private long startedAt;
        private int count;

        private Window(long startedAt) {
            this.startedAt = startedAt;
        }
    }
}

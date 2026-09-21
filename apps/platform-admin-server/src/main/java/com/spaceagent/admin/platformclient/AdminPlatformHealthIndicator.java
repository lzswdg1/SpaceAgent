package com.spaceagent.admin.platformclient;

import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

@Component("platformHandshake")
public class AdminPlatformHealthIndicator implements HealthIndicator {
    private final AdminPlatformClient client;

    public AdminPlatformHealthIndicator(AdminPlatformClient client) { this.client = client; }

    @Override
    public Health health() {
        try {
            PlatformAdminWire.Health health = client.health();
            if (!"UP".equals(health.status())) {
                return Health.down().withDetail("status", "PLATFORM_NOT_READY").build();
            }
            return Health.up().withDetail("status", "READY")
                    .withDetail("releaseVersion", health.releaseVersion())
                    .withDetail("schemaVersion", health.schemaVersion()).build();
        } catch (RuntimeException error) {
            return Health.down().withDetail("status", "PLATFORM_UNAVAILABLE").build();
        }
    }
}

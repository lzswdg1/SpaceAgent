package com.spaceagent.platform.integration.infrastructure;

import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;

/** Secret-free release readiness evidence; liveness remains independent of this indicator. */
@Component("releaseReadiness")
public class PlatformReleaseReadinessHealthIndicator implements HealthIndicator {
    private final PlatformReleaseProperties release;
    private final Environment environment;
    private final JdbcTemplate jdbc;

    public PlatformReleaseReadinessHealthIndicator(
            PlatformReleaseProperties release,
            Environment environment,
            JdbcTemplate jdbc) {
        this.release = release;
        this.environment = environment;
        this.jdbc = jdbc;
    }

    @Override
    public Health health() {
        if (release.getMode() == PlatformReleaseProperties.Mode.DEVELOPMENT) {
            return Health.up()
                    .withDetail("releaseMode", release.getMode().name())
                    .withDetail("releaseVersion", release.getVersion())
                    .build();
        }
        try {
            Integer version = jdbc.queryForObject("""
                    SELECT version::integer FROM flyway_schema_history
                    WHERE success ORDER BY installed_rank DESC LIMIT 1
                    """, Integer.class);
            Long failedMigrations = jdbc.queryForObject(
                    "SELECT count(*) FROM flyway_schema_history WHERE NOT success", Long.class);
            if (version == null || version != release.getExpectedSchemaVersion()
                    || failedMigrations == null || failedMigrations != 0) {
                return down("RELEASE_SCHEMA_NOT_READY")
                        .withDetail("schemaVersion", version == null ? "missing" : version)
                        .withDetail("expectedSchemaVersion", release.getExpectedSchemaVersion())
                        .build();
            }
            Path workspace = Path.of(environment.getRequiredProperty(
                    "platform.workspace.managed-root")).toAbsolutePath().normalize();
            Files.createDirectories(workspace);
            if (!Files.isDirectory(workspace) || !Files.isWritable(workspace)) {
                return down("RELEASE_WORKSPACE_NOT_WRITABLE").build();
            }
            if (!release.isTrustedCodeOnly() || release.isPublicUntrustedCodeEnabled()) {
                return down("RELEASE_AUDIENCE_UNSAFE").build();
            }
            return Health.up()
                    .withDetail("releaseMode", release.getMode().name())
                    .withDetail("releaseVersion", release.getVersion())
                    .withDetail("schemaVersion", version)
                    .withDetail("trustedCodeOnly", true)
                    .withDetail("publicUntrustedCodeEnabled", false)
                    .withDetail("workspace", "WRITABLE")
                    .build();
        } catch (Exception error) {
            return down("RELEASE_READINESS_CHECK_FAILED").build();
        }
    }

    private Health.Builder down(String reasonCode) {
        return Health.down()
                .withDetail("releaseMode", release.getMode().name())
                .withDetail("releaseVersion", release.getVersion())
                .withDetail("reasonCode", reasonCode);
    }
}

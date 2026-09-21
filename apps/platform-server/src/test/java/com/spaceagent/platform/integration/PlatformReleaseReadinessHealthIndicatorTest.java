package com.spaceagent.platform.integration;

import com.spaceagent.platform.integration.infrastructure.PlatformReleaseProperties;
import com.spaceagent.platform.integration.infrastructure.PlatformReleaseReadinessHealthIndicator;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.mock.env.MockEnvironment;

import java.nio.file.Files;

import static org.assertj.core.api.Assertions.assertThat;

class PlatformReleaseReadinessHealthIndicatorTest {

    @Test
    void trustedBetaReadinessRequiresExactSchemaAndWritableWorkspaceWithoutSecrets() throws Exception {
        var dataSource = new DriverManagerDataSource(
                "jdbc:h2:mem:release_readiness;MODE=PostgreSQL;DB_CLOSE_DELAY=-1", "sa", "");
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("""
                CREATE TABLE flyway_schema_history(
                    installed_rank INTEGER PRIMARY KEY, version VARCHAR(50), success BOOLEAN)
                """);
        jdbc.update("INSERT INTO flyway_schema_history VALUES(1,'1098',TRUE)");
        var workspace = Files.createTempDirectory("spaceagent-release-health-");
        var environment = new MockEnvironment()
                .withProperty("platform.workspace.managed-root", workspace.toString());
        PlatformReleaseProperties release = new PlatformReleaseProperties();
        release.setMode(PlatformReleaseProperties.Mode.TRUSTED_BETA);
        release.setVersion("1.0.0-rc1");
        var indicator = new PlatformReleaseReadinessHealthIndicator(
                release, environment, jdbc);

        var ready = indicator.health();
        assertThat(ready.getStatus().getCode()).isEqualTo("UP");
        assertThat(ready.getDetails())
                .containsEntry("schemaVersion", 1098)
                .containsEntry("workspace", "WRITABLE")
                .doesNotContainKeys("workspacePath", "databaseUrl", "secret");

        jdbc.update("UPDATE flyway_schema_history SET version='1033'");
        var stale = indicator.health();
        assertThat(stale.getStatus().getCode()).isEqualTo("DOWN");
        assertThat(stale.getDetails()).containsEntry("reasonCode", "RELEASE_SCHEMA_NOT_READY");
    }

    @Test
    void developmentReadinessDoesNotRequireReleaseDatabaseEvidence() {
        var dataSource = new DriverManagerDataSource(
                "jdbc:h2:mem:release_development;MODE=PostgreSQL", "sa", "");
        var indicator = new PlatformReleaseReadinessHealthIndicator(
                new PlatformReleaseProperties(), new MockEnvironment(),
                new JdbcTemplate(dataSource));
        assertThat(indicator.health().getStatus().getCode()).isEqualTo("UP");
    }
}

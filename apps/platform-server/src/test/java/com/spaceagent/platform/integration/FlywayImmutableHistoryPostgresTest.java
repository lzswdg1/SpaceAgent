package com.spaceagent.platform.integration;

import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers(disabledWithoutDocker = true)
class FlywayImmutableHistoryPostgresTest {

    private static final String V1072 =
            "db/platform-server/V1072__supervisor_run_policy.sql";
    private static final String V1073 =
            "db/platform-server/V1073__automation_occurrence_dispatch_claim.sql";
    private static final Map<String, String> ORIGINAL_SHA256 = Map.of(
            V1072, "6b5211b96e4407008ea32f15019a5fb94d149876d96ec7be6f5d251ac85f4588",
            V1073, "27d394e3667f130f2de9cb5247c48c3cf34cd58a782cd0e3fc8b2341c9a72d1b");

    @Container
    private static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("pgvector/pgvector:pg17")
                    .withDatabaseName("immutable_flyway_history")
                    .withUsername("spaceagent")
                    .withPassword("spaceagent");

    @Test
    void originalAppliedChecksumsValidateOnProcessRestart() throws Exception {
        ORIGINAL_SHA256.forEach((resource, expected) ->
                assertThat(sha256(resource)).isEqualTo(expected));

        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        Flyway firstProcess = flyway(dataSource);
        firstProcess.migrate();
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        Map<String, Integer> appliedChecksums = jdbc.query(
                        "SELECT version, checksum FROM flyway_schema_history "
                                + "WHERE version IN ('1072','1073') ORDER BY version",
                        (resultSet, row) -> Map.entry(
                                resultSet.getString("version"),
                                resultSet.getInt("checksum")))
                .stream()
                .collect(java.util.stream.Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
        assertThat(appliedChecksums).containsOnlyKeys("1072", "1073");

        Flyway restartedProcess = flyway(dataSource);
        assertThat(restartedProcess.validateWithResult().validationSuccessful).isTrue();
        assertThat(restartedProcess.migrate().migrationsExecuted).isZero();
        assertThat(jdbc.query(
                        "SELECT version, checksum FROM flyway_schema_history "
                                + "WHERE version IN ('1072','1073') ORDER BY version",
                        (resultSet, row) -> Map.entry(
                                resultSet.getString("version"),
                                resultSet.getInt("checksum")))
                .stream()
                .collect(java.util.stream.Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue)))
                .isEqualTo(appliedChecksums);
    }

    private static Flyway flyway(DriverManagerDataSource dataSource) {
        return Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/platform-server", "classpath:db/platform-runtime")
                .load();
    }

    private static String sha256(String resource) {
        try (var input = FlywayImmutableHistoryPostgresTest.class
                .getClassLoader().getResourceAsStream(resource)) {
            if (input == null) {
                throw new IllegalStateException("Missing migration resource: " + resource);
            }
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(input.readAllBytes()));
        } catch (IOException | NoSuchAlgorithmException exception) {
            throw new IllegalStateException("Cannot hash migration resource: " + resource, exception);
        }
    }
}

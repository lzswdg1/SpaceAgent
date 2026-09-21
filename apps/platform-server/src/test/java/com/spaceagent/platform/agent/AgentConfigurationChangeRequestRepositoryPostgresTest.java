package com.spaceagent.platform.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.spaceagent.platform.agent.domain.AgentConfigurationChangeRequest;
import com.spaceagent.platform.agent.domain.AgentConfigurationChangeState;
import com.spaceagent.platform.agent.domain.AgentConfigurationProposal;
import com.spaceagent.platform.agent.infrastructure.persistence.PostgresAgentConfigurationChangeRequestRepository;
import com.spaceagent.platform.support.PostgresSchemaDataSource;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers(disabledWithoutDocker = true)
class AgentConfigurationChangeRequestRepositoryPostgresTest {

    private static final Instant NOW = Instant.parse("2026-09-11T12:00:00Z");
    private static final String TENANT = "tenant-change";
    private static final String OWNER = "owner-change";
    private static final String MEMBER = "member-change";
    private static final String AGENT = "agent-change";
    private static final String REQUEST = "00000000-0000-4000-8000-000000000101";
    private static final String APPROVAL = "00000000-0000-4000-8000-000000000201";

    @Container
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("pgvector/pgvector:pg17")
                    .withDatabaseName("agent_configuration_change")
                    .withUsername("spaceagent")
                    .withPassword("spaceagent");

    private static DriverManagerDataSource dataSource;

    @BeforeAll
    static void migrateAndSeed() {
        dataSource = new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        Flyway.configure().dataSource(dataSource)
                .locations("classpath:db/platform-server", "classpath:db/platform-runtime")
                .load().migrate();
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        Timestamp now = Timestamp.from(NOW);
        jdbc.update("INSERT INTO platform_tenants(id,name,slug,status,created_at,updated_at) "
                + "VALUES(?,?,'agent-change','ACTIVE',?,?)", TENANT, "Agent Change", now, now);
        jdbc.update("INSERT INTO platform_users(id,tenant_id,external_id,display_name,created_at,updated_at) "
                + "VALUES(?,?,?,'Owner',?,?),(?,?,?,'Member',?,?)",
                OWNER, TENANT, "owner-change@example.com", now, now,
                MEMBER, TENANT, "member-change@example.com", now, now);
        jdbc.update("INSERT INTO platform_tenant_memberships(tenant_id,user_id,tenant_role,status,joined_at,updated_at) "
                + "VALUES(?,?,'OWNER','ACTIVE',?,?),(?,?,'MEMBER','ACTIVE',?,?)",
                TENANT, OWNER, now, now, TENANT, MEMBER, now, now);
        jdbc.update("INSERT INTO platform_agent_definitions(id,owner_id,tenant_id,name,status,revision,created_at,updated_at) "
                + "VALUES(?,?,?,'Shared Agent','ACTIVE',3,?,?)", AGENT, OWNER, TENANT, now, now);
        insertApproval(jdbc, APPROVAL, "sha256:" + "b".repeat(64));
    }

    @Test
    void persistsReplacesAndErasesTerminalProposalAcrossRestart() {
        var repository = repository();
        AgentConfigurationChangeRequest request = pending(APPROVAL, "b", "a");
        repository.insert(request);
        assertThat(repository.findPending(TENANT, AGENT, MEMBER)).contains(request);

        String replacementApproval = "00000000-0000-4000-8000-000000000202";
        insertApproval(new JdbcTemplate(dataSource), replacementApproval, "sha256:" + "d".repeat(64));
        AgentConfigurationChangeRequest replacement = request.replacePending(
                replacementApproval, 3, "a".repeat(64), "sha256:" + "d".repeat(64),
                proposal("replacement", "c".repeat(64)), NOW.plusSeconds(1));
        assertThat(repository.update(replacement, 1, AgentConfigurationChangeState.PENDING))
                .contains(replacement);
        assertThat(repository.update(replacement, 1, AgentConfigurationChangeState.PENDING))
                .isEmpty();

        AgentConfigurationChangeRequest applied = replacement.close(
                AgentConfigurationChangeState.APPLIED, OWNER, "approved", 4L, NOW.plusSeconds(2));
        assertThat(repository.update(applied, 2, AgentConfigurationChangeState.PENDING))
                .contains(applied);

        var restarted = repository();
        assertThat(restarted.findById(TENANT, REQUEST)).get().satisfies(value -> {
            assertThat(value.state()).isEqualTo(AgentConfigurationChangeState.APPLIED);
            assertThat(value.proposal()).isNull();
            assertThat(value.appliedAgentRevision()).isEqualTo(4L);
        });
        assertThat(new JdbcTemplate(dataSource).queryForObject(
                "SELECT proposal_json IS NULL FROM platform_agent_configuration_change_requests "
                        + "WHERE id=CAST(? AS UUID)", Boolean.class, REQUEST)).isTrue();
    }

    @Test
    void v1082UpgradesV1081WithoutInventingRequests() {
        String database = "agent_change_upgrade";
        DriverManagerDataSource upgrade = PostgresSchemaDataSource.forSchema(POSTGRES, database);
        Flyway.configure().dataSource(upgrade)
                .locations("classpath:db/platform-server", "classpath:db/platform-runtime")
                .target(MigrationVersion.fromVersion("1081")).load().migrate();
        JdbcTemplate jdbc = new JdbcTemplate(upgrade);
        assertThat(jdbc.queryForObject(
                "SELECT to_regclass('platform_agent_configuration_change_requests') IS NULL",
                Boolean.class)).isTrue();

        Flyway.configure().dataSource(upgrade)
                .locations("classpath:db/platform-server", "classpath:db/platform-runtime")
                .load().migrate();

        assertThat(jdbc.queryForObject(
                "SELECT to_regclass('platform_agent_configuration_change_requests') IS NOT NULL",
                Boolean.class)).isTrue();
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM platform_agent_configuration_change_requests", Long.class)).isZero();
        assertThat(jdbc.queryForObject(
                "SELECT version FROM flyway_schema_history WHERE success "
                        + "ORDER BY installed_rank DESC LIMIT 1", String.class)).isEqualTo("1098");
    }

    @Test
    void governanceOrMembershipCleanupCannotLeaveProposalPayloadBehind() {
        String approval = "00000000-0000-4000-8000-000000000203";
        String requestId = "00000000-0000-4000-8000-000000000103";
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        insertApproval(jdbc, approval, "sha256:" + "e".repeat(64));
        repository().insert(AgentConfigurationChangeRequest.pending(
                requestId, approval, TENANT, AGENT, OWNER, MEMBER, 3,
                "a".repeat(64), "sha256:" + "e".repeat(64),
                proposal("temporary", "e".repeat(64)), NOW));

        jdbc.update("DELETE FROM platform_approval_requests WHERE id=CAST(? AS UUID)", approval);

        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM platform_agent_configuration_change_requests "
                        + "WHERE id=CAST(? AS UUID)", Long.class, requestId)).isZero();
    }

    private static PostgresAgentConfigurationChangeRequestRepository repository() {
        return new PostgresAgentConfigurationChangeRequestRepository(
                new JdbcTemplate(dataSource), new ObjectMapper().findAndRegisterModules());
    }

    private static AgentConfigurationChangeRequest pending(
            String approvalId, String proposalHashCharacter, String configHashCharacter) {
        return AgentConfigurationChangeRequest.pending(
                REQUEST, approvalId, TENANT, AGENT, OWNER, MEMBER, 3,
                "a".repeat(64), "sha256:" + proposalHashCharacter.repeat(64),
                proposal("proposed", configHashCharacter.repeat(64)), NOW);
    }

    private static AgentConfigurationProposal proposal(String prompt, String configHash) {
        return new AgentConfigurationProposal(
                "Shared Agent", null, prompt, null, null, null, 0.2,
                200_000, 4096, 25, "ask", true, false, false,
                List.of(), List.of(), List.of(), configHash);
    }

    private static void insertApproval(JdbcTemplate jdbc, String id, String operationHash) {
        jdbc.update("""
                INSERT INTO platform_approval_requests(
                    id,tenant_id,requested_by,action_type,resource_type,resource_id,
                    operation_hash,summary,state,expires_at,revision,created_at,updated_at)
                VALUES(CAST(? AS UUID),?,?,'AGENT_CONFIGURATION_CHANGE','AGENT_CONFIGURATION',?,
                       ?,'Agent configuration change','PENDING',?,1,?,?)
                """, id, TENANT, MEMBER, AGENT, operationHash,
                Timestamp.from(NOW.plusSeconds(3600)), Timestamp.from(NOW), Timestamp.from(NOW));
    }
}

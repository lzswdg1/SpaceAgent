package com.spaceagent.platform.observability;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.spaceagent.platform.observability.domain.TraceFilter;
import com.spaceagent.platform.observability.domain.TraceSpanType;
import com.spaceagent.platform.observability.domain.TraceStatus;
import com.spaceagent.platform.observability.infrastructure.persistence.PostgresTracingQueryRepository;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.sql.DataSource;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

@Testcontainers(disabledWithoutDocker = true)
class PlatformTracingPostgresTest {

    @Container
    private static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("pgvector/pgvector:pg17")
                    .withDatabaseName("spaceagent_tracing")
                    .withUsername("spaceagent")
                    .withPassword("spaceagent");

    private static final Instant NOW = Instant.parse("2026-08-23T12:00:00Z");
    private static final String AGENT_ID = "00000000-0000-4000-8000-000000000001";
    private static final String VERSION_ID = "00000000-0000-4000-8000-000000000002";
    private static final String CONVERSATION_ID = "00000000-0000-4000-8000-000000000003";
    private static final String RUN_ID = "legacy-run-1";
    private static final String CHILD_RUN_ID = "legacy-child-1";
    private static final String STEP_ID = "00000000-0000-4000-8000-000000000004";
    private static final String PROJECT_ID = "00000000-0000-4000-8000-000000000005";
    private static final String TASK_ID = "00000000-0000-4000-8000-000000000006";
    private static final String SOURCE_ID = "00000000-0000-4000-8000-000000000007";
    private static final String WORKSPACE_ID = "00000000-0000-4000-8000-000000000008";
    private static final String DIRECTORY_ID = "00000000-0000-4000-8000-000000000014";
    private static final String HANDOFF_ID = "00000000-0000-4000-8000-000000000009";
    private static DataSource dataSource;

    @BeforeAll
    static void migrateAndSeed() {
        dataSource = new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        Flyway.configure().dataSource(dataSource)
                .locations("classpath:db/platform-server", "classpath:db/platform-runtime")
                .load().migrate();
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        jdbc.update("""
                INSERT INTO platform_tenants(id,name,slug,status,created_at,updated_at)
                VALUES ('tenant-1','Tracing','tracing','ACTIVE',?,?),
                       ('tenant-2','Foreign','foreign','ACTIVE',?,?)
                """, ts(NOW), ts(NOW), ts(NOW), ts(NOW));
        jdbc.update("""
                INSERT INTO platform_users(id,tenant_id,external_id,display_name,created_at,updated_at)
                VALUES ('owner-1','tenant-1','owner@example.com','Owner',?,?),
                       ('owner-2','tenant-2','other@example.com','Other',?,?)
                """, ts(NOW), ts(NOW), ts(NOW), ts(NOW));
        jdbc.update("""
                INSERT INTO platform_tenant_memberships(
                    tenant_id,user_id,tenant_role,status,joined_at,updated_at)
                VALUES ('tenant-1','owner-1','OWNER','ACTIVE',?,?),
                       ('tenant-2','owner-2','OWNER','ACTIVE',?,?)
                """, ts(NOW), ts(NOW), ts(NOW), ts(NOW));
        jdbc.update("""
                INSERT INTO platform_agent_definitions(
                    id,owner_id,tenant_id,name,description,created_at,updated_at,status,revision)
                VALUES (?,'owner-1','tenant-1','Trace Agent',NULL,?,?,'ACTIVE',1)
                """, AGENT_ID, ts(NOW), ts(NOW));
        jdbc.update("""
                INSERT INTO platform_conversations(
                    id,tenant_id,user_id,agent_id,title,status,created_at,updated_at)
                VALUES (?,'tenant-1','owner-1',?,'Trace Session','ACTIVE',?,?)
                """, CONVERSATION_ID, AGENT_ID, ts(NOW), ts(NOW));
        jdbc.update("""
                INSERT INTO platform_agent_runs(
                    id,agent_id,tenant_id,owner_id,conversation_id,
                    state,execution_cursor,revision,created_at,updated_at,completed_at)
                VALUES (?, ?,'tenant-1','owner-1',?,'COMPLETED',
                    '{}'::jsonb,4,?,?,?),
                       (?, ?,'tenant-1','owner-1',?,'COMPLETED',
                    '{}'::jsonb,2,?,?,?)
                """, RUN_ID, AGENT_ID, CONVERSATION_ID,
                ts(NOW.minusSeconds(2)), ts(NOW), ts(NOW),
                CHILD_RUN_ID, AGENT_ID, CONVERSATION_ID,
                ts(NOW.minusSeconds(1)), ts(NOW), ts(NOW));
        jdbc.update("""
                INSERT INTO platform_run_steps(id,agent_run_id,sequence,type,state,created_at,completed_at)
                VALUES (?, ?, 0, 'chat-runtime', 'COMPLETED', ?, ?)
                """, STEP_ID, RUN_ID, ts(NOW.minusSeconds(2)), ts(NOW));
        jdbc.update("""
                INSERT INTO platform_run_checkpoints(
                    id,agent_run_id,sequence,state_snapshot,created_at)
                VALUES ('00000000-0000-4000-8000-000000000010',?,0,
                    '{"secret":"checkpoint-secret"}',?)
                """, RUN_ID, ts(NOW.minusSeconds(1)));
        jdbc.update("""
                INSERT INTO platform_model_call_ledger(
                    id,agent_run_id,run_step_id,logical_call_id,request_hash,status,
                    provider_id,model_id,revision,first_chunk_at,first_chunk_ms,response_payload,usage_payload,
                    created_at,updated_at)
                VALUES ('00000000-0000-4000-8000-000000000011',?,?, 'inference:0',?,
                    'SUCCEEDED','provider-1','model-1',2,?,100,
                    '{"content":"provider-response-secret"}'::jsonb,
                    '{"inputTokens":100,"outputTokens":20,"cacheReadTokens":5}'::jsonb,?,?)
                """, RUN_ID, STEP_ID, "b".repeat(64), ts(NOW.minusMillis(1700)),
                ts(NOW.minusMillis(1800)), ts(NOW.minusMillis(800)));
        jdbc.update("""
                INSERT INTO platform_tool_execution_ledger(
                    id,agent_run_id,run_step_id,tool_name,tool_call_id,idempotency_key,
                    arguments,input_hash,status,result,result_ref,error,started_at,completed_at,
                    revision,updated_at)
                VALUES ('00000000-0000-4000-8000-000000000012',?,?, 'search','tool-1','key',
                    'tool-arguments-secret',?,'SUCCEEDED','tool-result-secret','artifact:result',
                    NULL,?,?,2,?)
                """, RUN_ID, STEP_ID, "sha256:" + "c".repeat(64),
                ts(NOW.minusMillis(700)), ts(NOW.minusMillis(300)), ts(NOW.minusMillis(300)));
        jdbc.update("""
                INSERT INTO platform_run_events(
                    id,agent_run_id,sequence_number,event_type,payload,execution_cursor,created_at)
                VALUES ('event-non-uuid',?,0,'RUN_CREATED',
                    '{"secret":"event-payload-secret"}'::jsonb,'{}'::jsonb,?)
                """, RUN_ID, ts(NOW.minusSeconds(2)));

        seedProjectEvidence(jdbc);
        seedAutomation(jdbc);
    }

    @Test
    void viewsCorrelateDurableEvidenceWithoutLeakingPayloads() throws Exception {
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        var repository = new PostgresTracingQueryRepository(new JdbcTemplate(dataSource), mapper);
        TraceFilter filter = new TraceFilter(
                "tenant-1", "owner-1", null, AGENT_ID, CONVERSATION_ID,
                "Trace", 100L, null, NOW.minusSeconds(60), NOW.plusSeconds(1));
        var page = repository.search(filter, 50, 0);
        assertThat(page.total()).isEqualTo(2);
        var summary = page.traces().stream()
                .filter(value -> value.metadata().get("agentRunId").equals(RUN_ID))
                .findFirst().orElseThrow();
        assertThatCode(() -> UUID.fromString(summary.id())).doesNotThrowAnyException();
        assertThat(summary.status()).isEqualTo(TraceStatus.SUCCESS);
        assertThat(summary.llmTurns()).isEqualTo(1);
        assertThat(summary.toolCalls()).isEqualTo(1);
        assertThat(summary.totalTokens()).isEqualTo(125);
        assertThat(summary.costUsd()).isNull();
        assertThat(summary.firstTokenMs()).isEqualTo(100L);
        assertThat(summary.metadata())
                .containsEntry("automationState", "SUCCEEDED")
                .containsEntry("handoffCount", 1)
                .containsEntry("delegationCount", 1)
                .containsEntry("reviewCount", 1)
                .containsEntry("artifactCount", 1);

        var detail = repository.find("tenant-1", "owner-1", summary.id()).orElseThrow();
        assertThat(detail.spans()).extracting(span -> span.spanType())
                .contains(TraceSpanType.ROOT, TraceSpanType.LLM,
                        TraceSpanType.TOOL, TraceSpanType.SYSTEM);
        assertThat(detail.spans()).extracting(span -> span.name())
                .contains("llm:model-1", "search", "automation:succeeded",
                        "handoff:completed", "delegation:completed",
                        "review:approved", "artifact:test_report");
        String json = mapper.writeValueAsString(detail);
        assertThat(json).doesNotContain(
                "secret-system-prompt", "checkpoint-secret", "provider-response-secret",
                "tool-arguments-secret", "tool-result-secret", "event-payload-secret");

        assertThat(repository.find("tenant-2", "owner-2", summary.id())).isEmpty();
        assertThat(repository.search(new TraceFilter(
                "tenant-1", "owner-2", null, null, null, null,
                null, null, null, null), 10, 0).traces()).isEmpty();
        var stats = repository.stats(filter);
        assertThat(stats.totalTraces()).isEqualTo(2);
        assertThat(stats.totalTokens()).isEqualTo(125);
        assertThat(stats.averageFirstTokenMs()).isEqualTo(100D);
        assertThat(stats.totalCostUsd()).isNull();
        assertThat(new JdbcTemplate(dataSource).queryForObject(
                "SELECT MAX(installed_rank) FROM flyway_schema_history", Integer.class))
                .isEqualTo(110);
    }

    private static void seedProjectEvidence(JdbcTemplate jdbc) {
        jdbc.update("""
                INSERT INTO platform_projects(id,tenant_id,owner_id,name,status,created_at,updated_at)
                VALUES (CAST(? AS UUID),'tenant-1','owner-1','Trace Project','ACTIVE',?,?)
                """, PROJECT_ID, ts(NOW), ts(NOW));
        jdbc.update("""
                INSERT INTO platform_tasks(
                    id,project_id,tenant_id,owner_user_id,title,goal,
                    constraints_json,acceptance_criteria_json,
                    state,created_at,updated_at)
                VALUES (CAST(? AS UUID),CAST(? AS UUID),'tenant-1','owner-1',
                    'Trace Task','trace','[]','[]',
                    'COMPLETED',?,?)
                """, TASK_ID, PROJECT_ID, ts(NOW), ts(NOW));
        jdbc.update("""
                INSERT INTO platform_source_repositories(
                    id,project_id,tenant_id,provider_repository_id,display_name,remote_url,
                    default_branch,repository_type,state,visibility,created_by,created_at,updated_at)
                VALUES (CAST(? AS UUID),CAST(? AS UUID),'tenant-1','trace-source','Trace Source',
                    'https://example.com/repo.git','main','GENERIC','READY','PRIVATE','owner-1',?,?)
                """, SOURCE_ID, PROJECT_ID, ts(NOW), ts(NOW));
        jdbc.update("""
                INSERT INTO platform_project_directories(
                    id,tenant_id,project_id,source_repository_id,name,relative_path,
                    is_default,state,created_by,created_at,updated_at)
                VALUES(CAST(? AS UUID),'tenant-1',CAST(? AS UUID),CAST(? AS UUID),
                    'Trace Source','.',FALSE,'ACTIVE','owner-1',?,?)
                """, DIRECTORY_ID, PROJECT_ID, SOURCE_ID, ts(NOW), ts(NOW));
        jdbc.update("""
                INSERT INTO platform_workspaces(
                    id,tenant_id,project_id,project_directory_id,task_id,source_repository_id,mode,worktree_key,
                    base_ref,branch_name,worktree_ref,head_commit,writable,state,revision,
                    created_by,created_at,updated_at,isolation_key)
                VALUES (CAST(? AS UUID),'tenant-1',CAST(? AS UUID),CAST(? AS UUID),CAST(? AS UUID),
                    CAST(? AS UUID),'MANAGED_GIT',CAST(? AS UUID),'main','trace-branch',
                    'managed:trace',? ,TRUE,'READY',1,'owner-1',?,?,'delegation:trace')
                """, WORKSPACE_ID, PROJECT_ID, DIRECTORY_ID, TASK_ID, SOURCE_ID,
                "00000000-0000-4000-8000-000000000013", "d".repeat(40), ts(NOW), ts(NOW));
        jdbc.update("""
                INSERT INTO platform_run_handoffs(
                    id,source_agent_run_id,target_agent_run_id,state,goal,current_state,
                    completed_work,decisions,failed_attempts,changed_files,test_status,
                    blockers,next_actions,created_at,completed_at)
                VALUES (?, ?, ?, 'COMPLETED','goal','state','work','decisions','failures',
                    'files','PASSED','blockers','next',?,?)
                """, HANDOFF_ID, RUN_ID, CHILD_RUN_ID, ts(NOW.minusSeconds(1)), ts(NOW));
        jdbc.update("""
                INSERT INTO platform_agent_delegations(
                    id,tenant_id,parent_run_id,child_run_id,task_plan_id,plan_step_id,
                    child_task_id,target_agent_id,workspace_id,
                    handoff_id,state,created_at,updated_at)
                VALUES ('00000000-0000-4000-8000-000000000014','tenant-1',?,?,
                    '00000000-0000-4000-8000-000000000015',
                    '00000000-0000-4000-8000-000000000016',CAST(? AS UUID),?,
                    CAST(? AS UUID),?,'COMPLETED',?,?)
                """, RUN_ID, CHILD_RUN_ID, TASK_ID, AGENT_ID,
                WORKSPACE_ID, HANDOFF_ID, ts(NOW.minusSeconds(1)), ts(NOW));
        jdbc.update("""
                INSERT INTO platform_agent_reviews(
                    id,tenant_id,parent_run_id,child_run_id,reviewer_agent_id,
                    artifact_ids_json,decision,evidence,created_at,decided_at)
                VALUES ('00000000-0000-4000-8000-000000000017','tenant-1',?,?,?,
                    '["00000000-0000-4000-8000-000000000018"]','APPROVED',
                    'private-review-evidence',?,?)
                """, RUN_ID, CHILD_RUN_ID, AGENT_ID, ts(NOW.minusMillis(500)), ts(NOW));
        jdbc.update("""
                INSERT INTO platform_artifacts(
                    id,tenant_id,project_id,task_id,agent_run_id,workspace_id,artifact_type,
                    name,content_ref,content_hash,summary,metadata_json,created_at)
                VALUES ('00000000-0000-4000-8000-000000000018','tenant-1',CAST(? AS UUID),
                    CAST(? AS UUID),?,CAST(? AS UUID),'TEST_REPORT','tests',
                    'secret-content-reference',?,'secret-artifact-summary',
                    '{"secret":"artifact-metadata-secret"}',?)
                """, PROJECT_ID, TASK_ID, RUN_ID, WORKSPACE_ID,
                "sha256:" + "e".repeat(64), ts(NOW.minusMillis(200)));
    }

    private static void seedAutomation(JdbcTemplate jdbc) {
        String scheduleId = "00000000-0000-4000-8000-000000000019";
        String continuationId = "00000000-0000-4000-8000-000000000020";
        jdbc.update("""
                INSERT INTO platform_runtime_continuations(
                    id,agent_run_id,continuation_type,deduplication_key,payload,state,
                    available_at,attempt,max_attempts,revision,created_at,updated_at,completed_at)
                VALUES (CAST(? AS UUID),?,'AUTOMATION_EXECUTION','automation:trace','{}',
                    'COMPLETED',?,1,1,2,?,?,?)
                """, continuationId, RUN_ID, ts(NOW.minusSeconds(2)),
                ts(NOW.minusSeconds(2)), ts(NOW), ts(NOW));
        jdbc.update("""
                INSERT INTO platform_automation_schedules(
                    id,tenant_id,owner_id,agent_id,description,prompt,schedule_type,
                    scheduled_at,timezone,state,next_fire_at,run_count,max_retries,revision,
                    created_at,updated_at)
                VALUES (CAST(? AS UUID),'tenant-1','owner-1',?,'Trace Automation',
                    'automation-prompt-secret','ONE_TIME',?,'UTC','COMPLETED',NULL,1,1,2,?,?)
                """, scheduleId, AGENT_ID, ts(NOW.minusSeconds(2)),
                ts(NOW.minusSeconds(3)), ts(NOW));
        jdbc.update("""
                INSERT INTO platform_automation_executions(
                    id,schedule_id,tenant_id,owner_id,agent_id,fire_key,
                    scheduled_for,trigger_type,state,operation_hash,conversation_id,
                    dispatch_run_id,continuation_id,agent_run_id,started_at,completed_at,
                    input_tokens,output_tokens,revision,created_at,updated_at)
                VALUES ('00000000-0000-4000-8000-000000000021',CAST(? AS UUID),'tenant-1',
                    'owner-1',?,'scheduled:trace',?,'SCHEDULED','SUCCEEDED',?,
                    ?,?,CAST(? AS UUID),?,?,?,100,20,3,?,?)
                """, scheduleId, AGENT_ID, ts(NOW.minusSeconds(2)),
                "sha256:" + "f".repeat(64), CONVERSATION_ID, RUN_ID, continuationId,
                RUN_ID, ts(NOW.minusSeconds(2)), ts(NOW), ts(NOW.minusSeconds(2)), ts(NOW));
    }

    private static Timestamp ts(Instant value) {
        return Timestamp.from(value);
    }
}

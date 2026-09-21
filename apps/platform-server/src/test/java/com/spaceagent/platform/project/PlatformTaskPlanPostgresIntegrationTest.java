package com.spaceagent.platform.project;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.spaceagent.platform.conversation.api.StartConversationCommand;
import com.spaceagent.platform.conversation.application.ConversationApplicationService;
import com.spaceagent.platform.conversation.infrastructure.persistence.PostgresConversationRepository;
import com.spaceagent.platform.conversation.infrastructure.persistence.PostgresMessageRepository;
import com.spaceagent.platform.identity.api.IdentityOwnershipPort;
import com.spaceagent.platform.project.api.CreateProjectCommand;
import com.spaceagent.platform.project.api.CreateTaskCommand;
import com.spaceagent.platform.project.api.CreateTaskPlanCommand;
import com.spaceagent.platform.project.api.CreateChatRootTaskCommand;
import com.spaceagent.platform.project.api.CreateChatTaskPlanProposalCommand;
import com.spaceagent.platform.project.api.ChatTaskPlanActionCommand;
import com.spaceagent.platform.project.api.GetChatTaskPlanQuery;
import com.spaceagent.platform.project.api.GetChatTaskQuery;
import com.spaceagent.platform.project.api.ListChatTasksQuery;
import com.spaceagent.platform.project.api.TaskPlanAction;
import com.spaceagent.platform.project.api.TaskPlanActionCommand;
import com.spaceagent.platform.project.application.ProjectAccessPolicy;
import com.spaceagent.platform.project.application.ProjectApplicationService;
import com.spaceagent.platform.project.application.TaskApplicationService;
import com.spaceagent.platform.project.application.TaskPlanApplicationService;
import com.spaceagent.platform.project.domain.TaskPlanStatus;
import com.spaceagent.platform.project.domain.TaskState;
import com.spaceagent.platform.project.infrastructure.persistence.PostgresProjectMembershipRepository;
import com.spaceagent.platform.project.infrastructure.persistence.PostgresProjectRepository;
import com.spaceagent.platform.project.infrastructure.persistence.PostgresTaskPlanRepository;
import com.spaceagent.platform.project.infrastructure.persistence.PostgresTaskRepository;
import com.spaceagent.platform.runtime.api.CompleteAgentRunCommand;
import com.spaceagent.platform.runtime.api.CreateCheckpointCommand;
import com.spaceagent.platform.runtime.api.StartAgentRunCommand;
import com.spaceagent.platform.runtime.application.RuntimeApplicationService;
import com.spaceagent.platform.runtime.domain.RunEventType;
import com.spaceagent.platform.runtime.infrastructure.persistence.PostgresRuntimeLedgerRepository;
import com.spaceagent.platform.tooling.api.ToolExecutionLedgerApplicationApi;
import com.spaceagent.shared.id.UuidGenerator;
import com.spaceagent.shared.time.TimeProvider;
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
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

@Testcontainers(disabledWithoutDocker = true)
class PlatformTaskPlanPostgresIntegrationTest {

    private static final Instant NOW = Instant.parse("2026-08-22T20:00:00Z");

    @Container
    private static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("pgvector/pgvector:pg17")
                    .withDatabaseName("spaceagent_task_plan")
                    .withUsername("spaceagent")
                    .withPassword("spaceagent");

    @BeforeAll
    static void migrateAndSeedIdentity() {
        Flyway.configure()
                .dataSource(newDataSource())
                .locations("classpath:db/platform-server", "classpath:db/platform-runtime")
                .load()
                .migrate();
        JdbcTemplate jdbc = new JdbcTemplate(newDataSource());
        jdbc.update("""
                INSERT INTO platform_tenants (id, name, slug, status, created_at, updated_at)
                VALUES ('tenant-1', 'Tenant 1', 'tenant-1', 'ACTIVE', ?, ?)
                """, Timestamp.from(NOW), Timestamp.from(NOW));
        jdbc.update("""
                INSERT INTO platform_users (
                    id, tenant_id, external_id, display_name, created_at, updated_at
                ) VALUES ('owner-1', 'tenant-1', 'owner@example.com', 'Owner', ?, ?)
                """, Timestamp.from(NOW), Timestamp.from(NOW));
        jdbc.update("""
                INSERT INTO platform_tenant_memberships (
                    tenant_id, user_id, tenant_role, status, joined_at, updated_at
                ) VALUES ('tenant-1', 'owner-1', 'OWNER', 'ACTIVE', ?, ?)
                """, Timestamp.from(NOW), Timestamp.from(NOW));
        jdbc.update("""
                INSERT INTO platform_agent_definitions (
                    id, owner_id, tenant_id, name, description,
                    created_at, updated_at, status, revision
                ) VALUES ('agent-1', 'owner-1', 'tenant-1', 'Runtime Agent', NULL,
                          ?, ?, 'ACTIVE', 1)
                """, Timestamp.from(NOW), Timestamp.from(NOW));
    }

    @Test
    void persistsPlanDagApprovalCurrentPointerAndConversationFocus() {
        JdbcTemplate jdbc = new JdbcTemplate(newDataSource());
        ObjectMapper objectMapper = new ObjectMapper();
        PostgresProjectRepository projects = new PostgresProjectRepository(jdbc);
        PostgresProjectMembershipRepository memberships =
                new PostgresProjectMembershipRepository(jdbc);
        PostgresTaskRepository tasks = new PostgresTaskRepository(jdbc, objectMapper);
        PostgresTaskPlanRepository plans = new PostgresTaskPlanRepository(jdbc, objectMapper);
        IdentityOwnershipPort identity = (tenantId, userId) -> true;
        ProjectAccessPolicy access = new ProjectAccessPolicy(projects, memberships, identity, (t, u, w) -> {});
        UuidGenerator ids = new UuidGenerator();
        TimeProvider time = () -> NOW;
        ProjectApplicationService projectApi = new ProjectApplicationService(
                projects, memberships, access, ids, time);
        TaskApplicationService taskApi = new TaskApplicationService(tasks, access, ids, time);
        TaskPlanApplicationService planApi = new TaskPlanApplicationService(
                plans, tasks, access, ids, time, null, null);

        String projectId = projectApi.createProject(new CreateProjectCommand(
                "tenant-1", "owner-1", "Persistent Plan", null)).id();
        var root = taskApi.createTask(task(projectId, null, "Root"));
        var inspect = taskApi.createTask(task(projectId, root.id(), "Inspect"));
        var implement = taskApi.createTask(task(projectId, root.id(), "Implement"));
        var plan = planApi.createPlan(new CreateTaskPlanCommand(
                "tenant-1", "owner-1", projectId, root.id(), null, List.of(
                step("inspect", inspect.id(), List.of()),
                step("implement", implement.id(), List.of("inspect")))));
        planApi.transition(action(projectId, root.id(), plan.id(), TaskPlanAction.PROPOSE));
        planApi.transition(action(projectId, root.id(), plan.id(), TaskPlanAction.APPROVE));
        var active = planApi.transition(action(
                projectId, root.id(), plan.id(), TaskPlanAction.ACTIVATE));
        assertThat(active.status()).isEqualTo(TaskPlanStatus.ACTIVE);

        PostgresTaskPlanRepository reconnectedPlans = new PostgresTaskPlanRepository(
                new JdbcTemplate(newDataSource()), objectMapper);
        PostgresTaskRepository reconnectedTasks = new PostgresTaskRepository(
                new JdbcTemplate(newDataSource()), objectMapper);
        assertThat(reconnectedPlans.findSteps(plan.id())).hasSize(2);
        assertThat(reconnectedPlans.findDependencies(plan.id())).hasSize(1);
        assertThat(reconnectedTasks.findById(root.id()).orElseThrow().currentTaskPlanId())
                .isEqualTo(plan.id());

        ConversationApplicationService conversations = new ConversationApplicationService(
                new PostgresConversationRepository(jdbc), new PostgresMessageRepository(jdbc),
                ids, time, taskApi);
        var conversation = conversations.start(new StartConversationCommand(
                projectId, null, root.id(), "tenant-1", "owner-1", null, "Planning"));
        assertThat(new PostgresConversationRepository(new JdbcTemplate(newDataSource()))
                .findById(conversation.id()).orElseThrow().activeTaskId()).isEqualTo(root.id());

        RuntimeApplicationService runtime = new RuntimeApplicationService(
                new PostgresRuntimeLedgerRepository(jdbc, objectMapper),
                mock(ToolExecutionLedgerApplicationApi.class), objectMapper,
                ids, time, null, null, planApi);
        var firstStep = active.steps().get(0);
        var run = runtime.startRun(new StartAgentRunCommand(
                "tenant-1", "owner-1", "agent-1",
                "00000000-0000-4000-8000-000000000001", conversation.id(),
                projectId, firstStep.childTaskId(), active.id(), firstStep.id()));
        runtime.markRunInProgress(run.id());
        runtime.createCheckpoint(new CreateCheckpointCommand(
                run.id(), "{\"phase\":\"execute\",\"stepId\":\"postgres-step\"}"));
        runtime.complete(new CompleteAgentRunCommand(run.id()));

        var reconnectedRuntime = new RuntimeApplicationService(
                new PostgresRuntimeLedgerRepository(
                        new JdbcTemplate(newDataSource()), objectMapper),
                mock(ToolExecutionLedgerApplicationApi.class), objectMapper,
                ids, time, null, null, planApi);
        var persistedRun = reconnectedRuntime.findRun(run.id()).orElseThrow();
        assertThat(persistedRun.projectId()).isEqualTo(projectId);
        assertThat(persistedRun.taskId()).isEqualTo(firstStep.childTaskId());
        assertThat(persistedRun.executionCursor().phase()).isEqualTo("execute");
        assertThat(reconnectedRuntime.findEvents(run.id()))
                .extracting(event -> event.type())
                .containsExactly(
                        RunEventType.RUN_CREATED,
                        RunEventType.RUN_STATE_CHANGED,
                        RunEventType.CHECKPOINT_CREATED,
                        RunEventType.CURSOR_ADVANCED,
                        RunEventType.RUN_STATE_CHANGED);

        var secondStep = active.steps().get(1);
        var secondRun = reconnectedRuntime.startRun(new StartAgentRunCommand(
                "tenant-1", "owner-1", "agent-1",
                "00000000-0000-4000-8000-000000000001", conversation.id(),
                projectId, secondStep.childTaskId(), active.id(), secondStep.id()));
        reconnectedRuntime.markRunInProgress(secondRun.id());
        reconnectedRuntime.complete(new CompleteAgentRunCommand(secondRun.id()));

        var terminalPlans = new PostgresTaskPlanRepository(
                new JdbcTemplate(newDataSource()), objectMapper);
        var terminalTasks = new PostgresTaskRepository(
                new JdbcTemplate(newDataSource()), objectMapper);
        assertThat(terminalPlans.findById(active.id()).orElseThrow().status())
                .isEqualTo(TaskPlanStatus.COMPLETED);
        assertThat(terminalTasks.findById(root.id()).orElseThrow().state())
                .isEqualTo(TaskState.COMPLETED);
        assertThat(terminalTasks.findById(firstStep.childTaskId()).orElseThrow().state())
                .isEqualTo(TaskState.COMPLETED);
        assertThat(terminalTasks.findById(secondStep.childTaskId()).orElseThrow().state())
                .isEqualTo(TaskState.COMPLETED);

        assertThat(columnType(jdbc, "platform_task_plans", "id")).isEqualTo("uuid");
        assertThat(columnType(jdbc, "platform_tasks", "current_task_plan_id"))
                .isEqualTo("uuid");
        assertThat(columnType(jdbc, "platform_conversations", "active_task_id"))
                .isEqualTo("uuid");
        assertThat(columnType(jdbc, "platform_agent_runs", "project_uuid"))
                .isEqualTo("uuid");
        assertThat(columnType(jdbc, "platform_agent_runs", "execution_cursor"))
                .isEqualTo("jsonb");
        assertThat(columnType(jdbc, "platform_run_events", "payload"))
                .isEqualTo("jsonb");
        assertThat(jdbc.queryForObject("""
                SELECT constraint_name
                FROM information_schema.table_constraints
                WHERE table_schema = current_schema()
                  AND table_name = 'platform_plan_step_dependencies'
                  AND constraint_name = 'fk_platform_plan_step_dependency_parent'
                """, String.class)).isNotNull();
    }

    @Test
    void persistsChatProposalDagIdempotencyLifecycleAndConversationCleanup() {
        JdbcTemplate jdbc = new JdbcTemplate(newDataSource());
        ObjectMapper objectMapper = new ObjectMapper();
        PostgresTaskRepository tasks = new PostgresTaskRepository(jdbc, objectMapper);
        PostgresTaskPlanRepository planRepository = new PostgresTaskPlanRepository(jdbc, objectMapper);
        ProjectAccessPolicy access = new ProjectAccessPolicy(
                new PostgresProjectRepository(jdbc),
                new PostgresProjectMembershipRepository(jdbc),
                (tenantId, userId) -> true, (t, u, w) -> {});
        UuidGenerator ids = new UuidGenerator();
        TaskApplicationService taskApi = new TaskApplicationService(tasks, access, ids, () -> NOW);
        TaskPlanApplicationService planApi = new TaskPlanApplicationService(
                planRepository, tasks, access, ids, () -> NOW, null, null);
        ConversationApplicationService conversations = new ConversationApplicationService(
                new PostgresConversationRepository(jdbc), new PostgresMessageRepository(jdbc),
                ids, () -> NOW, taskApi);
        var conversation = conversations.start(new StartConversationCommand(
                null, null, null, "tenant-1", "owner-1", "agent-1", "Chat planning"));
        var reservation = conversations.reserveReply(
                "tenant-1", "owner-1", conversation.id(), "Plan the research");
        var root = taskApi.createOrGetChatRootTask(new CreateChatRootTaskCommand(
                "tenant-1", "owner-1", conversation.id(), reservation.userMessage().id(),
                "Plan research", "Plan the research"));
        String sourceRun = UUID.randomUUID().toString();
        jdbc.update("""
                INSERT INTO platform_agent_runs(
                    id, agent_id, owner_id, tenant_id, conversation_id,
                    state, created_at, updated_at)
                VALUES(?, 'agent-1', 'owner-1', 'tenant-1', ?,
                       'SUCCEEDED', ?, ?)
                """, sourceRun, conversation.id(), Timestamp.from(NOW), Timestamp.from(NOW));
        jdbc.update("""
                INSERT INTO platform_agent_run_configuration_snapshots(
                    id, run_id, agent_id, tenant_id, owner_id, snapshot_state,
                    agent_revision, config_hash,
                    system_prompt, model_pool_id, model_provider_id, model_id, temperature,
                    max_context_tokens, max_output_tokens, max_turns, permission_mode,
                    memory_enabled, rag_enabled, network_enabled, knowledge_base_ids,
                    enabled_tool_ids, skill_ids, source_updated_by, source_updated_at, captured_at)
                VALUES(?, ?, 'agent-1', 'tenant-1', 'owner-1', 'SNAPSHOTTED', 1, ?, NULL,
                       NULL, NULL, NULL, 0.2, 32768, 4096, 8, 'ask', TRUE, TRUE, FALSE,
                       '[]'::jsonb, '[]'::jsonb, '[]'::jsonb, 'owner-1', ?, ?)
                """, sourceRun, sourceRun, "a".repeat(64),
                Timestamp.from(NOW), Timestamp.from(NOW));
        var proposal = new CreateChatTaskPlanProposalCommand(
                "tenant-1", "owner-1", conversation.id(), root.id(), sourceRun, null,
                "Collect and review", List.of(
                new CreateChatTaskPlanProposalCommand.StepProposal(
                        "collect", "Collect evidence", List.of()),
                new CreateChatTaskPlanProposalCommand.StepProposal(
                        "review", "Review evidence", List.of("collect"))));

        var persisted = planApi.createChatProposal(proposal);
        assertThat(planApi.createChatProposal(proposal).id()).isEqualTo(persisted.id());
        assertThat(persisted.status()).isEqualTo(TaskPlanStatus.PROPOSED);
        assertThat(new PostgresTaskPlanRepository(
                new JdbcTemplate(newDataSource()), objectMapper)
                .findBySourceAgentRunId(sourceRun)).map(value -> value.id())
                .contains(persisted.id());
        assertThat(persisted.steps()).hasSize(2);
        assertThat(persisted.steps().get(1).dependencyStepIds())
                .containsExactly(persisted.steps().get(0).id());
        assertThat(taskApi.listChatTasks(new ListChatTasksQuery(
                "tenant-1", "owner-1", conversation.id(), 1)))
                .extracting(value -> value.id()).containsExactly(root.id());

        planApi.transitionChatPlan(new ChatTaskPlanActionCommand(
                "tenant-1", "owner-1", conversation.id(), root.id(), persisted.id(),
                TaskPlanAction.APPROVE));
        planApi.transitionChatPlan(new ChatTaskPlanActionCommand(
                "tenant-1", "owner-1", conversation.id(), root.id(), persisted.id(),
                TaskPlanAction.ACTIVATE));
        var firstStep = persisted.steps().get(0);
        var secondStep = persisted.steps().get(1);
        planApi.transitionChatPlanStep(new com.spaceagent.platform.project.api.TransitionChatPlanStepExecutionCommand(
                "tenant-1", "owner-1", conversation.id(), root.id(), firstStep.childTaskId(),
                persisted.id(), firstStep.id(),
                com.spaceagent.platform.project.api.PlanStepExecutionAction.START));
        planApi.transitionChatPlanStep(new com.spaceagent.platform.project.api.TransitionChatPlanStepExecutionCommand(
                "tenant-1", "owner-1", conversation.id(), root.id(), firstStep.childTaskId(),
                persisted.id(), firstStep.id(),
                com.spaceagent.platform.project.api.PlanStepExecutionAction.COMPLETE));
        planApi.transitionChatPlanStep(new com.spaceagent.platform.project.api.TransitionChatPlanStepExecutionCommand(
                "tenant-1", "owner-1", conversation.id(), root.id(), secondStep.childTaskId(),
                persisted.id(), secondStep.id(),
                com.spaceagent.platform.project.api.PlanStepExecutionAction.START));
        planApi.transitionChatPlanStep(new com.spaceagent.platform.project.api.TransitionChatPlanStepExecutionCommand(
                "tenant-1", "owner-1", conversation.id(), root.id(), secondStep.childTaskId(),
                persisted.id(), secondStep.id(),
                com.spaceagent.platform.project.api.PlanStepExecutionAction.COMPLETE));
        assertThat(taskApi.getChatTask(new GetChatTaskQuery(
                "tenant-1", "owner-1", conversation.id(), root.id())).currentTaskPlanId())
                .isEqualTo(persisted.id());
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM flyway_schema_history WHERE success", Long.class))
                .isEqualTo(110L);

        conversations.delete(new com.spaceagent.platform.conversation.api.DeleteConversationCommand(
                conversation.id()));
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM platform_task_plans WHERE id = CAST(? AS UUID)",
                Long.class, persisted.id())).isZero();
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM platform_tasks WHERE conversation_id = ?",
                Long.class, conversation.id())).isZero();
    }

    @Test
    void v1050UpgradesV1049ProjectPlansWithoutChangingProjectScope() {
        String schema = "chat_plan_upgrade";
        Flyway.configure().dataSource(schemaDataSource(schema))
                .locations("classpath:db/platform-server", "classpath:db/platform-runtime")
                .target(MigrationVersion.fromVersion("1049")).load().migrate();
        JdbcTemplate jdbc = new JdbcTemplate(schemaDataSource(schema));
        String project = UUID.randomUUID().toString();
        String task = UUID.randomUUID().toString();
        String plan = UUID.randomUUID().toString();
        jdbc.update("INSERT INTO platform_tenants(id,name,slug,status,created_at,updated_at) "
                + "VALUES('tenant-upgrade','Upgrade','chat-plan-upgrade','ACTIVE',?,?)",
                Timestamp.from(NOW), Timestamp.from(NOW));
        jdbc.update("INSERT INTO platform_users(id,tenant_id,external_id,display_name,created_at,updated_at) "
                + "VALUES('owner-upgrade','tenant-upgrade','upgrade@example.com','Upgrade',?,?)",
                Timestamp.from(NOW), Timestamp.from(NOW));
        jdbc.update("INSERT INTO platform_tenant_memberships(tenant_id,user_id,tenant_role,status,joined_at,updated_at) "
                + "VALUES('tenant-upgrade','owner-upgrade','OWNER','ACTIVE',?,?)",
                Timestamp.from(NOW), Timestamp.from(NOW));
        jdbc.update("INSERT INTO platform_projects(id,tenant_id,owner_id,name,status,created_at,updated_at) "
                + "VALUES(CAST(? AS UUID),'tenant-upgrade','owner-upgrade','Upgrade','ACTIVE',?,?)",
                project, Timestamp.from(NOW), Timestamp.from(NOW));
        jdbc.update("INSERT INTO platform_tasks(id,project_id,tenant_id,owner_user_id,title,goal,state,created_at,updated_at) "
                + "VALUES(CAST(? AS UUID),CAST(? AS UUID),'tenant-upgrade','owner-upgrade','Root','Goal','PENDING',?,?)",
                task, project, Timestamp.from(NOW), Timestamp.from(NOW));
        jdbc.update("INSERT INTO platform_task_plans(id,project_id,root_task_id,version_number,status,created_by,created_at,updated_at) "
                + "VALUES(CAST(? AS UUID),CAST(? AS UUID),CAST(? AS UUID),1,'DRAFT','owner-upgrade',?,?)",
                plan, project, task, Timestamp.from(NOW), Timestamp.from(NOW));

        Flyway.configure().dataSource(schemaDataSource(schema))
                .locations("classpath:db/platform-server", "classpath:db/platform-runtime").load().migrate();

        assertThat(jdbc.queryForMap(
                "SELECT project_id::text,conversation_id,source_agent_run_id FROM platform_task_plans WHERE id=CAST(? AS UUID)",
                plan)).containsEntry("project_id", project)
                .containsEntry("conversation_id", null)
                .containsEntry("source_agent_run_id", null);
        assertThat(jdbc.queryForObject(
                "SELECT version FROM flyway_schema_history WHERE success ORDER BY installed_rank DESC LIMIT 1",
                String.class)).isEqualTo("1098");
    }

    private static CreateTaskCommand task(String projectId, String parentId, String title) {
        return new CreateTaskCommand(
                "tenant-1", "owner-1", projectId, parentId,
                title, "Goal " + title, null, List.of(), List.of("done"));
    }

    private static CreateTaskPlanCommand.PlanStepDraft step(
            String key,
            String childTaskId,
            List<String> dependencies) {
        return new CreateTaskPlanCommand.PlanStepDraft(
                key, childTaskId, dependencies, null, null,
                "Deliver " + key, List.of("accepted"), false);
    }

    private static TaskPlanActionCommand action(
            String projectId,
            String rootTaskId,
            String planId,
            TaskPlanAction action) {
        return new TaskPlanActionCommand(
                "tenant-1", "owner-1", projectId, rootTaskId, planId, action);
    }

    private static String columnType(JdbcTemplate jdbc, String table, String column) {
        return jdbc.queryForObject("""
                SELECT data_type FROM information_schema.columns
                WHERE table_schema = current_schema()
                  AND table_name = ? AND column_name = ?
                """, String.class, table, column);
    }

    private static DriverManagerDataSource newDataSource() {
        return new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    }

    private static DriverManagerDataSource schemaDataSource(String schema) {
        return com.spaceagent.platform.support.PostgresSchemaDataSource.forSchema(POSTGRES, schema);
    }
}

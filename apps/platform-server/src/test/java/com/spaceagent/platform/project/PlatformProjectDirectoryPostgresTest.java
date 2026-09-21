package com.spaceagent.platform.project;

import com.spaceagent.platform.conversation.api.StartConversationCommand;
import com.spaceagent.platform.conversation.application.ConversationApplicationService;
import com.spaceagent.platform.conversation.infrastructure.persistence.PostgresConversationRepository;
import com.spaceagent.platform.conversation.infrastructure.persistence.PostgresMessageRepository;
import com.spaceagent.platform.identity.api.AddTenantMembershipCommand;
import com.spaceagent.platform.identity.api.CreateTenantCommand;
import com.spaceagent.platform.identity.api.CreateUserCommand;
import com.spaceagent.platform.identity.application.IdentityApplicationService;
import com.spaceagent.platform.identity.domain.TenantRole;
import com.spaceagent.platform.identity.infrastructure.persistence.PostgresIdentityRepository;
import com.spaceagent.platform.project.api.CreateProjectCommand;
import com.spaceagent.platform.project.api.CreateTaskCommand;
import com.spaceagent.platform.project.api.ProjectDirectoryApplicationApi;
import com.spaceagent.platform.project.api.WorkspaceApplicationApi;
import com.spaceagent.platform.project.application.LocalWorkspaceBridgeApplicationService;
import com.spaceagent.platform.project.application.ProjectAccessPolicy;
import com.spaceagent.platform.project.application.ProjectApplicationService;
import com.spaceagent.platform.project.application.ProjectDirectoryApplicationService;
import com.spaceagent.platform.project.application.TaskApplicationService;
import com.spaceagent.platform.project.application.WorkspaceApplicationService;
import com.spaceagent.platform.project.domain.SourceRepository;
import com.spaceagent.platform.project.domain.SourceRepositoryState;
import com.spaceagent.platform.project.domain.SourceRepositoryType;
import com.spaceagent.platform.project.domain.SourceRepositoryVisibility;
import com.spaceagent.platform.project.domain.ProjectIntakeJob;
import com.spaceagent.platform.project.domain.ProjectIntakeState;
import com.spaceagent.platform.project.domain.Workspace;
import com.spaceagent.platform.project.domain.WorkspaceProvisioningGateway;
import com.spaceagent.platform.project.infrastructure.persistence.PostgresBridgeWorkspaceCommandRepository;
import com.spaceagent.platform.project.infrastructure.persistence.PostgresLocalWorkspaceBridgeRepository;
import com.spaceagent.platform.project.infrastructure.persistence.PostgresProjectDirectoryRepository;
import com.spaceagent.platform.project.infrastructure.persistence.PostgresProjectMembershipRepository;
import com.spaceagent.platform.project.infrastructure.persistence.PostgresProjectIntakeRepository;
import com.spaceagent.platform.runtime.domain.ProjectCodingJob;
import com.spaceagent.platform.runtime.domain.ProjectCodingJobState;
import com.spaceagent.platform.runtime.infrastructure.persistence.PostgresProjectCodingJobRepository;
import com.spaceagent.platform.runtime.application.ProjectCodingJobApplicationService;
import com.spaceagent.platform.runtime.api.ProjectCodingJobApplicationApi;
import com.spaceagent.platform.runtime.application.ProjectRunHandoffApplicationService;
import com.spaceagent.platform.runtime.api.ProjectRunHandoffApplicationApi;
import com.spaceagent.platform.runtime.domain.ProjectRunHandoffState;
import com.spaceagent.platform.runtime.infrastructure.persistence.PostgresProjectRunHandoffRepository;
import com.spaceagent.platform.project.infrastructure.persistence.PostgresProjectRepository;
import com.spaceagent.platform.project.infrastructure.persistence.PostgresSourceRepositoryRepository;
import com.spaceagent.platform.project.infrastructure.persistence.PostgresTaskRepository;
import com.spaceagent.platform.project.infrastructure.persistence.PostgresWorkspaceRepository;
import com.spaceagent.shared.id.UuidGenerator;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers(disabledWithoutDocker = true)
class PlatformProjectDirectoryPostgresTest {
    @Container
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("pgvector/pgvector:pg17")
                    .withDatabaseName("project_directory")
                    .withUsername("spaceagent")
                    .withPassword("spaceagent");

    @BeforeAll
    static void migrate() {
        Flyway.configure().dataSource(dataSource())
                .locations("classpath:db/platform-server", "classpath:db/platform-runtime")
                .load().migrate();
    }

    @Test
    void persistsStrictDirectoryConversationWorkspaceHierarchy() {
        JdbcTemplate jdbc = new JdbcTemplate(dataSource());
        UuidGenerator ids = new UuidGenerator();
        IdentityApplicationService identity = new IdentityApplicationService(
                new PostgresIdentityRepository(jdbc), ids, () -> NOW);
        String tenant = identity.createTenant(new CreateTenantCommand(
                "Directory", "directory-" + suffix())).id();
        String user = identity.createUser(new CreateUserCommand(
                tenant, "directory-" + suffix() + "@example.com", "Directory")).id();
        identity.addTenantMembership(new AddTenantMembershipCommand(tenant, user, TenantRole.OWNER));

        var projectRepository = new PostgresProjectRepository(jdbc);
        var memberships = new PostgresProjectMembershipRepository(jdbc);
        var directoryRepository = new PostgresProjectDirectoryRepository(jdbc);
        var sourceRepository = new PostgresSourceRepositoryRepository(jdbc);
        ProjectAccessPolicy access = new ProjectAccessPolicy(
                projectRepository, memberships, (candidateTenant, candidateUser) -> true, (t, u, w) -> {});
        ProjectApplicationService projects = new ProjectApplicationService(
                projectRepository, memberships, access, ids, () -> NOW, directoryRepository);
        String project = projects.createProject(new CreateProjectCommand(
                tenant, user, "Directory Project " + suffix(), null)).id();
        SourceRepository source = source(project, tenant, user, "source-" + suffix());
        sourceRepository.save(source);
        ProjectDirectoryApplicationService directories = new ProjectDirectoryApplicationService(
                directoryRepository, sourceRepository, access, ids, () -> NOW);
        var directory = directories.create(new ProjectDirectoryApplicationApi.CreateCommand(
                tenant, user, project, source.id(), "Backend", "."));

        var taskRepository = new PostgresTaskRepository(jdbc, new com.fasterxml.jackson.databind.ObjectMapper());
        String task = new TaskApplicationService(taskRepository, access, ids, () -> NOW)
                .createTask(new CreateTaskCommand(
                        tenant, user, project, null, "Implement", "goal", null,
                        List.of(), List.of("tests pass"))).id();
        var bridges = new LocalWorkspaceBridgeApplicationService(
                new PostgresLocalWorkspaceBridgeRepository(jdbc), access, ids, () -> NOW);
        WorkspaceApplicationService workspaces = new WorkspaceApplicationService(
                new PostgresWorkspaceRepository(jdbc),
                new PostgresBridgeWorkspaceCommandRepository(jdbc), access, taskRepository,
                sourceRepository, new PostgresLocalWorkspaceBridgeRepository(jdbc), bridges,
                request -> new com.spaceagent.platform.project.domain.WorkspaceCheckoutCredentialPort
                        .CredentialLease() {
                    public String authorizationHeader() { return null; }
                    public void close() { }
                },
                new WorkspaceProvisioningGateway() {
                    public ProvisionedWorkspace provision(
                            Workspace workspace, SourceRepository ignored, String authorization) {
                        return new ProvisionedWorkspace(
                                "managed:" + workspace.id(), "a".repeat(40));
                    }
                    public void cleanup(Workspace workspace, SourceRepository ignored) { }
                }, ids, () -> NOW, directories);
        var workspace = workspaces.provision(new WorkspaceApplicationApi.ProvisionCommand(
                tenant, user, project, directory.id(), task, source.id(), "main", "primary"));
        assertThat(workspace.projectDirectoryId()).isEqualTo(directory.id());

        ConversationApplicationService conversations = new ConversationApplicationService(
                new PostgresConversationRepository(jdbc), new PostgresMessageRepository(jdbc),
                ids, () -> NOW, null, directories);
        var conversation = conversations.start(new StartConversationCommand(
                project, directory.id(), null, null, tenant, user, null, "Backend"));
        assertThat(conversation.projectDirectoryId()).isEqualTo(directory.id());
        assertThat(conversations.pageByProjectDirectory(
                tenant, user, project, directory.id(), 1, 20).total()).isEqualTo(1L);

        String defaultDirectory = directories.list(new ProjectDirectoryApplicationApi.ListQuery(
                        tenant, user, project)).stream()
                .filter(ProjectDirectoryApplicationApi.DirectoryView::defaultDirectory)
                .findFirst().orElseThrow().id();
        assertThatThrownBy(() -> jdbc.update("""
                UPDATE platform_workspaces SET project_directory_id = CAST(? AS UUID)
                 WHERE id = CAST(? AS UUID)
                """, defaultDirectory, workspace.id()))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM flyway_schema_history WHERE success", Long.class))
                .isEqualTo(110L);
    }

    @Test
    void v1083BindsExistingBlankRootWithoutMovingConversationsAndPersistsLogicalDeletion() {
        var dataSource=com.spaceagent.platform.support.PostgresSchemaDataSource.forSchema(POSTGRES,"root_storage_upgrade_"+suffix());
        Flyway.configure().dataSource(dataSource).locations("classpath:db/platform-server","classpath:db/platform-runtime")
                .target(MigrationVersion.fromVersion("1082")).load().migrate();
        JdbcTemplate jdbc=new JdbcTemplate(dataSource);var ids=new UuidGenerator();
        var identity=new IdentityApplicationService(new PostgresIdentityRepository(jdbc),ids,()->NOW);
        String tenant=identity.createTenant(new CreateTenantCommand("Root upgrade","root-"+suffix())).id();
        String user=identity.createUser(new CreateUserCommand(tenant,"root-"+suffix()+"@example.com","Owner")).id();
        identity.addTenantMembership(new AddTenantMembershipCommand(tenant,user,TenantRole.OWNER));
        var projectRepo=new PostgresProjectRepository(jdbc);var members=new PostgresProjectMembershipRepository(jdbc);
        var dirs=new PostgresProjectDirectoryRepository(jdbc);var access=new ProjectAccessPolicy(projectRepo,members,(t,u)->true, (t, u, w) -> {});
        var projects=new ProjectApplicationService(projectRepo,members,access,ids,()->NOW,dirs);
        String project=projects.createProject(new CreateProjectCommand(tenant,user,"Existing blank root",null)).id();
        String root=dirs.findDefault(project).orElseThrow().id();String conversation=ids.nextId();
        jdbc.update("""
                INSERT INTO platform_conversations(id,project_id,project_uuid,project_directory_id,tenant_id,user_id,title,status,created_at,updated_at)
                VALUES(?,?,CAST(? AS UUID),CAST(? AS UUID),?,?,'Kept conversation','ACTIVE',?,?)
                """,conversation,project,project,root,tenant,user,Timestamp.from(NOW),Timestamp.from(NOW));
        Flyway.configure().dataSource(dataSource).locations("classpath:db/platform-server","classpath:db/platform-runtime").load().migrate();
        assertThat(dirs.findById(root).orElseThrow().sourceRepositoryId()).isEqualTo(root);
        assertThat(jdbc.queryForObject("SELECT project_directory_id::text FROM platform_conversations WHERE id=?",String.class,conversation)).isEqualTo(root);
        assertThat(new PostgresSourceRepositoryRepository(jdbc).findById(root).orElseThrow().state()).isEqualTo(SourceRepositoryState.PROVISIONING);
        dirs.save(dirs.findById(root).orElseThrow().archive(NOW.plusSeconds(1)));
        assertThat(new PostgresProjectDirectoryRepository(jdbc).findById(root).orElseThrow().state())
                .isEqualTo(com.spaceagent.platform.project.domain.ProjectDirectoryState.ARCHIVED);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM platform_conversations WHERE id=?",Integer.class,conversation)).isEqualTo(1);
    }

    @Test
    void v1045PersistsAndReclaimsProjectIntakeWithFencing() {
        JdbcTemplate jdbc = new JdbcTemplate(dataSource());
        UuidGenerator ids = new UuidGenerator();
        IdentityApplicationService identity = new IdentityApplicationService(
                new PostgresIdentityRepository(jdbc), ids, () -> NOW);
        String tenant = identity.createTenant(new CreateTenantCommand(
                "Intake", "intake-" + suffix())).id();
        String user = identity.createUser(new CreateUserCommand(
                tenant, "intake-" + suffix() + "@example.com", "Intake")).id();
        identity.addTenantMembership(new AddTenantMembershipCommand(tenant, user, TenantRole.OWNER));

        var projectRepository = new PostgresProjectRepository(jdbc);
        var memberships = new PostgresProjectMembershipRepository(jdbc);
        var directoryRepository = new PostgresProjectDirectoryRepository(jdbc);
        var sourceRepository = new PostgresSourceRepositoryRepository(jdbc);
        ProjectAccessPolicy access = new ProjectAccessPolicy(
                projectRepository, memberships, (candidateTenant, candidateUser) -> true, (t, u, w) -> {});
        String project = new ProjectApplicationService(
                projectRepository, memberships, access, ids, () -> NOW, directoryRepository)
                .createProject(new CreateProjectCommand(
                        tenant, user, "Intake Project " + suffix(), null)).id();
        SourceRepository source = source(project, tenant, user, "intake-source-" + suffix());
        sourceRepository.save(source);
        var directory = new ProjectDirectoryApplicationService(
                directoryRepository, sourceRepository, access, ids, () -> NOW)
                .create(new ProjectDirectoryApplicationApi.CreateCommand(
                        tenant, user, project, source.id(), "Repository", "."));

        String agent = UUID.randomUUID().toString();
        String configurationHash = "a".repeat(64);
        jdbc.update("""
                INSERT INTO platform_agent_definitions(
                    id,owner_id,tenant_id,name,description,archived_at,
                    status,revision,created_at,updated_at)
                VALUES(?,?,?,'Intake Agent',NULL,NULL,'ACTIVE',1,?,?)
                """, agent, user, tenant, Timestamp.from(NOW), Timestamp.from(NOW));
        var conversation = new ConversationApplicationService(
                new PostgresConversationRepository(jdbc), new PostgresMessageRepository(jdbc),
                ids, () -> NOW, null,
                new ProjectDirectoryApplicationService(
                        directoryRepository, sourceRepository, access, ids, () -> NOW))
                .start(new StartConversationCommand(
                        project, directory.id(), null, null, tenant, user, agent, "Intake"));

        var repository = new PostgresProjectIntakeRepository(jdbc);
        String jobId = UUID.randomUUID().toString();
        String hash = "sha256:" + "b".repeat(64);
        repository.insert(new ProjectIntakeJob(
                jobId, tenant, user, project, directory.id(), source.id(), conversation.id(),
                agent, "Understand repository", hash, "sha256:" + "c".repeat(64),
                ProjectIntakeState.PENDING, 0, null, null, 0, null, null, null, null, null,
                null, null, null, null, null, null, null, null, null, null, 1, NOW, null,
                NOW, null, null));

        var claimed = repository.claim(
                "worker", UUID.randomUUID().toString(), NOW, NOW.plusSeconds(300), 3)
                .orElseThrow();
        assertThat(claimed.id()).isEqualTo(jobId);
        assertThat(claimed.fencingToken()).isEqualTo(1);
        assertThat(new PostgresProjectIntakeRepository(
                new JdbcTemplate(dataSource())).findById(jobId)).isPresent();
        assertThat(repository.findByDirectory(directory.id(), user, 0, 20))
                .extracting(ProjectIntakeJob::id).containsExactly(jobId);
        assertThat(repository.countByDirectory(directory.id(), user)).isEqualTo(1L);

        String rootTask = UUID.randomUUID().toString();
        String childTask = UUID.randomUUID().toString();
        String plan = UUID.randomUUID().toString();
        String planStep = UUID.randomUUID().toString();
        jdbc.update("""
                INSERT INTO platform_tasks(id,project_id,tenant_id,owner_user_id,
                    parent_task_id,title,goal,description,
                    constraints_json,acceptance_criteria_json,state,created_at,updated_at)
                VALUES(CAST(? AS UUID),CAST(? AS UUID),?,?,NULL,'Root','Root',NULL,'[]'::jsonb,
                    '[]'::jsonb,'PENDING',?,?),
                  (CAST(? AS UUID),CAST(? AS UUID),?,?,CAST(? AS UUID),'Child','Child',NULL,
                    '[]'::jsonb,'["tests pass"]'::jsonb,'PENDING',?,?)
                """, rootTask, project, tenant, user, Timestamp.from(NOW), Timestamp.from(NOW),
                childTask, project, tenant, user, rootTask,
                Timestamp.from(NOW), Timestamp.from(NOW));
        jdbc.update("""
                INSERT INTO platform_task_plans(id,project_id,root_task_id,version_number,status,
                    generated_by_agent_id,created_by,approved_by,approved_at,created_at,updated_at)
                VALUES(CAST(? AS UUID),CAST(? AS UUID),CAST(? AS UUID),1,'ACTIVE',?,
                    ?,?,?,?,?)
                """, plan, project, rootTask, agent, user, user, Timestamp.from(NOW),
                Timestamp.from(NOW), Timestamp.from(NOW));
        jdbc.update("""
                INSERT INTO platform_plan_steps(id,task_plan_id,project_id,step_key,sequence_number,
                    child_task_id,required_capability,preferred_agent_id,expected_output,
                    acceptance_criteria_json,approval_required,state,created_at,updated_at)
                VALUES(CAST(? AS UUID),CAST(? AS UUID),CAST(? AS UUID),'backend',0,
                    CAST(? AS UUID),NULL,?,'backend','["tests pass"]'::jsonb,FALSE,'PENDING',?,?)
                """, planStep, plan, project, childTask, agent, Timestamp.from(NOW), Timestamp.from(NOW));
        var codingJobs = new PostgresProjectCodingJobRepository(jdbc);
        String codingJob = UUID.randomUUID().toString();
        codingJobs.insert(new ProjectCodingJob(codingJob, tenant, user, project, directory.id(),
                conversation.id(), source.id(), rootTask, childTask, plan, null, planStep, agent, configurationHash,
                configurationHash, "main", "sha256:" + "d".repeat(64), "sha256:" + "e".repeat(64),
                ProjectCodingJobState.PENDING, null, null, null, 0, 0, null, null, null, null,
                null, null, null, null, 0, null, null, 0, null, 1, NOW, null, NOW, null, agent));
        var codingClaim = codingJobs.claim(
                "coding-worker", UUID.randomUUID().toString(), NOW, NOW.plusSeconds(300), 3)
                .orElseThrow();
        assertThat(codingClaim.state()).isEqualTo(ProjectCodingJobState.RUNNING);
        assertThat(codingClaim.fencingToken()).isEqualTo(1);
        var codingService = new ProjectCodingJobApplicationService(
                codingJobs, ids, () -> NOW, new com.fasterxml.jackson.databind.ObjectMapper());
        codingService.initializeContext(new ProjectCodingJobApplicationApi.ClaimCommand(
                codingJob, "coding-worker", codingClaim.claimToken(), codingClaim.fencingToken()),
                "{\"messages\":[]}");
        assertThat(jdbc.queryForObject("SELECT context_json IS NOT NULL "
                + "FROM platform_project_coding_jobs WHERE id=CAST(? AS UUID)",
                Boolean.class, codingJob)).isTrue();
        assertThat(new PostgresProjectCodingJobRepository(
                new JdbcTemplate(dataSource())).findById(codingJob)).isPresent();

        String workspace = UUID.randomUUID().toString();
        String sourceRun = UUID.randomUUID().toString();
        String recoverySnapshot = UUID.randomUUID().toString();
        String targetCodingJob = UUID.randomUUID().toString();
        jdbc.update("""
                INSERT INTO platform_workspaces(
                    id,tenant_id,project_id,project_directory_id,task_id,source_repository_id,
                    isolation_key,mode,worktree_key,base_ref,branch_name,worktree_ref,head_commit,
                    writable,state,revision,created_by,created_at,updated_at)
                VALUES(CAST(? AS UUID),?,CAST(? AS UUID),CAST(? AS UUID),CAST(? AS UUID),
                    CAST(? AS UUID),?,'MANAGED_GIT',gen_random_uuid(),'main','handoff-branch',
                    ?,repeat('a',40),TRUE,'READY',1,?,?,?)
                """, workspace, tenant, project, directory.id(), childTask, source.id(),
                "plan-step:" + planStep, "managed:" + workspace, user,
                Timestamp.from(NOW), Timestamp.from(NOW));
        jdbc.update("""
                INSERT INTO platform_agent_runs(
                    id,agent_id,tenant_id,owner_id,conversation_id,
                    project_id,task_id,project_uuid,project_directory_id,workspace_id,task_uuid,
                    task_plan_id,plan_step_id,execution_cursor,revision,state,created_at,updated_at)
                VALUES(?,?,?,?,?, ?,?,CAST(? AS UUID),CAST(? AS UUID),
                    CAST(? AS UUID),CAST(? AS UUID),CAST(? AS UUID),CAST(? AS UUID),
                    '{\"phase\":\"coding\",\"stepId\":null,\"checkpointId\":null,\"checkpointSequence\":0}',
                    1,'CANCELLED',?,?)
                """, sourceRun, agent, tenant, user, conversation.id(), project, childTask,
                project, directory.id(), workspace, childTask, plan, planStep,
                Timestamp.from(NOW), Timestamp.from(NOW));
        jdbc.update("""
                INSERT INTO platform_agent_run_configuration_snapshots(
                    id,run_id,tenant_id,owner_id,agent_id,snapshot_state,captured_at)
                VALUES(?,?,?,?,?,'LEGACY_UNSNAPSHOTTED',?)
                """, sourceRun, sourceRun, tenant, user, agent, Timestamp.from(NOW));
        jdbc.update("""
                INSERT INTO platform_project_execution_context_snapshots(
                    id,tenant_id,owner_id,project_id,project_directory_id,conversation_id,
                    task_id,task_plan_id,plan_step_id,agent_run_id,run_configuration_snapshot_id,workspace_id,
                    idempotency_hash,input_hash,snapshot_hash,payload_json,created_at)
                VALUES(CAST(? AS UUID),?,?,CAST(? AS UUID),CAST(? AS UUID),?,CAST(? AS UUID),
                    CAST(? AS UUID),CAST(? AS UUID),?,?,CAST(? AS UUID),
                    'sha256:'||repeat('1',64),'sha256:'||repeat('2',64),
                    'sha256:'||encode(sha256(convert_to('{}','UTF8')),'hex'),'{}',?)
                """, recoverySnapshot, tenant, user, project, directory.id(), conversation.id(),
                childTask, plan, planStep, sourceRun, sourceRun, workspace, Timestamp.from(NOW));
        jdbc.update("""
                UPDATE platform_project_coding_jobs SET state='HANDED_OFF',workspace_id=CAST(? AS UUID),
                    coding_run_id=?,claim_owner=NULL,claim_token=NULL,lease_until=NULL,
                    revision=revision+1,updated_at=?,completed_at=? WHERE id=CAST(? AS UUID)
                """, workspace, sourceRun, Timestamp.from(NOW), Timestamp.from(NOW), codingJob);
        codingJobs.insert(new ProjectCodingJob(targetCodingJob, tenant, user, project, directory.id(),
                conversation.id(), source.id(), rootTask, childTask, plan, null, planStep, agent, configurationHash,
                configurationHash, "main", "sha256:" + "f".repeat(64), "sha256:" + "0".repeat(64),
                ProjectCodingJobState.PENDING, null, null, null, 0, 0, null, null, null, null,
                null, null, null, null, 0, null, null, 0, null, 1, NOW, null, NOW, null, agent));
        var handoffService = new ProjectRunHandoffApplicationService(
                new PostgresProjectRunHandoffRepository(jdbc), ids, () -> NOW);
        var handoff = handoffService.create(new ProjectRunHandoffApplicationApi.CreateCommand(
                UUID.randomUUID().toString(), tenant, user, project, directory.id(), source.id(),
                rootTask, childTask, plan, planStep, "main", codingJob, sourceRun, workspace,
                recoverySnapshot, "sha256:" + jdbc.queryForObject(
                        "SELECT encode(sha256(convert_to('{}','UTF8')),'hex')", String.class),
                conversation.id(), agent, agent, targetCodingJob, "handoff-pg-001"));
        assertThat(handoff.state()).isEqualTo(ProjectRunHandoffState.PENDING);
        handoffService.attachTargetRun(targetCodingJob, sourceRun);
        jdbc.update("""
                UPDATE platform_project_coding_jobs SET state='COMPLETED',updated_at=?,completed_at=?
                 WHERE id=CAST(? AS UUID)
                """, Timestamp.from(NOW), Timestamp.from(NOW), targetCodingJob);
        var handoffClaim = handoffService.claimFinalization("handoff-worker", 60, 3).orElseThrow();
        assertThat(handoffClaim.fencingToken()).isEqualTo(1);
        assertThat(handoffService.claimFinalization("other-worker", 60, 3)).isEmpty();
        assertThat(new PostgresProjectRunHandoffRepository(new JdbcTemplate(dataSource()))
                .findById(handoff.id())).isPresent();
    }

    @Test
    void v1045UpgradesV1044WithoutInventingIntakeJobs() {
        String schema = "project_intake_upgrade";
        Flyway.configure().dataSource(schemaDataSource(schema))
                .locations("classpath:db/platform-server", "classpath:db/platform-runtime")
                .target(MigrationVersion.fromVersion("1044")).load().migrate();
        JdbcTemplate jdbc = new JdbcTemplate(schemaDataSource(schema));
        assertThat(jdbc.queryForObject(
                "SELECT to_regclass('platform_project_intake_jobs') IS NULL", Boolean.class))
                .isTrue();

        Flyway.configure().dataSource(schemaDataSource(schema))
                .locations("classpath:db/platform-server", "classpath:db/platform-runtime")
                .target(MigrationVersion.fromVersion("1045")).load().migrate();

        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM platform_project_intake_jobs", Long.class)).isZero();
        assertThat(jdbc.queryForObject("""
                SELECT version FROM flyway_schema_history
                 WHERE success ORDER BY installed_rank DESC LIMIT 1
                """, String.class)).isEqualTo("1045");
    }

    @Test
    void v1046UpgradesV1045WithoutInventingCodingJobs() {
        String schema = "project_coding_upgrade";
        Flyway.configure().dataSource(schemaDataSource(schema))
                .locations("classpath:db/platform-server", "classpath:db/platform-runtime")
                .target(MigrationVersion.fromVersion("1045")).load().migrate();
        JdbcTemplate jdbc = new JdbcTemplate(schemaDataSource(schema));
        assertThat(jdbc.queryForObject(
                "SELECT to_regclass('platform_project_coding_jobs') IS NULL", Boolean.class))
                .isTrue();

        Flyway.configure().dataSource(schemaDataSource(schema))
                .locations("classpath:db/platform-server", "classpath:db/platform-runtime")
                .target(MigrationVersion.fromVersion("1046")).load().migrate();

        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM platform_project_coding_jobs", Long.class)).isZero();
        assertThat(jdbc.queryForObject("""
                SELECT version FROM flyway_schema_history
                 WHERE success ORDER BY installed_rank DESC LIMIT 1
                """, String.class)).isEqualTo("1046");
    }

    @Test
    void v1047UpgradesV1046WithoutInventingProjectHandoffs() {
        String schema = "project_handoff_upgrade";
        Flyway.configure().dataSource(schemaDataSource(schema))
                .locations("classpath:db/platform-server", "classpath:db/platform-runtime")
                .target(MigrationVersion.fromVersion("1046")).load().migrate();
        JdbcTemplate jdbc = new JdbcTemplate(schemaDataSource(schema));
        assertThat(jdbc.queryForObject(
                "SELECT to_regclass('platform_project_run_handoffs') IS NULL", Boolean.class))
                .isTrue();

        Flyway.configure().dataSource(schemaDataSource(schema))
                .locations("classpath:db/platform-server", "classpath:db/platform-runtime")
                .target(MigrationVersion.fromVersion("1047")).load().migrate();

        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM platform_project_run_handoffs", Long.class)).isZero();
        assertThat(jdbc.queryForObject("""
                SELECT version FROM flyway_schema_history
                 WHERE success ORDER BY installed_rank DESC LIMIT 1
                """, String.class)).isEqualTo("1047");
    }

    @Test
    void v1043BackfillsDefaultAndSourceDirectoriesWithoutLosingHierarchy() {
        String schema = "project_directory_upgrade";
        Flyway.configure().dataSource(schemaDataSource(schema))
                .locations("classpath:db/platform-server", "classpath:db/platform-runtime")
                .target(MigrationVersion.fromVersion("1042")).load().migrate();
        JdbcTemplate jdbc = new JdbcTemplate(schemaDataSource(schema));
        String tenant = UUID.randomUUID().toString();
        String user = UUID.randomUUID().toString();
        String project = UUID.randomUUID().toString();
        String task = UUID.randomUUID().toString();
        String source = UUID.randomUUID().toString();
        String workspace = UUID.randomUUID().toString();
        Timestamp now = Timestamp.from(NOW);
        jdbc.update("INSERT INTO platform_tenants(id,name,slug,status,created_at,updated_at) "
                + "VALUES(?, 'Upgrade', ?, 'ACTIVE', ?, ?)", tenant, "upgrade-" + suffix(), now, now);
        jdbc.update("INSERT INTO platform_users(id,tenant_id,external_id,display_name,created_at,updated_at) "
                + "VALUES(?, ?, ?, 'Upgrade', ?, ?)", user, tenant, "upgrade-" + suffix(), now, now);
        jdbc.update("INSERT INTO platform_tenant_memberships(tenant_id,user_id,tenant_role,status,joined_at,updated_at) "
                + "VALUES(?, ?, 'OWNER', 'ACTIVE', ?, ?)", tenant, user, now, now);
        jdbc.update("INSERT INTO platform_projects(id,tenant_id,owner_id,name,status,created_at,updated_at) "
                + "VALUES(CAST(? AS UUID), ?, ?, 'Upgrade Project', 'ACTIVE', ?, ?)",
                project, tenant, user, now, now);
        jdbc.update("INSERT INTO platform_project_memberships(id,project_id,user_id,role,created_at) "
                + "VALUES(gen_random_uuid(), CAST(? AS UUID), ?, 'OWNER', ?)", project, user, now);
        jdbc.update("INSERT INTO platform_tasks(id,project_id,parent_task_id,title,goal,description,constraints_json,"
                + "acceptance_criteria_json,state,created_at,updated_at) VALUES(CAST(? AS UUID),"
                + "CAST(? AS UUID),NULL,'Task','Goal',NULL,'[]'::jsonb,'[]'::jsonb,'PENDING',?,?)",
                task, project, now, now);
        jdbc.update("""
                INSERT INTO platform_source_repositories(
                    id,project_id,tenant_id,provider_repository_id,display_name,remote_url,
                    default_branch,repository_type,state,visibility,created_by,created_at,updated_at)
                VALUES(CAST(? AS UUID),CAST(? AS UUID),?,'upgrade-source','Upgrade Source',
                    'https://example.com/upgrade.git','main','GENERIC','READY','PRIVATE',?,?,?)
                """, source, project, tenant, user, now, now);
        jdbc.update("""
                INSERT INTO platform_workspaces(
                    id,tenant_id,project_id,task_id,source_repository_id,bridge_id,mode,
                    worktree_key,base_ref,branch_name,worktree_ref,head_commit,writable,state,
                    failure_reason,revision,created_by,created_at,updated_at,isolation_key)
                VALUES(CAST(? AS UUID),?,CAST(? AS UUID),CAST(? AS UUID),CAST(? AS UUID),NULL,
                    'MANAGED_GIT',gen_random_uuid(),'main','spaceagent/upgrade','managed:upgrade',?,
                    TRUE,'READY',NULL,1,?,?,?,'primary')
                """, workspace, tenant, project, task, source, "b".repeat(40), user, now, now);
        String conversation = UUID.randomUUID().toString();
        jdbc.update("""
                INSERT INTO platform_conversations(
                    id,project_id,task_id,tenant_id,user_id,agent_id,title,status,
                    created_at,updated_at,project_uuid,task_uuid,active_task_id)
                VALUES(?,?,NULL,?,?,NULL,'Upgrade Conversation','ACTIVE',?,?,CAST(? AS UUID),NULL,NULL)
                """, conversation, project, tenant, user, now, now, project);

        Flyway.configure().dataSource(schemaDataSource(schema))
                .locations("classpath:db/platform-server", "classpath:db/platform-runtime")
                .target(MigrationVersion.fromVersion("1043")).load().migrate();

        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM platform_project_directories
                 WHERE project_id = CAST(? AS UUID)
                """, Long.class, project)).isEqualTo(2L);
        assertThat(jdbc.queryForObject("""
                SELECT directory.is_default
                  FROM platform_conversations conversation
                  JOIN platform_project_directories directory
                    ON directory.id = conversation.project_directory_id
                 WHERE conversation.id = ?
                """, Boolean.class, conversation)).isTrue();
        assertThat(jdbc.queryForObject("""
                SELECT directory.source_repository_id = workspace.source_repository_id
                  FROM platform_workspaces workspace
                  JOIN platform_project_directories directory
                    ON directory.id = workspace.project_directory_id
                 WHERE workspace.id = CAST(? AS UUID)
                """, Boolean.class, workspace)).isTrue();
    }

    private static SourceRepository source(
            String projectId, String tenantId, String userId, String providerId) {
        return new SourceRepository(
                UUID.randomUUID().toString(), projectId, tenantId, null, null, null,
                providerId, "Repository", "https://example.com/repository.git", null,
                "main", SourceRepositoryType.GIT, SourceRepositoryState.READY,
                SourceRepositoryVisibility.PRIVATE, userId, NOW, NOW);
    }

    private static DriverManagerDataSource dataSource() {
        return new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    }

    private static DriverManagerDataSource schemaDataSource(String schema) {
        return com.spaceagent.platform.support.PostgresSchemaDataSource.forSchema(POSTGRES, schema);
    }

    private static String suffix() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    private static final Instant NOW = Instant.parse("2026-09-05T00:00:00Z");
}

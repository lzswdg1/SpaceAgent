package com.spaceagent.platform.architecture;

import com.spaceagent.platform.ModuleRegistry;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * Enforces the M2 platform-server module boundaries without introducing a
 * heavyweight architecture dependency.
 *
 * <p>The tests scan source files under the module's {@code src/main/java} tree. They are
 * intentionally small and dependency-free so they run in the same JVM as the rest of the
 * module test suite.
 */
class PlatformModuleArchitectureTest {

    private static final Path PLATFORM_MAIN_JAVA = locatePlatformMainJava();
    private static final Path REPOSITORY_ROOT = Files.isDirectory(Path.of("services"))
            ? Path.of(".") : Path.of("../..");

    private static final Set<String> MODULES = Set.copyOf(ModuleRegistry.MODULE_NAMES);

    private static final Set<String> APPLICATION_MODULES = Set.copyOf(
            ModuleRegistry.BUSINESS_MODULE_NAMES
    );

    private static final Set<String> RUNTIME_DEPENDENT_MODULES = Set.of(
            "project", "conversation", "memory", "knowledge"
    );

    private static final Set<String> INFERENCE_FORBIDDEN_DEPENDENCIES = MODULES.stream()
            .filter(module -> !"inference".equals(module) && !"shared".equals(module))
            .collect(java.util.stream.Collectors.toUnmodifiableSet());

    @Test
    void everyLogicalModuleDeclaresRequiredLayerBoundaries() {
        List<String> missing = MODULES.stream()
                .flatMap(module -> requiredBoundaries(module).stream()
                        .map(boundary -> module + "/" + boundary))
                .filter(relative -> !Files.isRegularFile(packageInfo(relative)))
                .sorted()
                .toList();

        assertTrue(missing.isEmpty(),
                "Each platform-server logical module must declare its required package "
                        + "boundaries (api/domain/infrastructure for all modules plus "
                        + "application for business modules); missing: " + missing);
    }

    @Test
    void platformSharedModuleDoesNotDependOnOtherPlatformModules() throws IOException {
        List<String> violations = javaFiles(PLATFORM_MAIN_JAVA.resolve("com/spaceagent/platform/shared"))
                .flatMap(PlatformModuleArchitectureTest::imports)
                .filter(line -> line.contains("com.spaceagent.platform.")
                        && !line.contains("com.spaceagent.platform.shared."))
                .sorted()
                .toList();

        assertTrue(violations.isEmpty(),
                "platform.shared must not depend on other platform modules; found: " + violations);
    }

    @Test
    void domainPackagesRemainFrameworkIndependent() throws IOException {
        List<String> violations = javaFiles(PLATFORM_MAIN_JAVA)
                .filter(PlatformModuleArchitectureTest::isInDomainPackage)
                .flatMap(PlatformModuleArchitectureTest::imports)
                .filter(PlatformModuleArchitectureTest::isFrameworkImport)
                .sorted()
                .toList();

        assertTrue(violations.isEmpty(),
                "platform domain packages must not import Spring/MyBatis/LangChain4j/Temporal "
                        + "or provider SDKs; found: " + violations);
    }

    @Test
    void domainStateModulesDoNotDependOnRuntime() throws IOException {
        List<String> violations = javaFilesInModules(RUNTIME_DEPENDENT_MODULES)
                .flatMap(PlatformModuleArchitectureTest::imports)
                .filter(line -> line.startsWith("import com.spaceagent.platform.runtime."))
                .sorted()
                .toList();

        assertTrue(violations.isEmpty(),
                "project/conversation/memory/knowledge must not depend on runtime; found: "
                        + violations);
    }

    @Test
    void inferenceDoesNotDependOnOtherBusinessModules() throws IOException {
        List<String> violations = javaFilesInModules(Set.of("inference"))
                .flatMap(PlatformModuleArchitectureTest::imports)
                .filter(line -> INFERENCE_FORBIDDEN_DEPENDENCIES.stream()
                        .anyMatch(module -> line.startsWith("import com.spaceagent.platform." + module + ".")))
                .sorted()
                .toList();

        assertTrue(violations.isEmpty(),
                "inference must not depend on other business modules; found: " + violations);
    }

    @Test
    void agentDefinitionDoesNotOwnRuntimeOrWorkspaceState() throws IOException {
        Path definition = PLATFORM_MAIN_JAVA.resolve(
                "com/spaceagent/platform/agent/domain/AgentDefinition.java");
        String source = Files.readString(definition);
        List<String> forbiddenOwnership = List.of(
                "taskId", "projectId", "workspaceId", "conversationId",
                "agentRunId", "checkpointId").stream()
                .filter(source::contains)
                .toList();

        assertTrue(forbiddenOwnership.isEmpty(),
                "AgentDefinition must not own task/project/workspace/conversation/runtime state; found: "
                        + forbiddenOwnership);
    }

    @Test
    void crossModulePersistenceAccessIsForbidden() throws IOException {
        List<String> violations = javaFiles(PLATFORM_MAIN_JAVA)
                .flatMap(file -> {
                    String ownerModule = owningModule(file);
                    return imports(file)
                            .filter(line -> isCrossModulePersistenceImport(line, ownerModule));
                })
                .sorted()
                .toList();

        assertTrue(violations.isEmpty(),
                "No module may import another module's infrastructure persistence package; found: "
                + violations);
    }

    @Test
    void httpControllersDependOnApplicationApisNotRepositories() throws IOException {
        List<String> violations = javaFiles(PLATFORM_MAIN_JAVA.resolve(
                        "com/spaceagent/platform/integration/infrastructure/http"))
                .flatMap(PlatformModuleArchitectureTest::imports)
                .filter(line -> line.contains(".infrastructure.persistence")
                        || (line.contains(".domain.") && line.contains("Repository")))
                .sorted()
                .toList();

        assertTrue(violations.isEmpty(),
                "Platform HTTP controllers must be adapters over public Application APIs and "
                        + "must not import repositories/persistence implementations; found: " + violations);
    }

    @Test
    void chatHttpAdapterDelegatesOnlyToRuntimeFacade() throws IOException {
        Path controller = PLATFORM_MAIN_JAVA.resolve(
                "com/spaceagent/platform/integration/infrastructure/http/PlatformChatHttpController.java");
        List<String> violations = imports(controller)
                .filter(line -> line.startsWith("import com.spaceagent.platform.conversation.")
                        || line.startsWith("import com.spaceagent.platform.inference.")
                        || line.startsWith("import com.spaceagent.platform.tooling."))
                .toList();

        assertTrue(violations.isEmpty(),
                "PlatformChatHttpController must delegate execution only to Runtime; found: " + violations);
    }

    @Test
    void knowledgeDoesNotOwnAgentConversationOrRuntime() throws IOException {
        List<String> violations = javaFilesInModules(Set.of("knowledge"))
                .flatMap(PlatformModuleArchitectureTest::imports)
                .filter(line -> line.startsWith("import com.spaceagent.platform.agent.")
                        || line.startsWith("import com.spaceagent.platform.conversation.")
                        || line.startsWith("import com.spaceagent.platform.runtime."))
                .toList();

        assertTrue(violations.isEmpty(),
                "Knowledge must not depend on Agent, Conversation, or Runtime; found: " + violations);
    }

    @Test
    void observabilityReadsOnlyItsDisposableProjectionViews() throws IOException {
        List<String> sourceTables = List.of(
                "platform_agent_runs", "platform_model_call_ledger",
                "platform_conversations", "platform_messages",
                "platform_agent_definitions", "platform_tenants");
        List<String> violations = javaFiles(PLATFORM_MAIN_JAVA.resolve(
                        "com/spaceagent/platform/observability/infrastructure/persistence"))
                .flatMap(file -> {
                    try {
                        String source = Files.readString(file);
                        return sourceTables.stream()
                                .filter(source::contains)
                                .map(table -> file.getFileName() + ":" + table);
                    } catch (IOException error) {
                        throw new IllegalStateException(error);
                    }
                })
                .sorted()
                .toList();

        assertTrue(violations.isEmpty(),
                "Observability Java persistence must query only observability-owned views; found: "
                        + violations);
    }

    @Test
    void observabilityPersistenceQueriesOnlyOwnedReadViews() throws IOException {
        Path persistence = PLATFORM_MAIN_JAVA.resolve(
                "com/spaceagent/platform/observability/infrastructure/persistence");
        List<String> violations = javaFiles(persistence)
                .flatMap(file -> {
                    try {
                        String source = Files.readString(file);
                        return java.util.regex.Pattern.compile("platform_[a-z_]+")
                                .matcher(source).results()
                                .map(java.util.regex.MatchResult::group)
                                .filter(name -> !name.startsWith("platform_observability_"))
                                .filter(name -> !name.startsWith("platform_trace_"))
                                .map(name -> file.getFileName() + ":" + name);
                    } catch (IOException error) {
                        throw new IllegalStateException(error);
                    }
                }).distinct().sorted().toList();
        assertTrue(violations.isEmpty(),
                "Observability/Tracing Java persistence must query only owned disposable views; "
                        + "found: " + violations);
    }

    @Test
    void governanceOwnsOnlyPolicyAndApprovalState() throws IOException {
        Path governance = PLATFORM_MAIN_JAVA.resolve("com/spaceagent/platform/governance");
        List<String> dependencyViolations = javaFiles(governance)
                .flatMap(PlatformModuleArchitectureTest::imports)
                .filter(line -> line.startsWith("import com.spaceagent.platform."))
                .filter(line -> !line.startsWith("import com.spaceagent.platform.governance."))
                .filter(line -> !line.startsWith("import com.spaceagent.platform.identity.api."))
                .filter(line -> !line.startsWith("import com.spaceagent.platform.identity.domain."))
                .sorted()
                .toList();
        assertTrue(dependencyViolations.isEmpty(),
                "Governance may authorize membership through Identity public contracts but must "
                        + "not own Runtime, Tool, Project, Automation, or other business state; found: "
                        + dependencyViolations);

        List<String> ownedTables = List.of(
                "platform_governance_policies", "platform_approval_requests",
                "platform_governance_business_attempts", "platform_governance_business_outcomes");
        List<String> tableViolations = javaFiles(governance.resolve("infrastructure/persistence"))
                .flatMap(file -> {
                    try {
                        String source = Files.readString(file);
                        return java.util.regex.Pattern.compile("platform_[a-z_]+")
                                .matcher(source).results()
                                .map(java.util.regex.MatchResult::group)
                                .filter(table -> !ownedTables.contains(table))
                                .map(table -> file.getFileName() + ":" + table);
                    } catch (IOException error) {
                        throw new IllegalStateException(error);
                    }
                })
                .distinct().sorted().toList();
        assertTrue(tableViolations.isEmpty(),
                "Governance persistence must access only its policy, approval and ADR-085 business evidence tables; found: "
                        + tableViolations);
    }

    @Test
    void automationUsesPublicApisAndOwnsOnlyScheduleExecutionTables() throws IOException {
        Path automation = PLATFORM_MAIN_JAVA.resolve("com/spaceagent/platform/automation");
        List<String> dependencyViolations = javaFiles(automation)
                .flatMap(PlatformModuleArchitectureTest::imports)
                .filter(line -> line.startsWith("import com.spaceagent.platform."))
                .filter(line -> !line.startsWith("import com.spaceagent.platform.automation."))
                .filter(line -> !line.contains(".api."))
                .filter(line -> !line.startsWith("import com.spaceagent.platform.identity.domain."))
                .filter(line -> !line.startsWith("import com.spaceagent.platform.agent.domain."))
                .filter(line -> !line.startsWith("import com.spaceagent.platform.governance.domain."))
                .filter(line -> !line.startsWith("import com.spaceagent.platform.runtime.domain."))
                .sorted().toList();
        assertTrue(dependencyViolations.isEmpty(),
                "Automation must use public Application APIs and never another module's "
                        + "application/infrastructure/repository implementation; found: "
                        + dependencyViolations);

        List<String> ownedTables = List.of(
                "platform_automation_schedules",
                "platform_automation_executions",
                "platform_automation_triggers",
                "platform_automation_trigger_subscriptions",
                "platform_automation_trigger_occurrences",
                "platform_automation_trigger_deliveries",
                "platform_automation_dispatch_plans",
                "platform_automation_trigger_dead_letters");
        List<String> tableViolations = javaFiles(automation.resolve("infrastructure/persistence"))
                .flatMap(file -> {
                    try {
                        String source = Files.readString(file);
                        return java.util.regex.Pattern.compile("platform_[a-z_]+")
                                .matcher(source).results()
                                .map(java.util.regex.MatchResult::group)
                                .filter(table -> !ownedTables.contains(table))
                                .map(table -> file.getFileName() + ":" + table);
                    } catch (IOException error) {
                        throw new IllegalStateException(error);
                    }
                }).distinct().sorted().toList();
        assertTrue(tableViolations.isEmpty(),
                "Automation persistence must access only its owned Schedule/Execution/Trigger tables; found: "
                        + tableViolations);

        String worker = Files.readString(automation.resolve(
                "application/AutomationSchedulerWorker.java"));
        assertTrue(!worker.contains("Redis") && !worker.contains("Bull")
                        && !worker.contains("ConcurrentHashMap"),
                "Automation wake-up worker must not own queue/schedule state in process or Redis");
    }

    @Test
    void organizationInvitationDomainNeverOwnsPlaintextTokens() throws IOException {
        Path identityDomain = PLATFORM_MAIN_JAVA.resolve(
                "com/spaceagent/platform/identity/domain");
        String invitation = Files.readString(identityDomain.resolve("OrganizationInvitation.java"));
        String repository = Files.readString(
                identityDomain.resolve("OrganizationInvitationRepository.java"));
        String preview = Files.readString(PLATFORM_MAIN_JAVA.resolve(
                "com/spaceagent/platform/identity/api/OrganizationInvitationPreviewView.java"));

        assertTrue(invitation.contains("String tokenHash") && !invitation.contains("String token,"),
                "OrganizationInvitation may persist only the token digest");
        assertTrue(!repository.contains("rawToken") && !repository.contains("String token,"),
                "Invitation persistence port must never accept a plaintext token");
        assertTrue(preview.contains("String maskedEmail") && !preview.contains("String email,"),
                "Public invitation preview must not disclose the invited identity");
    }

    @Test
    void organizationCleanupControlPlaneOwnsNoForeignPurgeOrScheduler() throws IOException {
        Path cleanupPersistence = PLATFORM_MAIN_JAVA.resolve(
                "com/spaceagent/platform/identity/infrastructure/persistence/"
                        + "PostgresOrganizationCleanupRepository.java");
        String persistence = Files.readString(cleanupPersistence);
        List<String> tableViolations = java.util.regex.Pattern.compile("platform_[a-z_]+")
                .matcher(persistence).results()
                .map(java.util.regex.MatchResult::group)
                .filter(table -> !table.equals("platform_organization_cleanup_jobs"))
                .filter(table -> !table.equals("platform_organization_cleanup_steps"))
                .distinct().sorted().toList();
        assertTrue(tableViolations.isEmpty(),
                "Identity cleanup persistence must own only Job/Step tables; found: "
                        + tableViolations);

        Path coordinator = PLATFORM_MAIN_JAVA.resolve(
                "com/spaceagent/platform/integration/application/"
                        + "OrganizationCleanupCoordinator.java");
        String source = Files.readString(coordinator);
        assertTrue(!source.contains("@Scheduled") && !source.contains("JdbcTemplate")
                        && !source.contains(".infrastructure."),
                "PR2B coordinator must not schedule or execute purge persistence before PR2C");
        assertTrue(source.contains("OrganizationCleanupApplicationApi"),
                "Integration coordinator must use the Identity public control-plane API");

        Path executionCoordinator = PLATFORM_MAIN_JAVA.resolve(
                "com/spaceagent/platform/integration/application/"
                        + "OrganizationCleanupExecutionCoordinator.java");
        List<String> executionViolations = imports(executionCoordinator)
                .filter(line -> line.startsWith("import com.spaceagent.platform."))
                .filter(line -> !line.contains(".api."))
                .filter(line -> !line.startsWith("import com.spaceagent.platform.integration."))
                .filter(line -> !line.startsWith("import com.spaceagent.platform.identity.domain."
                ))
                .toList();
        assertTrue(executionViolations.isEmpty(),
                "Cleanup execution coordinator must call only public module APIs; found: "
                        + executionViolations);
        String scheduler = Files.readString(PLATFORM_MAIN_JAVA.resolve(
                "com/spaceagent/platform/integration/application/"
                        + "OrganizationCleanupSchedulerWorker.java"));
        assertTrue(scheduler.contains("@Scheduled")
                        && scheduler.contains("havingValue = \"postgres\"")
                        && !scheduler.contains("JdbcTemplate"),
                "Cleanup wake-up worker must be PostgreSQL-only and persistence-free");

        Map<String, Set<String>> cleanupTables = Map.of(
                "memory", Set.of("platform_memory_candidates", "platform_consolidated_memories"),
                "identity", Set.of("platform_refresh_tokens", "platform_organization_invitations", "platform_tenant_memberships"),
                "agent", Set.of("platform_agent_definitions", "platform_agent_api_keys", "platform_agent_knowledge_bindings", "platform_agent_current_configurations", "platform_agent_mcp_bindings"),
                "inference", Set.of("platform_embedding_calls", "platform_inference_budget_reservations", "platform_inference_budget_periods", "platform_inference_budget_policies", "platform_model_pool_members", "platform_model_pools", "platform_provider_models", "platform_model_providers"),
                "artifact", Set.of("platform_artifacts", "platform_artifact_objects",
                        "platform_artifact_object_staging", "platform_artifact_object_references",
                        "platform_artifact_object_legal_holds", "platform_artifact_object_deletions"),
                "conversation", Set.of("platform_conversation_context_snapshots", "platform_conversations"),
                "automation", Set.of("platform_automation_executions", "platform_automation_schedules",
                        "platform_automation_triggers", "platform_automation_trigger_subscriptions",
                        "platform_automation_trigger_occurrences", "platform_automation_trigger_deliveries",
                        "platform_automation_dispatch_plans",
                        "platform_automation_trigger_dead_letters"),
                "governance", Set.of("platform_approval_requests", "platform_governance_policies"),
                "runtime", Set.of("platform_run_worker_leases", "platform_agent_runs", "platform_runtime_continuations", "platform_agent_reviews", "platform_agent_delegations", "platform_project_coding_jobs", "platform_project_run_handoffs", "platform_project_plan_executions", "platform_project_plan_step_assignments"),
                "tooling", Set.of("platform_mcp_invocation_ledger", "platform_mcp_oauth_states", "platform_mcp_connections", "platform_mcp_installations"));
        for (var owner : cleanupTables.entrySet()) {
            Path service = PLATFORM_MAIN_JAVA.resolve("com/spaceagent/platform/" + owner.getKey()
                    + "/infrastructure/persistence/Postgres"
                    + Character.toUpperCase(owner.getKey().charAt(0))
                    + owner.getKey().substring(1) + "CleanupService.java");
            List<String> violations = java.util.regex.Pattern.compile("platform_[a-z_]+")
                    .matcher(Files.readString(service)).results()
                    .map(java.util.regex.MatchResult::group)
                    .filter(table -> !owner.getValue().contains(table))
                    .distinct().sorted().toList();
            assertTrue(violations.isEmpty(), owner.getKey()
                    + " cleanup must access only owner tables; found: " + violations);
        }
    }

    @Test
    void mcpMarketplaceViewsAndHttpNeverExposeAuthPayloads() throws IOException {
        String api = Files.readString(PLATFORM_MAIN_JAVA.resolve(
                "com/spaceagent/platform/tooling/api/McpMarketplaceApplicationApi.java"));
        String http = Files.readString(PLATFORM_MAIN_JAVA.resolve(
                "com/spaceagent/platform/integration/infrastructure/http/"
                        + "PlatformMcpMarketplaceHttpController.java"));
        assertTrue(api.contains("boolean authConfigured")
                        && !api.contains("encryptedAuthJson"),
                "MCP public views must expose only authConfigured, never auth/ciphertext");
        assertTrue(!http.contains("McpConnectionSecretCipher") && !http.contains("decrypt("),
                "MCP HTTP adapter must never access secret decryption");
        List<String> sdkLeaks = javaFiles(PLATFORM_MAIN_JAVA)
                .filter(file -> !file.toString().replace('\\', '/').contains(
                        "/tooling/infrastructure/"))
                .flatMap(PlatformModuleArchitectureTest::imports)
                .filter(line -> line.startsWith("import io.modelcontextprotocol."))
                .toList();
        assertTrue(sdkLeaks.isEmpty(),
                "Official MCP SDK types must remain inside Tooling infrastructure; found: "
                        + sdkLeaks);
    }

    @Test
    void officialMcpRegistryRemainsReviewedExternalMetadataNotRuntimeAuthority()
            throws IOException {
        String service = Files.readString(PLATFORM_MAIN_JAVA.resolve(
                "com/spaceagent/platform/tooling/application/"
                        + "McpRegistryAdministrationService.java"));
        String worker = Files.readString(PLATFORM_MAIN_JAVA.resolve(
                "com/spaceagent/platform/tooling/application/"
                        + "McpRegistrySynchronizationWorker.java"));
        String gateway = Files.readString(PLATFORM_MAIN_JAVA.resolve(
                "com/spaceagent/platform/tooling/infrastructure/"
                        + "OfficialMcpRegistryHttpGateway.java"));
        String migration = Files.readString(Path.of(
                "src/main/resources/db/platform-server/"
                        + "V1042__official_mcp_registry_sync.sql"));
        assertTrue(service.contains("PENDING_REVIEW")
                        && service.contains("McpRegistryPublication")
                        && service.contains("McpRegistryStatus.ACTIVE")
                        && !service.contains("platform_mcp_")
                        && !service.contains("JdbcTemplate"),
                "Registry application flow must review through domain ports before publication");
        assertTrue(worker.contains("@Scheduled") && !worker.contains("JdbcTemplate")
                        && !worker.contains("HttpClient"),
                "Registry wake-up worker must own neither network nor persistence details");
        assertTrue(gateway.contains("HttpClient.Redirect.NEVER")
                        && gateway.contains("registry.modelcontextprotocol.io")
                        && gateway.contains("[REDACTED]"),
                "Official Registry gateway must be fixed-origin, redirect-free and secret-sanitizing");
        assertTrue(migration.contains("platform_mcp_registry_snapshots")
                        && migration.contains("platform_mcp_registry_candidates")
                        && migration.contains("PENDING_REVIEW")
                        && migration.contains("DEFERRABLE INITIALLY DEFERRED"),
                "V1042 must preserve immutable snapshot/review/publication consistency");
    }

    @Test
    void projectModeUsesDirectoryHierarchyAndImmutableSandboxWorkspaceBinding()
            throws IOException {
        String directoryApi = Files.readString(PLATFORM_MAIN_JAVA.resolve(
                "com/spaceagent/platform/project/api/ProjectDirectoryApplicationApi.java"));
        String conversation = Files.readString(PLATFORM_MAIN_JAVA.resolve(
                "com/spaceagent/platform/conversation/domain/Conversation.java"));
        String workspace = Files.readString(PLATFORM_MAIN_JAVA.resolve(
                "com/spaceagent/platform/project/domain/Workspace.java"));
        String run = Files.readString(PLATFORM_MAIN_JAVA.resolve(
                "com/spaceagent/platform/runtime/domain/AgentRun.java"));
        String coding = Files.readString(PLATFORM_MAIN_JAVA.resolve(
                "com/spaceagent/platform/runtime/application/CodingRuntimeApplicationService.java"));
        String migration = Files.readString(Path.of(
                "src/main/resources/db/platform-server/"
                        + "V1043__project_directory_sandbox_hierarchy.sql"));
        assertTrue(directoryApi.contains("resolveConversationDirectory")
                        && directoryApi.contains("resolveWorkspaceDirectory"),
                "Project must own directory resolution through a public Application API");
        assertTrue(conversation.contains("String projectDirectoryId")
                        && workspace.contains("String projectDirectoryId")
                        && run.contains("String projectDirectoryId")
                        && run.contains("String workspaceId"),
                "Conversation, Workspace and Coding Run must carry the directory hierarchy");
        assertTrue(coding.contains("CODING_PROJECT_DIRECTORY_MISMATCH")
                        && coding.contains("CODING_WORKSPACE_BINDING_MISMATCH")
                        && coding.contains("SandboxComputeApplicationApi"),
                "Coding must reject cross-directory/workspace use and retain Sandbox execution");
        assertTrue(migration.contains("platform_project_directories")
                        && migration.contains("fk_platform_agent_run_workspace_directory")
                        && migration.contains("fk_platform_workspace_project_directory_source"),
                "V1043 must enforce directory/source/workspace/run consistency");
    }

    @Test
    void projectPlanAutoDispatchKeepsProjectLifecycleAuthoritativeAndSingleFlight()
            throws IOException {
        String api = Files.readString(PLATFORM_MAIN_JAVA.resolve(
                "com/spaceagent/platform/integration/api/"
                        + "ProjectPlanExecutionApplicationApi.java"));
        String controller = Files.readString(PLATFORM_MAIN_JAVA.resolve(
                "com/spaceagent/platform/integration/infrastructure/http/"
                        + "PlatformProjectPlanExecutionHttpController.java"));
        String runtimeController = Files.readString(PLATFORM_MAIN_JAVA.resolve(
                "com/spaceagent/platform/integration/infrastructure/http/"
                        + "PlatformProjectPlanExecutionRuntimeHttpController.java"));
        String coordinator = Files.readString(PLATFORM_MAIN_JAVA.resolve(
                "com/spaceagent/platform/integration/application/"
                        + "ProjectCodingCoordinator.java"));
        String plans = Files.readString(PLATFORM_MAIN_JAVA.resolve(
                "com/spaceagent/platform/project/application/"
                        + "TaskPlanApplicationService.java"));
        String runtimeExecutions = Files.readString(PLATFORM_MAIN_JAVA.resolve(
                "com/spaceagent/platform/runtime/application/"
                        + "ProjectPlanExecutionApplicationService.java"));
        assertTrue(api.contains("DispatchCommand") && api.contains("DispatchView")
                        && !api.contains(".infrastructure.") && !api.contains("JdbcTemplate"),
                "Project Plan execution must expose an owner-safe Application API");
        assertTrue(controller.contains("/execute")
                        && controller.contains("ProjectPlanExecutionApplicationApi")
                        && !controller.contains(".infrastructure.persistence")
                        && !controller.contains("JdbcTemplate"),
                "HTTP must enter the plan dispatcher without persistence bypasses");
        assertTrue(runtimeController.contains("ProjectCodingCoordinator")
                        && runtimeController.contains("coordinator.dispatch")
                        && runtimeController.contains("projectId, taskPlanId, executionId"),
                "Public execution start/read must retain Integration validation and path scope");
        assertTrue(coordinator.contains("dispatchNext")
                        && coordinator.contains("project-plan-auto:")
                        && coordinator.contains("PlanStepExecutionAction.START")
                        && coordinator.contains("PlanStepExecutionAction.COMPLETE")
                        && coordinator.contains("dispatchSuccessor")
                        && coordinator.contains("completeReviewedJob(")
                        && coordinator.contains("lockedExecution.state()")
                        && coordinator.contains("planExecutions.fail(transition, code)")
                        && coordinator.contains("PROJECT_PLAN_ACTIVE_JOB_MISSING"),
                "Project Coding must use durable single-flight PlanStep dispatch");
        assertTrue(runtimeExecutions.contains("insertIfAbsent(created)")
                        && runtimeExecutions.contains("findActiveByExecutionId")
                        && runtimeExecutions.contains("copyWithControl"),
                "Execution replay, Job projection and BLOCKED evidence must be exact-scoped");
        assertTrue(plans.contains("startRootTask")
                        && plans.contains("completeRootTask")
                        && plans.contains("failRootTask")
                        && plans.contains("PlanStepState.COMPLETED"),
                "Project must remain authoritative for Step, child Task, Plan and Root Task state");
    }

    @Test
    void projectRecoverySnapshotComposesOwnerApisAndCapturesGitOnlyInSandbox()
            throws IOException {
        String service = Files.readString(PLATFORM_MAIN_JAVA.resolve(
                "com/spaceagent/platform/runtime/application/"
                        + "ProjectRecoveryApplicationService.java"));
        String api = Files.readString(PLATFORM_MAIN_JAVA.resolve(
                "com/spaceagent/platform/runtime/api/ProjectRecoveryApplicationApi.java"));
        String migration = Files.readString(Path.of(
                "src/main/resources/db/platform-runtime/"
                        + "V1044__project_execution_context_snapshot.sql"));
        assertTrue(service.contains("ProjectDirectoryApplicationApi")
                        && service.contains("ProjectBlueprintApplicationApi")
                        && service.contains("ConversationContextSnapshotApplicationApi")
                        && service.contains("ArtifactApplicationApi")
                        && service.contains("ModelCallLedgerApplicationApi")
                        && service.contains("ToolExecutionLedgerApplicationApi")
                        && service.contains("GovernanceApplicationApi"),
                "Recovery composition must use public owner-module Application APIs");
        assertTrue(service.contains("SandboxComputeApplicationApi")
                        && service.contains("project-context-snapshot")
                        && service.contains("workspaces/")
                        && !service.contains("CodingWorkspaceApplicationApi")
                        && !service.contains("WorkspaceCodingGateway"),
                "Recovery Git evidence must use the exact OCI Sandbox Workspace without host fallback");
        assertTrue(api.contains("UnknownToolEffect")
                        && api.contains("UnknownModelEffect")
                        && api.contains("ApprovalRequirement")
                        && api.contains("checkpointSha256")
                        && !api.contains("checkpointSnapshot"),
                "Recovery API must carry safe typed evidence without raw checkpoint payloads");
        assertTrue(migration.contains("platform_project_execution_context_snapshots")
                        && migration.contains("fk_platform_project_context_run_scope")
                        && migration.contains("idempotency_hash")
                        && migration.contains("snapshot_hash"),
                "V1044 must enforce immutable idempotent exact-Run snapshot scope");
    }

    @Test
    void projectIntakeKeepsUnconfirmedIntentOutOfProjectAuthorityAndReadsOnlyInSandbox()
            throws IOException {
        String projectService = Files.readString(PLATFORM_MAIN_JAVA.resolve(
                "com/spaceagent/platform/project/application/ProjectIntakeApplicationService.java"));
        String coordinator = Files.readString(PLATFORM_MAIN_JAVA.resolve(
                "com/spaceagent/platform/integration/application/ProjectIntakeCoordinator.java"));
        String sandbox = Files.readString(PLATFORM_MAIN_JAVA.resolve(
                "com/spaceagent/platform/tooling/application/SandboxComputeApplicationService.java"));
        String migration = Files.readString(Path.of(
                "src/main/resources/db/platform-server/V1045__project_intake_jobs.sql"));
        assertTrue(projectService.indexOf("requireProposalState")
                        < projectService.indexOf("blueprints.create")
                        && projectService.indexOf("blueprints.create")
                        < projectService.indexOf("tasks.createTask"),
                "Project intent must be created only after proposal-state/hash confirmation");
        assertTrue(coordinator.contains("SandboxComputeApplicationApi")
                        && coordinator.contains("ToolExecutionLedgerApplicationApi")
                        && coordinator.contains("InferenceExecutionApi")
                        && !coordinator.contains("WorkspaceCodingGateway")
                        && !coordinator.contains("ProcessBuilder"),
                "Intake coordination must use owner APIs without host-side source execution");
        assertTrue(sandbox.contains("project-intake-inspection")
                        && sandbox.contains("readOnlyWorkspace"),
                "Project intake inspection must request an enforced read-only Sandbox mount");
        assertTrue(migration.contains("platform_project_intake_jobs")
                        && migration.contains("fencing_token")
                        && migration.contains("proposal_hash")
                        && migration.contains("ck_platform_project_intake_sandbox_root")
                        && migration.contains("uk_platform_project_intake_active_directory"),
                "V1045 must retain exact source-root scope, proposal evidence and worker fencing");
    }

    @Test
    void autonomousProjectCodingKeepsAuthorityInJavaAndAllWorkspaceEffectsInOci()
            throws IOException {
        String coordinator = Files.readString(PLATFORM_MAIN_JAVA.resolve(
                "com/spaceagent/platform/integration/application/ProjectCodingCoordinator.java"));
        String coding = Files.readString(PLATFORM_MAIN_JAVA.resolve(
                "com/spaceagent/platform/project/application/CodingWorkspaceApplicationService.java"));
        String workspaceTools = Files.readString(PLATFORM_MAIN_JAVA.resolve(
                "com/spaceagent/platform/project/application/WorkspaceToolApplicationService.java"));
        String sandboxAdapter = Files.readString(PLATFORM_MAIN_JAVA.resolve(
                "com/spaceagent/platform/integration/application/SandboxWorkspaceGateway.java"));
        String migration = Files.readString(Path.of(
                "src/main/resources/db/platform-runtime/V1046__project_coding_jobs.sql"));
        assertTrue(coordinator.contains("ProjectCodingJobApplicationApi")
                        && coordinator.contains("InferenceExecutionApi")
                        && coordinator.contains("RuntimeToolExecutionApplicationApi")
                        && coordinator.contains("MultiAgentCollaborationApplicationApi")
                        && coordinator.contains("ReviewedSourceMergeApplicationApi")
                        && !coordinator.contains(".infrastructure.persistence")
                        && !coordinator.contains("JdbcTemplate")
                        && !coordinator.contains("ProcessBuilder"),
                "Autonomous coding must coordinate public owner APIs without persistence or host effects");
        assertTrue(coding.contains("WorkspaceSandboxGateway")
                        && workspaceTools.contains("WorkspaceSandboxGateway")
                        && !coding.contains("WorkspaceCodingGateway gateway")
                        && !workspaceTools.contains("WorkspaceToolGateway"),
                "Active Workspace byte/Git/document paths must use the Sandbox port");
        assertTrue(sandboxAdapter.contains("SandboxComputeApplicationApi")
                        && sandboxAdapter.contains("spaceagent-workspace-tool")
                        && sandboxAdapter.contains("workspaces/"),
                "Integration must adapt exact Workspace scope to the existing OCI compute boundary");
        assertTrue(migration.contains("platform_project_coding_jobs")
                        && migration.contains("fencing_token")
                        && migration.contains("pending_approval_id")
                        && coordinator.contains("reviewerAgentId")
                        && migration.contains("source_merge_id"),
                "V1046 must persist fenced approval/review/merge execution state");
    }

    @Test
    void projectHandoffComposesRecoveryMemoryAndWorkspaceOwnersWithoutNewAuthority()
            throws IOException {
        String coordinator = Files.readString(PLATFORM_MAIN_JAVA.resolve(
                "com/spaceagent/platform/integration/application/ProjectRunHandoffCoordinator.java"));
        String finalizer = Files.readString(PLATFORM_MAIN_JAVA.resolve(
                "com/spaceagent/platform/integration/application/ProjectHandoffFinalizer.java"));
        String runtime = Files.readString(PLATFORM_MAIN_JAVA.resolve(
                "com/spaceagent/platform/runtime/application/ProjectRunHandoffApplicationService.java"));
        String migration = Files.readString(Path.of(
                "src/main/resources/db/platform-runtime/V1047__project_run_handoffs.sql"));
        assertTrue(coordinator.contains("ProjectRecoveryApplicationApi")
                        && coordinator.contains("MemoryApplicationApi")
                        && coordinator.contains("WorkspaceApplicationApi")
                        && coordinator.contains("ProjectCodingJobApplicationApi")
                        && !coordinator.contains(".infrastructure.persistence")
                        && !coordinator.contains("JdbcTemplate")
                        && !coordinator.contains("ProcessBuilder"),
                "Project handoff must compose only public owner APIs");
        assertTrue(finalizer.contains("claimFinalization")
                        && finalizer.contains("saveProjectSnapshot")
                        && finalizer.contains("workspaces.archive")
                        && !finalizer.contains("ProcessBuilder"),
                "Handoff finalization must be fenced and delegate memory/archive effects");
        assertTrue(runtime.contains("idempotencyHash")
                        && runtime.contains("fencingToken")
                        && runtime.contains("READY_TO_FINALIZE"),
                "Runtime must own durable idempotent/fenced handoff lifecycle");
        assertTrue(migration.contains("platform_project_run_handoffs")
                        && migration.contains("recovery_snapshot_id")
                        && migration.contains("target_coding_job_id")
                        && migration.contains("fencing_token")
                        && migration.contains("HANDED_OFF"),
                "V1047 must pin recovery, target execution and finalization fencing");
    }

    @Test
    void crossRuntimeGenAiTelemetryIsPinnedRedactedAndNonAuthoritative() throws IOException {
        String inference = Files.readString(PLATFORM_MAIN_JAVA.resolve(
                "com/spaceagent/platform/inference/infrastructure/MicrometerInferenceTelemetry.java"));
        String runtime = Files.readString(PLATFORM_MAIN_JAVA.resolve(
                "com/spaceagent/platform/runtime/infrastructure/MicrometerRuntimeOperationalTelemetry.java"));
        String orchestrator = Files.readString(REPOSITORY_ROOT.resolve(
                "services/multi-agent-orchestrator/src/telemetry.ts"));
        String sandbox = Files.readString(REPOSITORY_ROOT.resolve(
                "workers/sandbox-worker/sandbox_worker/telemetry.py"));
        String migration = Files.readString(Path.of(
                "src/main/resources/db/platform-runtime/V1048__model_first_chunk_evidence.sql"));
        String all = inference + runtime + orchestrator + sandbox;
        assertTrue(all.contains("94f432d7126f5884d30a2cdde6f4e89908ebb6fd")
                        && all.contains("gen_ai.operation.name")
                        && all.contains("gen_ai.response.time_to_first_chunk")
                        && orchestrator.contains("@opentelemetry/sdk-node")
                        && sandbox.contains("opentelemetry.sdk"),
                "GenAI telemetry must pin the reviewed upstream semantics and official SDKs");
        assertFalse(all.contains("gen_ai.input.messages")
                        || all.contains("gen_ai.output.messages")
                        || all.contains("gen_ai.system_instructions")
                        || all.contains("gen_ai.tool.call.arguments")
                        || all.contains("gen_ai.tool.call.result"),
                "Content-bearing GenAI attributes must remain hard-disabled");
        assertTrue(Files.readString(PLATFORM_MAIN_JAVA.resolve(
                        "com/spaceagent/platform/runtime/infrastructure/HttpMultiAgentOrchestrationClient.java"))
                        .contains("W3CTraceContextPropagator")
                        && Files.readString(PLATFORM_MAIN_JAVA.resolve(
                        "com/spaceagent/platform/tooling/infrastructure/HttpSandboxExecutionGateway.java"))
                        .contains("W3CTraceContextPropagator"),
                "Private Java HTTP boundaries must use official W3C propagation");
        assertTrue(migration.contains("first_chunk_at")
                        && migration.contains("first_chunk_ms")
                        && migration.contains("platform_trace_summaries"),
                "V1048 must retain first-chunk authority in ModelCallLedger and Trace projection");
    }

    @Test
    void githubMcpProjectImportPreservesModuleAuthorityAndHashedIdempotency() throws IOException {
        Path coordinator = PLATFORM_MAIN_JAVA.resolve(
                "com/spaceagent/platform/integration/application/"
                        + "GithubMcpProjectImportCoordinator.java");
        List<String> violations = imports(coordinator)
                .filter(line -> line.startsWith("import com.spaceagent.platform."))
                .filter(line -> !line.contains(".api."))
                .toList();
        assertTrue(violations.isEmpty(),
                "GitHub MCP import coordinator must use only public module APIs; found: "
                        + violations);
        List<String> projectToolingImports = javaFilesInModules(Set.of("project"))
                .flatMap(PlatformModuleArchitectureTest::imports)
                .filter(line -> line.startsWith("import com.spaceagent.platform.tooling."))
                .toList();
        assertTrue(projectToolingImports.isEmpty(),
                "Project must consume validated MCP results without depending on Tooling; found: "
                        + projectToolingImports);
        String migration = Files.readString(Path.of("src/main/resources/db/platform-server/"
                + "V1029__mcp_invocation_and_project_source.sql"));
        assertTrue(migration.contains("idempotency_key_hash CHAR(64)")
                        && !migration.contains("idempotency_key VARCHAR")
                        && migration.contains("deliberately no cross-module FK"),
                "MCP import must store only hashed idempotency and opaque Project provenance");
    }

    @Test
    void privateMcpCheckoutGrantStaysEphemeralAndOutsideProjectPersistence() throws IOException {
        List<String> httpLeaks = javaFiles(PLATFORM_MAIN_JAVA.resolve(
                        "com/spaceagent/platform/integration/infrastructure/http"))
                .flatMap(PlatformModuleArchitectureTest::imports)
                .filter(line -> line.contains("GithubMcpCheckoutApplicationApi"))
                .toList();
        assertTrue(httpLeaks.isEmpty(),
                "Secret-bearing MCP checkout API must have no HTTP adapter; found: " + httpLeaks);
        String workspace = Files.readString(PLATFORM_MAIN_JAVA.resolve(
                "com/spaceagent/platform/project/application/WorkspaceApplicationService.java"));
        assertTrue(!workspace.contains("com.spaceagent.platform.tooling")
                        && !workspace.contains("encryptedAuthorizationHeader")
                        && !workspace.contains("w.fail(e.getMessage()"),
                "Project must use its credential port and persist only safe Workspace failures");
        String git = Files.readString(PLATFORM_MAIN_JAVA.resolve(
                "com/spaceagent/platform/project/infrastructure/"
                        + "GitWorkspaceProvisioningGateway.java"));
        assertTrue(git.contains("GIT_CONFIG_VALUE_0")
                        && !git.contains("Authorization: Bearer \"+token")
                        && !git.contains("cmd.add(authorizationHeader)"),
                "Git authorization must stay in the child environment, never command arguments");
        String migration = Files.readString(Path.of("src/main/resources/db/platform-server/"
                + "V1030__mcp_checkout_grants.sql"));
        assertTrue(migration.contains("checkout_expires_at")
                        && migration.contains("checkout_consumed_at")
                        && migration.contains("result_json IS NULL"),
                "Checkout grant ciphertext must be expiring and cleared after consumption");
    }

    @Test
    void contextPackageIsRuntimeValueWithoutDurableRepositoryPort() throws IOException {
        List<String> repositoryPorts = javaFiles(
                PLATFORM_MAIN_JAVA.resolve("com/spaceagent/platform/context"))
                .map(Path::getFileName)
                .map(Path::toString)
                .filter(name -> name.endsWith("Repository.java"))
                .sorted()
                .toList();

        assertTrue(repositoryPorts.isEmpty(),
                "ContextPackage is a compiled runtime value and must not declare a durable "
                        + "repository port; found: " + repositoryPorts);
    }

    @Test
    void genericChatApprovalResumeUsesRuntimeCheckpointAndWorkerFence() throws IOException {
        String service = Files.readString(PLATFORM_MAIN_JAVA.resolve(
                "com/spaceagent/platform/runtime/application/ChatRuntimeApplicationService.java"));
        String checkpoint = Files.readString(PLATFORM_MAIN_JAVA.resolve(
                "com/spaceagent/platform/runtime/domain/ChatToolWaitCheckpoint.java"));
        String controller = Files.readString(PLATFORM_MAIN_JAVA.resolve(
                "com/spaceagent/platform/integration/infrastructure/http/PlatformChatHttpController.java"));
        assertTrue(checkpoint.contains("chat-approval/v1")
                        && service.contains("saveToolWaitCheckpoint")
                        && service.contains("markRunWaitingForUser")
                        && service.contains("acquireLease")
                        && service.contains("resumeFenced")
                        && service.contains("completeFenced"),
                "Chat approval resume must use versioned Runtime checkpoints and worker fencing");
        assertTrue(controller.contains("resume-approval")
                        && !controller.contains("ToolExecutionLedgerApplicationApi")
                        && !controller.contains("GovernanceRepository")
                        && !service.contains("reconcileUnknown("),
                "Chat HTTP must stay behind Runtime and M53-PR1 must not expose generic UNKNOWN edits");
    }

    @Test
    void toolUnknownReconciliationIsAllowlistedReadOnlyAndNotClientDirected() throws IOException {
        String service = Files.readString(PLATFORM_MAIN_JAVA.resolve(
                "com/spaceagent/platform/runtime/application/"
                        + "RuntimeToolReconciliationApplicationService.java"));
        String api = Files.readString(PLATFORM_MAIN_JAVA.resolve(
                "com/spaceagent/platform/runtime/api/RuntimeToolReconciliationApplicationApi.java"));
        String controller = Files.readString(PLATFORM_MAIN_JAVA.resolve(
                "com/spaceagent/platform/integration/infrastructure/http/"
                        + "PlatformRuntimeToolReconciliationHttpController.java"));
        String checkpoint = Files.readString(PLATFORM_MAIN_JAVA.resolve(
                "com/spaceagent/platform/runtime/domain/ChatToolWaitCheckpoint.java"));
        assertTrue(service.contains("case \"document_write\"")
                        && service.contains("case \"workspace-write_file\"")
                        && service.contains("case \"workspace-delete_file\"")
                        && service.contains("readFile(")
                        && service.contains("readDocument(")
                        && !service.contains("external.execute(")
                        && !service.contains("workspace.write"),
                "UNKNOWN reconciliation must use only allowlisted read-only postconditions");
        assertTrue(!api.contains("ToolExecutionStatus resolution")
                        && !api.contains("String result,")
                        && !api.contains("ReconciliationEvidence evidence")
                        && !controller.contains("ToolExecutionLedgerApplicationApi")
                        && checkpoint.contains("chat-tool-unknown/v1"),
                "Clients must not choose reconciliation state/evidence or bypass Runtime");
    }

    @Test
    void chatPlanningKeepsSharedJavaAuthorityWithoutHiddenProject() throws IOException {
        String task = Files.readString(PLATFORM_MAIN_JAVA.resolve(
                "com/spaceagent/platform/project/domain/Task.java"));
        String chat = Files.readString(PLATFORM_MAIN_JAVA.resolve(
                "com/spaceagent/platform/runtime/application/ChatRuntimeApplicationService.java"));
        String migration = Files.readString(Path.of(
                "src/main/resources/db/platform-runtime/V1049__chat_root_task_scope.sql"));
        String planMigration = Files.readString(Path.of(
                "src/main/resources/db/platform-runtime/V1050__chat_task_plan_scope.sql"));
        String orchestration = Files.readString(PLATFORM_MAIN_JAVA.resolve(
                "com/spaceagent/platform/runtime/application/"
                        + "MultiAgentOrchestrationApplicationService.java"));
        assertTrue(task.contains("createChatRoot")
                        && chat.contains("createOrGetChatRootTask")
                        && chat.contains("chatTask == null ? null : chatTask.id()")
                        && !chat.contains("createProject("),
                "Chat must create a scoped Root Task and never a hidden Project");
        assertTrue(migration.contains("source_message_id")
                        && migration.contains("chat_task_id")
                        && migration.contains("ck_platform_task_scope")
                        && migration.contains("ck_platform_agent_run_task_scope")
                        && !chat.contains("createPlan("),
                "V1049 must enforce mutually exclusive Chat/Project Root Task scope");
        assertTrue(planMigration.contains("ck_platform_task_plan_scope")
                        && planMigration.contains("source_agent_run_id")
                        && planMigration.contains("fk_platform_plan_step_chat_child")
                        && orchestration.contains("createChatProposal")
                        && orchestration.contains("effectiveTaskId(run)"),
                "V1050 Chat plans must persist through Java-owned scoped TaskPlan APIs");
        String reviewCheckpoint = Files.readString(PLATFORM_MAIN_JAVA.resolve(
                "com/spaceagent/platform/runtime/domain/ChatPlanReviewCheckpoint.java"));
        String releaseOverlay = Files.readString(
                REPOSITORY_ROOT.resolve("docker-compose.release.yml"));
        assertTrue(chat.contains("beginAutomaticPlanning")
                        && chat.contains("WAITING_PLAN_APPROVAL")
                        && chat.contains("chat-plan-review")
                        && reviewCheckpoint.contains("chat-plan-review/v1")
                        && releaseOverlay.contains(
                                "PLATFORM_CHAT_AUTOMATIC_PLANNING_ENABLED: ${CHAT_AUTOMATIC_PLANNING_ENABLED:-false}"),
                "M54-PR3A planning must wait durably and remain default-off");
        assertTrue(chat.contains("resumePlan(")
                        && chat.contains("executeActivePlan(")
                        && chat.contains("transitionChatPlanStep")
                        && chat.contains("planProgress()")
                        && chat.contains("chat-plan-synthesis"),
                "M54-PR3B must execute the active DAG and preserve Tool-wait plan progress");
    }

    @Test
    void skillRegistryIsVersionedInToolingAndAgentBindsPublishedVersions() throws IOException {
        String migration = Files.readString(Path.of(
                "src/main/resources/db/platform-runtime/V1051__versioned_skill_registry.sql"));
        String registry = Files.readString(PLATFORM_MAIN_JAVA.resolve(
                "com/spaceagent/platform/tooling/application/SkillRegistryApplicationService.java"));
        String catalog = Files.readString(PLATFORM_MAIN_JAVA.resolve(
                "com/spaceagent/platform/tooling/application/RuntimeCapabilityCatalogApplicationService.java"));
        String agent = Files.readString(PLATFORM_MAIN_JAVA.resolve(
                "com/spaceagent/platform/agent/application/AgentApplicationService.java"));
        String chat = Files.readString(PLATFORM_MAIN_JAVA.resolve(
                "com/spaceagent/platform/runtime/application/ChatRuntimeApplicationService.java"));
        String coding = Files.readString(PLATFORM_MAIN_JAVA.resolve(
                "com/spaceagent/platform/integration/application/ProjectCodingCoordinator.java"));
        String contextTypes = Files.readString(PLATFORM_MAIN_JAVA.resolve(
                "com/spaceagent/platform/context/domain/ContextSourceType.java"));
        assertTrue(migration.contains("platform_skill_definitions")
                        && migration.contains("platform_skill_versions")
                        && migration.contains("config_hash")
                        && migration.contains("fk_platform_skill_definition_current_version"),
                "V1051 must persist immutable versioned Skill authority in Tooling");
        assertTrue(registry.contains("MAX_INSTRUCTION_BYTES")
                        && registry.contains("SKILL_TOOL_NOT_REGISTERED")
                        && !registry.contains("ProcessBuilder")
                        && !registry.contains("Runtime.getRuntime"),
                "M55-PR1 Skills must be bounded inert instructions, never executable host code");
        assertTrue(catalog.contains("SkillVersionStatus.PUBLISHED")
                        && catalog.contains("currentVersionId")
                        && agent.contains("validateSkillBindings"),
                "Agent current configuration must bind the published tenant SkillVersion only");
        assertTrue(catalog.contains("resolvePinnedSkills")
                        && catalog.contains("AGENT_SKILL_TOOL_NOT_ENABLED")
                        && catalog.contains("SkillVersionStatus.DEPRECATED")
                        && contextTypes.contains("SKILL"),
                "M55-PR2 must resolve historical pins and keep required Tools separately enabled");
        assertTrue(chat.contains("ContextSourceType.SKILL")
                        && chat.contains("skill-context-bound")
                        && chat.contains("checkpointSkillUse")
                        && coding.contains("resolvePinnedSkills")
                        && coding.contains("checkpointSkillUse")
                        && coding.contains("project-coding-review"),
                "M55-PR2 must bind Skill context and hash-only evidence in Chat and Project Runs");
    }

    private static boolean isInDomainPackage(Path file) {
        return file.toString().replace('\\', '/')
                .contains("/com/spaceagent/platform/")
                && file.toString().replace('\\', '/').contains("/domain/");
    }

    private static boolean isFrameworkImport(String importLine) {
        return importLine.startsWith("import org.springframework.")
                || importLine.startsWith("import static org.springframework.")
                || importLine.startsWith("import org.mybatis.")
                || importLine.startsWith("import static org.mybatis.")
                || importLine.startsWith("import dev.langchain4j.")
                || importLine.startsWith("import static dev.langchain4j.")
                || importLine.startsWith("import io.temporal.")
                || importLine.startsWith("import static io.temporal.");
    }

    private static boolean isCrossModulePersistenceImport(String importLine, String ownerModule) {
        if (!importLine.contains(".infrastructure.persistence")) {
            return false;
        }
        return MODULES.stream()
                .filter(module -> !module.equals(ownerModule))
                .map(module -> "com.spaceagent.platform." + module + ".infrastructure.persistence")
                .anyMatch(importLine::startsWith);
    }

    private static String owningModule(Path file) {
        String path = file.toString().replace('\\', '/');
        String marker = "/com/spaceagent/platform/";
        int markerIndex = path.indexOf(marker);
        if (markerIndex < 0) {
            return "";
        }
        String after = path.substring(markerIndex + marker.length());
        int slash = after.indexOf('/');
        return slash < 0 ? after : after.substring(0, slash);
    }

    private static Stream<Path> javaFilesInModules(Set<String> modules) throws IOException {
        return javaFiles(PLATFORM_MAIN_JAVA)
                .filter(file -> modules.stream().anyMatch(module ->
                        file.toString().replace('\\', '/')
                                .contains("/com/spaceagent/platform/" + module + "/")));
    }

    private static List<String> requiredBoundaries(String module) {
        if (APPLICATION_MODULES.contains(module)) {
            return List.of("api", "application", "domain", "infrastructure");
        }
        return List.of("api", "domain", "infrastructure");
    }

    private static Path packageInfo(String moduleAndBoundary) {
        return PLATFORM_MAIN_JAVA.resolve(
                "com/spaceagent/platform/" + moduleAndBoundary + "/package-info.java");
    }

    private static Stream<Path> javaFiles(Path root) throws IOException {
        if (!Files.isDirectory(root)) {
            return Stream.empty();
        }
        try (Stream<Path> paths = Files.walk(root)) {
            return paths.filter(Files::isRegularFile)
                    .filter(path -> path.toString().endsWith(".java"))
                    .toList()
                    .stream();
        }
    }

    private static Stream<String> imports(Path file) {
        try {
            return Files.readAllLines(file).stream()
                    .filter(line -> line.startsWith("import "))
                    .map(String::trim);
        } catch (IOException error) {
            throw new IllegalStateException("Failed to read " + file, error);
        }
    }

    private static Path locatePlatformMainJava() {
        Path moduleCandidate = Path.of("src/main/java");
        if (Files.isDirectory(moduleCandidate)) {
            return moduleCandidate;
        }
        Path reactorCandidate = Path.of("apps/platform-server/src/main/java");
        if (Files.isDirectory(reactorCandidate)) {
            return reactorCandidate;
        }
        return Path.of("src/main/java");
    }
}

package com.spaceagent.platform.integration.application;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.spaceagent.platform.agent.api.AgentApplicationApi;
import com.spaceagent.platform.agent.api.AgentCurrentConfigurationApplicationApi;
import com.spaceagent.platform.agent.domain.AgentDefinitionStatus;
import com.spaceagent.platform.conversation.api.ConversationApplicationApi;
import com.spaceagent.platform.conversation.api.SetConversationActiveTaskCommand;
import com.spaceagent.platform.conversation.domain.ConversationStatus;
import com.spaceagent.platform.inference.api.InferenceExecutionApi;
import com.spaceagent.platform.inference.api.ModelPoolApplicationApi;
import com.spaceagent.platform.project.api.ProjectIntakeApplicationApi;
import com.spaceagent.platform.project.api.ProjectIntakeInspection;
import com.spaceagent.platform.runtime.api.CompleteAgentRunCommand;
import com.spaceagent.platform.runtime.api.AgentRunConfigurationSnapshotApplicationApi;
import com.spaceagent.platform.runtime.api.CompleteRunStepCommand;
import com.spaceagent.platform.runtime.api.FailAgentRunCommand;
import com.spaceagent.platform.runtime.api.FailRunStepCommand;
import com.spaceagent.platform.runtime.api.RuntimeApplicationApi;
import com.spaceagent.platform.runtime.api.RunStepView;
import com.spaceagent.platform.runtime.api.StartAgentRunCommand;
import com.spaceagent.platform.runtime.api.StartRunStepCommand;
import com.spaceagent.platform.runtime.domain.AgentRunState;
import com.spaceagent.platform.runtime.domain.RunStepState;
import com.spaceagent.platform.tooling.api.ClaimToolExecutionCommand;
import com.spaceagent.platform.tooling.api.CompleteToolExecutionCommand;
import com.spaceagent.platform.tooling.api.MarkToolExecutionUnknownCommand;
import com.spaceagent.platform.tooling.api.RuntimeCapabilityCatalogApplicationApi;
import com.spaceagent.platform.tooling.api.SandboxComputeApplicationApi;
import com.spaceagent.platform.tooling.api.ToolExecutionClaimView;
import com.spaceagent.platform.tooling.api.ToolExecutionLedgerApplicationApi;
import com.spaceagent.platform.tooling.domain.ToolExecutionClaimDecisionType;
import com.spaceagent.platform.tooling.domain.ToolExecutionStatus;
import com.spaceagent.shared.exception.BusinessException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.regex.Pattern;

/** Coordinates external read/model work while owner modules retain every durable row. */
@Service
public class ProjectIntakeCoordinator {
    private static final int MAX_TRACKED_FILES = 20_000;
    private static final int MAX_RECORDED_PATHS = 1_000;
    private static final int MAX_SELECTED_FILES = 24;
    private static final int MAX_FILE_CHARACTERS = 32_000;
    private static final int MAX_SELECTED_CHARACTERS = 180_000;
    private static final Set<String> EXACT_FILES = Set.of(
            "README", "README.md", "AGENTS.md", "pom.xml", "build.gradle",
            "build.gradle.kts", "settings.gradle", "settings.gradle.kts", "package.json",
            "pnpm-workspace.yaml", "go.mod", "Cargo.toml", "pyproject.toml",
            "requirements.txt", "Dockerfile", "docker-compose.yml", "docker-compose.yaml",
            "Makefile", "CONTRIBUTING.md");
    private static final Pattern SECRET_LINE = Pattern.compile(
            "(?i).*(api[_-]?key|secret|password|passwd|token|authorization|private[_-]?key|client[_-]?secret).*[:=].*");
    private static final Pattern INLINE_SECRET = Pattern.compile(
            "(?i)(bearer\\s+)[A-Za-z0-9._~+/=-]{8,}|(sk|ghp|github_pat|xox[baprs])[-_A-Za-z0-9]{8,}");

    private final ProjectIntakeApplicationApi intake;
    private final ConversationApplicationApi conversations;
    private final AgentApplicationApi agents;
    private final AgentCurrentConfigurationApplicationApi currentConfigurations;
    private final AgentRunConfigurationSnapshotApplicationApi runConfigurations;
    private final RuntimeApplicationApi runtime;
    private final ToolExecutionLedgerApplicationApi toolLedger;
    private final SandboxComputeApplicationApi sandbox;
    private final ModelPoolApplicationApi modelPools;
    private final InferenceExecutionApi inference;
    private final ObjectMapper json;
    private final RuntimeCapabilityCatalogApplicationApi capabilities;
    private final TransactionTemplate transactions;

    public ProjectIntakeCoordinator(
            ProjectIntakeApplicationApi intake,
            ConversationApplicationApi conversations,
            AgentApplicationApi agents,
            AgentCurrentConfigurationApplicationApi currentConfigurations,
            AgentRunConfigurationSnapshotApplicationApi runConfigurations,
            RuntimeApplicationApi runtime,
            ToolExecutionLedgerApplicationApi toolLedger,
            SandboxComputeApplicationApi sandbox,
            ModelPoolApplicationApi modelPools,
            InferenceExecutionApi inference,
            ObjectMapper json,
            RuntimeCapabilityCatalogApplicationApi capabilities,
            PlatformTransactionManager transactionManager) {
        this.intake = intake;
        this.conversations = conversations;
        this.agents = agents;
        this.currentConfigurations = currentConfigurations;
        this.runConfigurations = runConfigurations;
        this.runtime = runtime;
        this.toolLedger = toolLedger;
        this.sandbox = sandbox;
        this.modelPools = modelPools;
        this.inference = inference;
        this.json = json;
        this.capabilities = capabilities;
        this.transactions = new TransactionTemplate(transactionManager);
    }

    public ProjectIntakeApplicationApi.JobView enqueue(
            ProjectIntakeApplicationApi.EnqueueCommand command) {
        var sandboxCapability = capabilities.catalog().sandbox();
        if (!sandboxCapability.containerized()
                || !"HTTP".equalsIgnoreCase(sandboxCapability.mode())) {
            throw new BusinessException(
                    "Project intake requires the isolated Sandbox worker",
                    HttpStatus.SERVICE_UNAVAILABLE, "PROJECT_INTAKE_SANDBOX_REQUIRED");
        }
        validateConversation(command);
        validateAgent(command);
        return intake.enqueue(command);
    }

    public ProjectIntakeApplicationApi.JobView confirm(
            ProjectIntakeApplicationApi.ConfirmCommand command) {
        return transactions.execute(status -> {
            var confirmed = intake.confirm(command);
            conversations.setActiveTask(new SetConversationActiveTaskCommand(
                    confirmed.tenantId(), confirmed.ownerId(), confirmed.conversationId(),
                    confirmed.rootTaskId()));
            return confirmed;
        });
    }

    public ProjectIntakeApplicationApi.JobView reject(
            ProjectIntakeApplicationApi.RejectCommand command) {
        return intake.reject(command);
    }

    public boolean runOnce(String workerId, int leaseSeconds, int maximumAttempts) {
        var claim = intake.claim(workerId, leaseSeconds, maximumAttempts).orElse(null);
        if (claim == null) return false;
        execute(claim, workerId, leaseSeconds);
        return true;
    }

    private void execute(
            ProjectIntakeApplicationApi.ClaimView claim, String workerId, int leaseSeconds) {
        var job = claim.job();
        RunStepView step = null;
        try {
            job = intake.provisionWorkspace(new ProjectIntakeApplicationApi.ClaimCommand(
                    job.id(), workerId, claim.claimToken(), claim.fencingToken()));
            if (job.agentRunId() == null) {
                ProjectIntakeApplicationApi.JobView current = job;
                job = transactions.execute(status -> {
                    var run = runtime.startRun(new StartAgentRunCommand(
                            current.tenantId(), current.ownerId(), current.agentId(),
                            null, current.conversationId(), null, null,
                            null, null, null, null));
                    runtime.markRunInProgress(run.id());
                    return intake.attachRun(new ProjectIntakeApplicationApi.AttachRunCommand(
                            current.id(), workerId, claim.claimToken(), claim.fencingToken(),
                            run.id()));
                });
            }
            step = resolveStep(job.agentRunId());
            ProjectIntakeInspection inspection = job.inspection() == null
                    ? inspect(job, step, claim, leaseSeconds)
                    : inspection(new ProjectIntakeApplicationApi.ClaimCommand(
                            job.id(), workerId, claim.claimToken(), claim.fencingToken()));
            if (job.inspection() == null) {
                String inspectionJson = write(inspection);
                job = intake.recordInspection(new ProjectIntakeApplicationApi.InspectionCommand(
                        job.id(), workerId, claim.claimToken(), claim.fencingToken(),
                        inspection.headCommit(), sha256(inspectionJson), inspectionJson));
            }
            String rawProposal = generate(job, step, inspection);
            var proposal = parseProposal(rawProposal);
            String proposalJson = write(proposal);
            ProjectIntakeApplicationApi.JobView finalJob = job;
            RunStepView finalStep = step;
            transactions.executeWithoutResult(status -> {
                runtime.completeStep(new CompleteRunStepCommand(finalJob.agentRunId(), finalStep.id()));
                runtime.complete(new CompleteAgentRunCommand(finalJob.agentRunId()));
                intake.publishProposal(new ProjectIntakeApplicationApi.ProposalCommand(
                        finalJob.id(), workerId, claim.claimToken(), claim.fencingToken(),
                        sha256(proposalJson), proposalJson));
            });
        } catch (RuntimeException error) {
            fail(job, step, claim, workerId, error);
        } finally {
            intake.cleanupOneWorkspace();
        }
    }

    private RunStepView resolveStep(String runId) {
        return runtime.findSteps(runId).stream()
                .filter(value -> "project-intake".equals(value.type()))
                .filter(value -> value.state() == RunStepState.PENDING
                        || value.state() == RunStepState.IN_PROGRESS)
                .findFirst().orElseGet(() -> runtime.startStep(
                        new StartRunStepCommand(runId, "project-intake")));
    }

    private ProjectIntakeInspection inspect(
            ProjectIntakeApplicationApi.JobView job,
            RunStepView step,
            ProjectIntakeApplicationApi.ClaimView lease,
            int leaseSeconds) {
        String arguments = "{\"operation\":\"project-intake-inspection\"}";
        String inputHash = sha256(job.id() + "\n" + job.sourceHeadCommit());
        ToolExecutionClaimView claim = toolLedger.claim(new ClaimToolExecutionCommand(
                job.agentRunId(), step.id(), "project_intake_inspection",
                "project-intake:" + job.id(), "project-intake:" + job.id(),
                arguments, inputHash, Math.max(60, Math.min(1_800, leaseSeconds))));
        if (claim.type() == ToolExecutionClaimDecisionType.BUSY
                || claim.type() == ToolExecutionClaimDecisionType.UNKNOWN
                || claim.type() == ToolExecutionClaimDecisionType.CONFLICT) {
            throw new BusinessException("Project intake inspection requires reconciliation",
                    HttpStatus.CONFLICT, "PROJECT_INTAKE_TOOL_UNKNOWN");
        }
        try {
            ProjectIntakeInspection result = sandboxInspection(job);
            if (claim.type() == ToolExecutionClaimDecisionType.CLAIMED) {
                String summary = "{\"trackedFileCount\":" + result.trackedFileCount()
                        + ",\"selectedFileCount\":" + result.selectedFiles().size() + "}";
                toolLedger.complete(new CompleteToolExecutionCommand(
                        job.agentRunId(), claim.ledger().toolCallId(), claim.claimToken(),
                        claim.revision(), ToolExecutionStatus.SUCCEEDED, summary,
                        "project-intake:" + job.id() + ":" + sha256(write(result)), null));
            }
            return result;
        } catch (RuntimeException error) {
            if (claim.type() == ToolExecutionClaimDecisionType.CLAIMED) {
                try {
                    toolLedger.markUnknown(new MarkToolExecutionUnknownCommand(
                            job.agentRunId(), claim.ledger().toolCallId(), claim.claimToken(),
                            claim.revision(), "PROJECT_INTAKE_SANDBOX_OUTCOME_UNKNOWN"));
                } catch (RuntimeException ignored) {
                    // A concurrent terminal result will be observed from the ledger.
                }
            }
            throw new BusinessException("Project intake Sandbox inspection is unavailable",
                    HttpStatus.SERVICE_UNAVAILABLE, "PROJECT_INTAKE_TOOL_UNKNOWN");
        }
    }

    private ProjectIntakeInspection sandboxInspection(ProjectIntakeApplicationApi.JobView job) {
        String head = git(job, "head", List.of("rev-parse", "HEAD")).trim().toLowerCase();
        if (!head.equals(job.sourceHeadCommit())) {
            throw new BusinessException("Project source changed during intake", HttpStatus.CONFLICT,
                    "PROJECT_INTAKE_SOURCE_CHANGED");
        }
        String pathsOutput = git(job, "paths", List.of("ls-files", "-z"));
        List<String> paths = paths(pathsOutput);
        List<String> recorded = paths.stream().limit(MAX_RECORDED_PATHS).toList();
        List<String> selected = paths.stream().filter(ProjectIntakeCoordinator::selected)
                .sorted(Comparator.comparingInt(ProjectIntakeCoordinator::priority)
                        .thenComparing(value -> value))
                .limit(MAX_SELECTED_FILES).toList();
        List<ProjectIntakeInspection.InspectedFile> files = new ArrayList<>();
        int total = 0;
        for (String path : selected) {
            String content = git(job, "file-" + sha256(path).substring(7, 19),
                    List.of("show", "HEAD:" + path));
            boolean truncated = content.length() > MAX_FILE_CHARACTERS;
            content = content.substring(0, Math.min(content.length(), MAX_FILE_CHARACTERS));
            content = redact(content);
            int remaining = MAX_SELECTED_CHARACTERS - total;
            if (remaining <= 0) break;
            if (content.length() > remaining) {
                content = content.substring(0, remaining);
                truncated = true;
            }
            total += content.length();
            files.add(new ProjectIntakeInspection.InspectedFile(
                    path, sha256(content), truncated, content));
        }
        return new ProjectIntakeInspection(head, paths.size(), recorded, files);
    }

    private String git(
            ProjectIntakeApplicationApi.JobView job, String suffix, List<String> arguments) {
        var result = sandbox.execute(new SandboxComputeApplicationApi.ComputeCommand(
                job.agentRunId(), "project-intake:" + job.id() + ":" + suffix,
                "workspaces/" + job.id(), job.id(), "project-intake-inspection",
                "git", arguments, 60));
        if (!"SUCCEEDED".equals(result.status()) || result.exitStatus() != 0) {
            throw new IllegalStateException("Sandbox Git inspection failed");
        }
        return result.stdout() == null ? "" : result.stdout();
    }

    private String generate(
            ProjectIntakeApplicationApi.JobView job,
            RunStepView step,
            ProjectIntakeInspection inspection) {
        AgentRunConfigurationSnapshotApplicationApi.SnapshotView agent =
                runConfigurations.require(job.tenantId(), job.ownerId(), job.agentRunId());
        Selection selection = selection(job, agent);
        int availableInputCharacters = Math.max(8_000,
                Math.min(120_000, (agent.maxContextTokens() - agent.maxOutputTokens()) * 3));
        String evidence = write(inspection);
        evidence = evidence.substring(0, Math.min(evidence.length(), availableInputCharacters));
        String system = """
                You are a software-project intake planner. Return exactly one JSON object and no
                markdown. Do not invent persistent IDs. Do not include secrets or file contents in
                evidence. Produce this shape:
                {"blueprint":{"goal":"","requirements":[],"modules":[],"boundaries":[],
                "architectureDecisions":[],"commands":{},"environmentRefs":[],"conventions":[],
                "forbiddenAreas":[],"risks":[],"openQuestions":[],"acceptanceCriteria":[]},
                "rootTask":{"title":"","goal":"","description":"","constraints":[],
                "acceptanceCriteria":[]},"childTasks":[{"key":"lowercase-key","title":"",
                "goal":"","description":"","constraints":[],"acceptanceCriteria":[]}],
                "steps":[{"stepKey":"lowercase-key","childTaskKey":"lowercase-key",
                "dependsOnStepKeys":[],"requiredCapability":null,"expectedOutput":"",
                "acceptanceCriteria":[],"approvalRequired":false}]}
                Every childTask key must appear in exactly one step. Dependencies must be acyclic.
                environmentRefs contain variable names only, never values.
                """;
        List<InferenceExecutionApi.InferenceMessage> messages = List.of(
                new InferenceExecutionApi.InferenceMessage("system", system),
                new InferenceExecutionApi.InferenceMessage("user",
                        "User goal:\n" + job.goal() + "\n\nRedacted repository evidence:\n" + evidence));
        var result = inference.execute(new InferenceExecutionApi.InferenceExecutionCommand(
                selection.providerId(), selection.modelId(), messages,
                Map.of("temperature", 0D,
                        "maxOutputTokens", Math.min(12_000, agent.maxOutputTokens())),
                job.agentRunId(), step.id(), "project-intake:" + job.id(), job.tenantId(),
                selection.modelPoolId(), selection.candidates(), selection.strategy(),
                selection.snapshotHash(), selection.fallbackEnabled()));
        if (!result.toolCalls().isEmpty() || result.content().isBlank()
                || result.content().getBytes(StandardCharsets.UTF_8).length > 500_000) {
            throw new BusinessException("Project intake model proposal is invalid",
                    HttpStatus.BAD_GATEWAY, "PROJECT_INTAKE_PROPOSAL_INVALID");
        }
        return result.content().trim();
    }

    private Selection selection(
            ProjectIntakeApplicationApi.JobView job,
            AgentRunConfigurationSnapshotApplicationApi.SnapshotView agent) {
        if (agent.modelPoolId() == null) {
            String provider = agent.modelProviderId() == null ? "generic" : agent.modelProviderId();
            String model = agent.modelId() == null ? "default" : agent.modelId();
            return new Selection(null, provider, model,
                    List.of(new InferenceExecutionApi.InferenceCandidate(
                            null, provider, null, model, 0, 1, null, null, null, null)),
                    "PRIORITY", null, false);
        }
        var resolved = modelPools.resolvePool(
                job.tenantId(), job.ownerId(), agent.modelPoolId(), job.id());
        var candidates = resolved.candidates().stream().map(value ->
                new InferenceExecutionApi.InferenceCandidate(
                        value.memberId(), value.providerId(), value.providerModelId(),
                        value.modelId(), value.priority(), value.weight(), value.healthLatencyMs(),
                        value.priceId(), value.inputMicrosPerMillionTokens(),
                        value.outputMicrosPerMillionTokens())).toList();
        var primary = candidates.getFirst();
        return new Selection(agent.modelPoolId(), primary.providerId(), primary.modelId(),
                candidates, resolved.routingStrategy().name(),
                resolved.candidateSnapshotHash(), resolved.fallbackEnabled());
    }

    private void fail(
            ProjectIntakeApplicationApi.JobView job,
            RunStepView step,
            ProjectIntakeApplicationApi.ClaimView claim,
            String workerId,
            RuntimeException error) {
        boolean blocked = error instanceof BusinessException business
                && (business.getCode().contains("UNKNOWN")
                    || business.getCode().contains("IN_PROGRESS")
                    || business.getCode().contains("LEASE_LOST"));
        String code = error instanceof BusinessException business
                ? safeCode(business.getCode()) : "PROJECT_INTAKE_FAILED";
        try {
            ProjectIntakeApplicationApi.JobView finalJob = job;
            RunStepView finalStep = step;
            transactions.executeWithoutResult(status -> {
                if (finalStep != null && finalJob.agentRunId() != null) {
                    runtime.findSteps(finalJob.agentRunId()).stream()
                            .filter(value -> value.id().equals(finalStep.id()))
                            .filter(value -> value.state() == RunStepState.PENDING
                                    || value.state() == RunStepState.IN_PROGRESS)
                            .findFirst().ifPresent(value -> runtime.failStep(
                                    new FailRunStepCommand(finalJob.agentRunId(), value.id())));
                    runtime.findRun(finalJob.agentRunId())
                            .filter(value -> value.state() != AgentRunState.COMPLETED
                                    && value.state() != AgentRunState.FAILED
                                    && value.state() != AgentRunState.CANCELLED)
                            .ifPresent(value -> runtime.fail(new FailAgentRunCommand(
                                    value.id(), code)));
                }
                intake.fail(new ProjectIntakeApplicationApi.FailCommand(
                        finalJob.id(), workerId, claim.claimToken(), claim.fencingToken(),
                        code, blocked));
            });
        } catch (RuntimeException ignored) {
            // A stale worker cannot overwrite the current Job/Run owner.
        }
    }

    private ProjectIntakeInspection inspection(ProjectIntakeApplicationApi.ClaimCommand command) {
        try {
            return json.readerFor(ProjectIntakeInspection.class)
                    .with(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                    .readValue(intake.readInspection(command));
        } catch (BusinessException error) {
            throw error;
        } catch (Exception error) {
            throw new BusinessException("Project intake inspection replay is unavailable",
                    HttpStatus.CONFLICT, "PROJECT_INTAKE_INSPECTION_REPLAY_REQUIRED");
        }
    }

    private ProjectIntakeApplicationApi.ProjectIntakeProposal parseProposal(String value) {
        try {
            return json.readerFor(ProjectIntakeApplicationApi.ProjectIntakeProposal.class)
                    .with(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).readValue(value);
        } catch (Exception error) {
            throw new BusinessException("Project intake proposal is not valid JSON",
                    HttpStatus.BAD_GATEWAY, "PROJECT_INTAKE_PROPOSAL_INVALID");
        }
    }

    private void validateConversation(ProjectIntakeApplicationApi.EnqueueCommand command) {
        conversations.find(command.conversationId())
                .filter(value -> value.tenantId().equals(command.tenantId()))
                .filter(value -> value.userId().equals(command.userId()))
                .filter(value -> command.projectId().equals(value.projectId()))
                .filter(value -> command.projectDirectoryId().equals(value.projectDirectoryId()))
                .filter(value -> value.status() == ConversationStatus.ACTIVE)
                .orElseThrow(() -> new BusinessException(
                        "Active Project Conversation not found", HttpStatus.NOT_FOUND,
                        "PROJECT_INTAKE_CONVERSATION_NOT_FOUND"));
    }

    private void validateAgent(ProjectIntakeApplicationApi.EnqueueCommand command) {
        var agent = agents.findById(command.agentId())
                .filter(value -> value.tenantId().equals(command.tenantId()))
                .filter(value -> value.ownerId().equals(command.userId()))
                .filter(value -> value.status() == AgentDefinitionStatus.ACTIVE)
                .orElseThrow(() -> new BusinessException(
                        "Active Agent not found", HttpStatus.NOT_FOUND,
                        "PROJECT_INTAKE_AGENT_NOT_FOUND"));
        currentConfigurations.requireCurrent(command.tenantId(), command.userId(), agent.id());
    }

    private List<String> paths(String value) {
        if (value == null || value.isEmpty()) return List.of();
        TreeSet<String> result = new TreeSet<>();
        for (String path : value.split("\\u0000", -1)) {
            if (path.isEmpty()) continue;
            if (path.length() > 500 || path.startsWith("/") || path.contains("..")
                    || path.contains(":") || path.chars().anyMatch(ch -> ch < 32)) {
                throw new IllegalStateException("Sandbox returned an invalid tracked path");
            }
            result.add(path);
            if (result.size() > MAX_TRACKED_FILES) {
                throw new BusinessException("Project contains too many tracked files",
                        HttpStatus.PAYLOAD_TOO_LARGE, "PROJECT_INTAKE_FILE_LIMIT");
            }
        }
        return List.copyOf(result);
    }

    private static boolean selected(String path) {
        String name = path.substring(path.lastIndexOf('/') + 1);
        String lower = path.toLowerCase(Locale.ROOT);
        return EXACT_FILES.contains(name)
                || lower.startsWith("docs/") && lower.endsWith(".md")
                || lower.startsWith(".github/workflows/")
                    && (lower.endsWith(".yml") || lower.endsWith(".yaml"));
    }

    private static int priority(String path) {
        String name = path.substring(path.lastIndexOf('/') + 1);
        if (name.equals("AGENTS.md")) return 0;
        if (name.startsWith("README")) return 1;
        if (Set.of("pom.xml", "package.json", "go.mod", "pyproject.toml", "Cargo.toml")
                .contains(name)) return 2;
        if (name.startsWith("docker-compose") || name.equals("Dockerfile")) return 3;
        return 4;
    }

    private static String redact(String content) {
        String normalized = content.replace("\r\n", "\n").replace('\r', '\n');
        StringBuilder result = new StringBuilder();
        boolean privateKey = false;
        for (String line : normalized.split("\n", -1)) {
            if (line.contains("-----BEGIN") && line.contains("PRIVATE KEY")) privateKey = true;
            if (privateKey || SECRET_LINE.matcher(line).matches()) {
                result.append("[REDACTED_SECRET_LINE]");
            } else {
                result.append(INLINE_SECRET.matcher(line).replaceAll("$1[REDACTED]"));
            }
            if (line.contains("-----END") && line.contains("PRIVATE KEY")) privateKey = false;
            result.append('\n');
        }
        return result.toString();
    }

    private String write(Object value) {
        try { return json.writeValueAsString(value); }
        catch (Exception error) { throw new IllegalStateException("Project intake JSON failed", error); }
    }

    private static String safeCode(String value) {
        return value != null && value.matches("[A-Z0-9_]{1,120}")
                ? value : "PROJECT_INTAKE_FAILED";
    }

    private static String sha256(String value) {
        try {
            return "sha256:" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception error) {
            throw new IllegalStateException("Unable to hash Project intake input", error);
        }
    }

    private record Selection(
            String modelPoolId, String providerId, String modelId,
            List<InferenceExecutionApi.InferenceCandidate> candidates, String strategy,
            String snapshotHash, boolean fallbackEnabled) {
    }
}

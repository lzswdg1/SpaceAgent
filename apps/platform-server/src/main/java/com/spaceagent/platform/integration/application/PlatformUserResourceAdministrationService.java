package com.spaceagent.platform.integration.application;

import com.spaceagent.platform.automation.api.AutomationSystemAdministrationApi;
import com.spaceagent.platform.conversation.api.ConversationSystemAdministrationApi;
import com.spaceagent.platform.inference.api.InferenceSystemAdministrationApi;
import com.spaceagent.platform.knowledge.api.KnowledgeSystemAdministrationApi;
import com.spaceagent.platform.memory.api.MemorySystemAdministrationApi;
import com.spaceagent.platform.project.api.ProjectSystemAdministrationApi;
import com.spaceagent.platform.runtime.api.RuntimeSystemAdministrationApi;
import com.spaceagent.platform.shared.api.SystemAdministrationPage;
import com.spaceagent.platform.shared.api.UserResourceSummary;
import com.spaceagent.platform.tooling.api.ToolingSystemAdministrationApi;
import com.spaceagent.shared.exception.BusinessException;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.util.Locale;
import java.util.Set;

@Service
@ConditionalOnProperty(prefix = "platform", name = "persistence", havingValue = "postgres")
public class PlatformUserResourceAdministrationService {
    public static final Set<String> KINDS = Set.of(
            "MODEL_POOL", "PROJECT", "TASK", "WORKSPACE", "CONVERSATION", "MCP_CONNECTION",
            "KNOWLEDGE_DOCUMENT", "MEMORY", "AUTOMATION", "RUN", "MODEL_EFFECT", "TOOL_EFFECT");

    private final InferenceSystemAdministrationApi inference;
    private final ProjectSystemAdministrationApi project;
    private final ConversationSystemAdministrationApi conversation;
    private final ToolingSystemAdministrationApi tooling;
    private final KnowledgeSystemAdministrationApi knowledge;
    private final MemorySystemAdministrationApi memory;
    private final AutomationSystemAdministrationApi automation;
    private final RuntimeSystemAdministrationApi runtime;

    public PlatformUserResourceAdministrationService(
            InferenceSystemAdministrationApi inference,
            ProjectSystemAdministrationApi project,
            ConversationSystemAdministrationApi conversation,
            ToolingSystemAdministrationApi tooling,
            KnowledgeSystemAdministrationApi knowledge,
            MemorySystemAdministrationApi memory,
            AutomationSystemAdministrationApi automation,
            RuntimeSystemAdministrationApi runtime) {
        this.inference = inference;
        this.project = project;
        this.conversation = conversation;
        this.tooling = tooling;
        this.knowledge = knowledge;
        this.memory = memory;
        this.automation = automation;
        this.runtime = runtime;
    }

    public SystemAdministrationPage<UserResourceSummary> resources(
            String userId, String kind, int page, int pageSize) {
        return switch (kind(kind)) {
            case "MODEL_POOL" -> inference.modelPoolsByOwner(userId, page, pageSize);
            case "PROJECT" -> project.projectsByOwner(userId, page, pageSize);
            case "TASK" -> project.tasksByOwner(userId, page, pageSize);
            case "WORKSPACE" -> project.workspacesByOwner(userId, page, pageSize);
            case "CONVERSATION" -> conversation.conversationsByOwner(userId, page, pageSize);
            case "MCP_CONNECTION" -> tooling.mcpConnectionsByManager(userId, page, pageSize);
            case "KNOWLEDGE_DOCUMENT" -> knowledge.documentsByOwner(userId, page, pageSize);
            case "MEMORY" -> memory.memoriesByUser(userId, page, pageSize);
            case "AUTOMATION" -> automation.schedulesByOwner(userId, page, pageSize);
            case "RUN" -> runtime.runsByOwner(userId, page, pageSize);
            case "MODEL_EFFECT" -> inference.modelEffectsByOwner(userId, page, pageSize);
            case "TOOL_EFFECT" -> tooling.toolEffectsByOwner(userId, page, pageSize);
            default -> throw invalidKind();
        };
    }

    public UserResourceOverview overview(String userId) {
        return new UserResourceOverview(
                total(userId, "MODEL_POOL"), total(userId, "PROJECT"), total(userId, "TASK"),
                total(userId, "WORKSPACE"), total(userId, "CONVERSATION"),
                total(userId, "MCP_CONNECTION"), total(userId, "KNOWLEDGE_DOCUMENT"),
                total(userId, "MEMORY"), total(userId, "AUTOMATION"), total(userId, "RUN"),
                total(userId, "MODEL_EFFECT"), total(userId, "TOOL_EFFECT"));
    }

    private long total(String userId, String kind) {
        return resources(userId, kind, 0, 1).total();
    }

    private static String kind(String value) {
        if (value == null || value.isBlank()) throw invalidKind();
        String normalized = value.trim().toUpperCase(Locale.ROOT);
        if (!KINDS.contains(normalized)) throw invalidKind();
        return normalized;
    }

    private static BusinessException invalidKind() {
        return new BusinessException("User resource kind is invalid", HttpStatus.BAD_REQUEST,
                "SYSTEM_ADMIN_USER_RESOURCE_KIND_INVALID");
    }

    public record UserResourceOverview(
            long modelPools, long projects, long tasks, long workspaces, long conversations,
            long mcpConnections, long knowledgeDocuments, long memories, long automations,
            long runs, long modelEffects, long toolEffects) {
    }
}

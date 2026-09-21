package com.spaceagent.platform.tooling.api;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Read-only catalog of capabilities that the active Runtime can actually execute. */
public interface RuntimeCapabilityCatalogApplicationApi {

    CapabilityCatalogView catalog();

    boolean supportsTool(String toolId);

    boolean supportsSkill(String skillId);

    default boolean supportsSkill(String skillVersionId, String tenantId) {
        return supportsSkill(skillVersionId);
    }

    void validateSkillBindings(
            String tenantId, List<String> skillVersionIds, List<String> enabledToolIds);

    List<SkillCapabilityView> resolvePinnedSkills(
            String tenantId, List<String> skillVersionIds, List<String> enabledToolIds);

    Optional<CapabilityView> findTool(String toolId);

    List<CapabilityView> resolveTools(List<String> toolIds);

    ValidatedToolArguments validateArguments(String toolId, String argumentsJson);

    record CapabilityCatalogView(
            List<CapabilityView> tools,
            List<CapabilityView> skills,
            SandboxCapabilityView sandbox) {

        public CapabilityCatalogView {
            tools = tools == null ? List.of() : List.copyOf(tools);
            skills = skills == null ? List.of() : List.copyOf(skills);
            sandbox = sandbox == null ? SandboxCapabilityView.compatibility() : sandbox;
        }

        public CapabilityCatalogView(List<CapabilityView> tools, List<CapabilityView> skills) {
            this(tools, skills, SandboxCapabilityView.compatibility());
        }
    }

    /** Secret-free deployment evidence; sandbox mode is never browser mutable. */
    record SandboxCapabilityView(
            String mode,
            String isolation,
            boolean containerized,
            boolean codingCommandAvailable) {

        public static SandboxCapabilityView compatibility() {
            return new SandboxCapabilityView(
                    "IN_PROCESS", "PROCESS_COMPATIBILITY", false, false);
        }
    }

    record CapabilityView(
            String id,
            String name,
            String description,
            String executionMode,
            Map<String, Object> inputSchema,
            boolean available,
            boolean readOnly,
            boolean requiresNetwork,
            boolean requiresWorkspace) {

        public CapabilityView {
            inputSchema = inputSchema == null ? Map.of() : Map.copyOf(inputSchema);
        }
    }

    record ValidatedToolArguments(
            String canonicalToolId,
            Map<String, Object> arguments) {

        public ValidatedToolArguments {
            arguments = arguments == null ? Map.of() : Map.copyOf(arguments);
        }
    }

    record SkillCapabilityView(
            String id,
            String skillId,
            String name,
            int versionNumber,
            String configHash,
            String instructions,
            List<String> requiredToolIds) {

        public SkillCapabilityView {
            requiredToolIds = requiredToolIds == null ? List.of() : List.copyOf(requiredToolIds);
        }
    }
}

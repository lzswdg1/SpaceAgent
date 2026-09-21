package com.spaceagent.platform.tooling.application;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.networknt.schema.Schema;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SpecificationVersion;
import com.spaceagent.platform.tooling.api.RuntimeCapabilityCatalogApplicationApi;
import com.spaceagent.platform.tooling.domain.WebSearchGateway;
import com.spaceagent.platform.tooling.domain.SkillLifecycle;
import com.spaceagent.platform.tooling.domain.SkillRegistryRepository;
import com.spaceagent.platform.tooling.domain.SkillVersionStatus;
import com.spaceagent.platform.tooling.infrastructure.SandboxProperties;
import com.spaceagent.shared.exception.BusinessException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

@Service
public class RuntimeCapabilityCatalogApplicationService
        implements RuntimeCapabilityCatalogApplicationApi {
    private static final int MAX_ARGUMENT_JSON_BYTES = 1_000_000;
    private static final int MAX_BOUND_SKILLS = 16;
    private static final int MAX_COMBINED_SKILL_INSTRUCTION_BYTES = 256 * 1024;
    private static final Map<String, String> ALIASES = Map.of("tool:echo", "echo");

    private final ObjectMapper json;
    private final Map<String, CapabilityView> tools;
    private final Map<String, Schema> validators;
    private final SandboxCapabilityView sandbox;
    private final SkillRegistryRepository skillRepository;

    @Autowired
    public RuntimeCapabilityCatalogApplicationService(
            ObjectMapper json,
            WebSearchGateway webSearchGateway,
            SandboxProperties sandboxProperties,
            SkillRegistryRepository skillRepository) {
        this.json = json;
        boolean containerizedSandbox = "http".equalsIgnoreCase(sandboxProperties.getMode());
        List<CapabilityView> definitions = RuntimeToolDefinitions.all(
                webSearchGateway.available(), containerizedSandbox);
        Map<String, CapabilityView> indexed = new LinkedHashMap<>();
        Map<String, Schema> compiled = new LinkedHashMap<>();
        SchemaRegistry registry = SchemaRegistry.withDefaultDialect(
                SpecificationVersion.DRAFT_2020_12);
        for (CapabilityView definition : definitions) {
            String id = normalized(definition.id());
            if (indexed.put(id, definition) != null) {
                throw new IllegalStateException("Duplicate Runtime Tool definition: " + id);
            }
            compiled.put(id, registry.getSchema(json.valueToTree(definition.inputSchema())));
        }
        this.tools = Collections.unmodifiableMap(new LinkedHashMap<>(indexed));
        this.validators = Collections.unmodifiableMap(new LinkedHashMap<>(compiled));
        this.sandbox = containerizedSandbox
                ? new SandboxCapabilityView("HTTP", "OCI_CONTAINER", true, true)
                : SandboxCapabilityView.compatibility();
        this.skillRepository = skillRepository;
    }

    public RuntimeCapabilityCatalogApplicationService(
            ObjectMapper json,
            WebSearchGateway webSearchGateway) {
        this(json, webSearchGateway, new SandboxProperties(), null);
    }

    public RuntimeCapabilityCatalogApplicationService(
            ObjectMapper json,
            WebSearchGateway webSearchGateway,
            SandboxProperties sandboxProperties) {
        this(json, webSearchGateway, sandboxProperties, null);
    }

    @Override
    public CapabilityCatalogView catalog() {
        return new CapabilityCatalogView(List.copyOf(tools.values()), List.of(), sandbox);
    }

    @Override
    public boolean supportsTool(String toolId) {
        return findTool(toolId).map(CapabilityView::available).orElse(false);
    }

    @Override
    public boolean supportsSkill(String skillId) {
        return false;
    }

    @Override
    public boolean supportsSkill(String skillVersionId, String tenantId) {
        if (skillRepository == null || skillVersionId == null || tenantId == null) return false;
        return skillRepository.findVersion(skillVersionId)
                .filter(version -> version.status() == SkillVersionStatus.PUBLISHED)
                .flatMap(version -> skillRepository.findDefinition(version.skillId()))
                .filter(definition -> definition.lifecycle() == SkillLifecycle.ACTIVE)
                .filter(definition -> tenantId.equals(definition.tenantId()))
                .filter(definition -> skillVersionId.equals(definition.currentVersionId()))
                .isPresent();
    }

    @Override
    public void validateSkillBindings(
            String tenantId, List<String> skillVersionIds, List<String> enabledToolIds) {
        resolveSkills(tenantId, skillVersionIds, enabledToolIds, false);
    }

    @Override
    public List<SkillCapabilityView> resolvePinnedSkills(
            String tenantId, List<String> skillVersionIds, List<String> enabledToolIds) {
        return resolveSkills(tenantId, skillVersionIds, enabledToolIds, true);
    }

    @Override
    public Optional<CapabilityView> findTool(String toolId) {
        if (toolId == null || toolId.isBlank()) return Optional.empty();
        return Optional.ofNullable(tools.get(canonical(toolId)));
    }

    @Override
    public List<CapabilityView> resolveTools(List<String> toolIds) {
        if (toolIds == null || toolIds.isEmpty()) return List.of();
        LinkedHashSet<String> resolved = new LinkedHashSet<>();
        for (String toolId : toolIds) {
            findTool(toolId).filter(CapabilityView::available)
                    .ifPresent(value -> resolved.add(value.id()));
        }
        return resolved.stream().map(tools::get).toList();
    }

    private List<SkillCapabilityView> resolveSkills(
            String tenantId,
            List<String> skillVersionIds,
            List<String> enabledToolIds,
            boolean allowHistoricalPublishedVersion) {
        if (skillVersionIds == null || skillVersionIds.isEmpty()) return List.of();
        if (skillRepository == null) {
            throw business("Runtime Skill registry is unavailable",
                    "SKILL_REGISTRY_UNAVAILABLE", HttpStatus.SERVICE_UNAVAILABLE);
        }
        List<String> ids = skillVersionIds.stream()
                .filter(value -> value != null && !value.isBlank())
                .map(String::trim).distinct().sorted().toList();
        if (ids.size() != skillVersionIds.size() || ids.size() > MAX_BOUND_SKILLS) {
            throw business("Agent Skill bindings are invalid",
                    "AGENT_SKILL_BINDINGS_INVALID", HttpStatus.BAD_REQUEST);
        }
        java.util.Set<String> enabled = resolveTools(enabledToolIds).stream()
                .map(CapabilityView::id).collect(java.util.stream.Collectors.toSet());
        List<SkillCapabilityView> result = new java.util.ArrayList<>();
        int instructionBytes = 0;
        for (String id : ids) {
            var version = skillRepository.findVersion(id)
                    .orElseThrow(() -> business(
                            "Runtime Skill version is not registered: " + id,
                            "AGENT_SKILL_NOT_REGISTERED", HttpStatus.BAD_REQUEST));
            if (version.status() != SkillVersionStatus.PUBLISHED
                    && !(allowHistoricalPublishedVersion
                            && version.status() == SkillVersionStatus.DEPRECATED)) {
                throw business("Runtime Skill version is not published: " + id,
                        "AGENT_SKILL_NOT_PUBLISHED", HttpStatus.CONFLICT);
            }
            var definition = skillRepository.findDefinition(version.skillId())
                    .filter(value -> tenantId != null && tenantId.equals(value.tenantId()))
                    .orElseThrow(() -> business(
                            "Runtime Skill version is not visible: " + id,
                            "AGENT_SKILL_NOT_VISIBLE", HttpStatus.NOT_FOUND));
            if (!allowHistoricalPublishedVersion
                    && (definition.lifecycle() != SkillLifecycle.ACTIVE
                            || !id.equals(definition.currentVersionId()))) {
                throw business("Agent must bind the current published Skill version: " + id,
                        "AGENT_SKILL_NOT_CURRENT", HttpStatus.CONFLICT);
            }
            List<String> required = version.requiredToolIds().stream()
                    .map(value -> findTool(value).map(CapabilityView::id).orElse(value))
                    .distinct().sorted().toList();
            required.stream().filter(value -> !enabled.contains(value)).findFirst()
                    .ifPresent(value -> {
                        throw business("Skill requires an Agent Tool that is not enabled: " + value,
                                "AGENT_SKILL_TOOL_NOT_ENABLED", HttpStatus.CONFLICT);
                    });
            instructionBytes += version.instructions()
                    .getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
            if (instructionBytes > MAX_COMBINED_SKILL_INSTRUCTION_BYTES) {
                throw business("Combined Skill instructions exceed the Runtime limit",
                        "AGENT_SKILL_CONTEXT_TOO_LARGE", HttpStatus.PAYLOAD_TOO_LARGE);
            }
            result.add(new SkillCapabilityView(
                    version.id(), definition.id(), definition.name(), version.versionNumber(),
                    version.configHash(), version.instructions(), required));
        }
        return List.copyOf(result);
    }

    @Override
    public ValidatedToolArguments validateArguments(String toolId, String argumentsJson) {
        CapabilityView definition = findTool(toolId).orElseThrow(() -> business(
                "Runtime Tool is not registered", "TOOL_NOT_REGISTERED", HttpStatus.BAD_REQUEST));
        if (!definition.available()) {
            throw business(
                    "Runtime Tool is not available in this deployment",
                    "TOOL_UNAVAILABLE", HttpStatus.SERVICE_UNAVAILABLE);
        }
        String raw = argumentsJson == null || argumentsJson.isBlank() ? "{}" : argumentsJson;
        if (raw.getBytes(java.nio.charset.StandardCharsets.UTF_8).length
                > MAX_ARGUMENT_JSON_BYTES) {
            throw business(
                    "Tool arguments exceed the allowed size",
                    "TOOL_ARGUMENTS_TOO_LARGE", HttpStatus.BAD_REQUEST);
        }
        JsonNode node;
        try {
            node = json.readTree(raw);
        } catch (Exception error) {
            throw business(
                    "Tool arguments must be valid JSON",
                    "TOOL_ARGUMENTS_INVALID", HttpStatus.BAD_REQUEST);
        }
        if (node == null || !node.isObject()
                || !validators.get(definition.id()).validate(node).isEmpty()) {
            throw business(
                    "Tool arguments failed JSON Schema validation",
                    "TOOL_ARGUMENTS_INVALID", HttpStatus.BAD_REQUEST);
        }
        Map<String, Object> arguments = json.convertValue(
                node, new TypeReference<Map<String, Object>>() { });
        return new ValidatedToolArguments(definition.id(), arguments);
    }

    private static String canonical(String value) {
        String normalized = normalized(value);
        return ALIASES.getOrDefault(normalized, normalized);
    }

    private static String normalized(String value) {
        return value.trim().toLowerCase(Locale.ROOT);
    }

    private static BusinessException business(
            String message, String code, HttpStatus status) {
        return new BusinessException(message, status, code);
    }
}

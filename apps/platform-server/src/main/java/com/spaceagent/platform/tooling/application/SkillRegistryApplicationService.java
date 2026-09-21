package com.spaceagent.platform.tooling.application;

import com.spaceagent.platform.tooling.api.RuntimeCapabilityCatalogApplicationApi;
import com.spaceagent.platform.tooling.api.SkillRegistryApplicationApi;
import com.spaceagent.platform.tooling.domain.SkillDefinition;
import com.spaceagent.platform.tooling.domain.SkillLifecycle;
import com.spaceagent.platform.tooling.domain.SkillRegistryRepository;
import com.spaceagent.platform.tooling.domain.SkillVersion;
import com.spaceagent.platform.tooling.domain.SkillVersionStatus;
import com.spaceagent.shared.exception.BusinessException;
import com.spaceagent.shared.id.IdGenerator;
import com.spaceagent.shared.time.TimeProvider;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;

@Service
public class SkillRegistryApplicationService implements SkillRegistryApplicationApi {
    private static final int MAX_INSTRUCTION_BYTES = 128 * 1024;
    private static final int MAX_REQUIRED_TOOLS = 32;

    private final SkillRegistryRepository repository;
    private final RuntimeCapabilityCatalogApplicationApi capabilityCatalog;
    private final IdGenerator idGenerator;
    private final TimeProvider timeProvider;

    public SkillRegistryApplicationService(
            SkillRegistryRepository repository,
            RuntimeCapabilityCatalogApplicationApi capabilityCatalog,
            IdGenerator idGenerator,
            TimeProvider timeProvider) {
        this.repository = repository;
        this.capabilityCatalog = capabilityCatalog;
        this.idGenerator = idGenerator;
        this.timeProvider = timeProvider;
    }

    @Override
    @Transactional
    public SkillView create(CreateSkillCommand command) {
        requireScope(command.tenantId(), command.userId());
        String name = normalizedName(command.name());
        if (repository.findDefinitions(command.tenantId()).stream()
                .anyMatch(value -> value.lifecycle() == SkillLifecycle.ACTIVE
                        && value.name().equalsIgnoreCase(name))) {
            throw business("Skill name already exists", "SKILL_NAME_CONFLICT", HttpStatus.CONFLICT);
        }
        SkillContent content = content(command.instructions(), command.requiredToolIds());
        var now = timeProvider.now();
        SkillDefinition definition = new SkillDefinition(
                idGenerator.nextId(), command.tenantId(), command.userId(), name,
                boundedDescription(command.description()), SkillLifecycle.ACTIVE,
                null, 1L, now, now, null);
        SkillVersion version = draft(
                definition.id(), 1, command.userId(), now, content);
        repository.saveDefinition(definition);
        repository.insertVersion(version);
        return view(definition);
    }

    @Override
    @Transactional
    public SkillVersionView createVersion(CreateSkillVersionCommand command) {
        SkillDefinition definition = ownedForUpdate(
                command.tenantId(), command.userId(), command.skillId());
        if (definition.lifecycle() != SkillLifecycle.ACTIVE) {
            throw business("Skill is archived", "SKILL_ARCHIVED", HttpStatus.CONFLICT);
        }
        SkillContent content = content(command.instructions(), command.requiredToolIds());
        SkillVersion version = draft(
                definition.id(), repository.nextVersionNumber(definition.id()),
                command.userId(), timeProvider.now(), content);
        repository.insertVersion(version);
        return versionView(version);
    }

    @Override
    @Transactional
    public SkillView publish(SkillVersionLifecycleCommand command) {
        SkillDefinition definition = ownedForUpdate(
                command.tenantId(), command.userId(), command.skillId());
        SkillVersion version = requireVersion(definition.id(), command.skillVersionId());
        var now = timeProvider.now();
        if (definition.currentVersionId() != null
                && !definition.currentVersionId().equals(version.id())) {
            SkillVersion previous = repository.findVersion(definition.currentVersionId())
                    .orElseThrow(() -> new IllegalStateException("Current SkillVersion is missing"));
            if (previous.status() == SkillVersionStatus.PUBLISHED) {
                repository.updateVersionLifecycle(previous.deprecate(command.userId(), now));
            }
        }
        SkillVersion published;
        try {
            published = version.publish(command.userId(), now);
        } catch (IllegalStateException error) {
            throw business(error.getMessage(), "SKILL_VERSION_STATE_CONFLICT", HttpStatus.CONFLICT);
        }
        repository.updateVersionLifecycle(published);
        repository.saveDefinition(definition.publish(published.id(), now));
        return view(repository.findDefinition(definition.id()).orElseThrow());
    }

    @Override
    @Transactional
    public SkillView deprecate(SkillVersionLifecycleCommand command) {
        SkillDefinition definition = ownedForUpdate(
                command.tenantId(), command.userId(), command.skillId());
        SkillVersion version = requireVersion(definition.id(), command.skillVersionId());
        SkillVersion deprecated;
        try {
            deprecated = version.deprecate(command.userId(), timeProvider.now());
        } catch (IllegalStateException error) {
            throw business(error.getMessage(), "SKILL_VERSION_STATE_CONFLICT", HttpStatus.CONFLICT);
        }
        repository.updateVersionLifecycle(deprecated);
        if (version.id().equals(definition.currentVersionId())) {
            repository.saveDefinition(definition.clearCurrentVersion(timeProvider.now()));
        }
        return view(repository.findDefinition(definition.id()).orElseThrow());
    }

    @Override
    @Transactional
    public void archive(ArchiveSkillCommand command) {
        SkillDefinition definition = ownedForUpdate(
                command.tenantId(), command.userId(), command.skillId());
        repository.saveDefinition(definition.archive(timeProvider.now()));
    }

    @Override
    @Transactional(readOnly = true)
    public SkillView get(String tenantId, String userId, String skillId) {
        requireScope(tenantId, userId);
        return view(visible(tenantId, skillId));
    }

    @Override
    @Transactional(readOnly = true)
    public List<SkillView> list(String tenantId, String userId) {
        requireScope(tenantId, userId);
        return repository.findDefinitions(tenantId).stream().map(this::view).toList();
    }

    private SkillDefinition visible(String tenantId, String skillId) {
        return repository.findDefinition(skillId)
                .filter(value -> tenantId.equals(value.tenantId()))
                .orElseThrow(() -> business("Skill not found", "SKILL_NOT_FOUND", HttpStatus.NOT_FOUND));
    }

    private SkillDefinition ownedForUpdate(String tenantId, String userId, String skillId) {
        requireScope(tenantId, userId);
        return repository.findDefinitionForUpdate(skillId)
                .filter(value -> tenantId.equals(value.tenantId()))
                .filter(value -> userId.equals(value.ownerUserId()))
                .orElseThrow(() -> business("Skill not found", "SKILL_NOT_FOUND", HttpStatus.NOT_FOUND));
    }

    private SkillVersion requireVersion(String skillId, String versionId) {
        return repository.findVersion(versionId)
                .filter(value -> skillId.equals(value.skillId()))
                .orElseThrow(() -> business(
                        "SkillVersion not found", "SKILL_VERSION_NOT_FOUND", HttpStatus.NOT_FOUND));
    }

    private SkillContent content(String instructions, List<String> toolIds) {
        String normalizedInstructions = instructions == null ? "" : instructions.trim();
        if (normalizedInstructions.isBlank()) {
            throw business("Skill instructions are required", "SKILL_INSTRUCTIONS_REQUIRED",
                    HttpStatus.BAD_REQUEST);
        }
        if (normalizedInstructions.getBytes(StandardCharsets.UTF_8).length > MAX_INSTRUCTION_BYTES) {
            throw business("Skill instructions are too large", "SKILL_INSTRUCTIONS_TOO_LARGE",
                    HttpStatus.PAYLOAD_TOO_LARGE);
        }
        List<String> normalizedTools = toolIds == null ? List.of() : toolIds.stream()
                .filter(value -> value != null && !value.isBlank())
                .map(String::trim)
                .map(value -> capabilityCatalog.findTool(value)
                        .map(RuntimeCapabilityCatalogApplicationApi.CapabilityView::id)
                        .orElseThrow(() -> business("Runtime Tool is not registered: " + value,
                                "SKILL_TOOL_NOT_REGISTERED", HttpStatus.BAD_REQUEST)))
                .distinct().sorted().toList();
        if (normalizedTools.size() > MAX_REQUIRED_TOOLS) {
            throw business("Skill requires too many Tools", "SKILL_TOOL_LIMIT_EXCEEDED",
                    HttpStatus.BAD_REQUEST);
        }
        String canonical = normalizedInstructions + "\n" + String.join("\n", normalizedTools);
        return new SkillContent(normalizedInstructions, normalizedTools, sha256(canonical));
    }

    private SkillVersion draft(
            String skillId, int number, String userId, java.time.Instant now,
            SkillContent content) {
        return new SkillVersion(
                idGenerator.nextId(), skillId, number, SkillVersionStatus.DRAFT,
                content.hash(), content.instructions(), content.requiredToolIds(),
                userId, now, null, null, null, null);
    }

    private SkillView view(SkillDefinition definition) {
        return new SkillView(
                definition.id(), definition.tenantId(), definition.ownerUserId(), definition.name(),
                definition.description(), definition.lifecycle().name(), definition.currentVersionId(),
                definition.revision(), definition.createdAt(), definition.updatedAt(),
                definition.archivedAt(), repository.findVersions(definition.id()).stream()
                        .map(this::versionView).toList());
    }

    private SkillVersionView versionView(SkillVersion value) {
        return new SkillVersionView(
                value.id(), value.skillId(), value.versionNumber(), value.status().name(),
                value.configHash(), value.instructions(), value.requiredToolIds(), value.createdBy(),
                value.createdAt(), value.publishedBy(), value.publishedAt(),
                value.deprecatedBy(), value.deprecatedAt());
    }

    private static String normalizedName(String value) {
        String name = value == null ? "" : value.trim();
        if (name.isBlank() || name.length() > 100) {
            throw business("Skill name must be between 1 and 100 characters",
                    "SKILL_NAME_INVALID", HttpStatus.BAD_REQUEST);
        }
        return name;
    }

    private static String boundedDescription(String value) {
        if (value == null) return null;
        String description = value.trim();
        if (description.length() > 1_000) {
            throw business("Skill description is too large", "SKILL_DESCRIPTION_TOO_LARGE",
                    HttpStatus.BAD_REQUEST);
        }
        return description;
    }

    private static void requireScope(String tenantId, String userId) {
        if (tenantId == null || tenantId.isBlank() || userId == null || userId.isBlank()) {
            throw business("Skill scope is required", "SKILL_SCOPE_REQUIRED", HttpStatus.BAD_REQUEST);
        }
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception error) {
            throw new IllegalStateException("SHA-256 is unavailable", error);
        }
    }

    private static BusinessException business(String message, String code, HttpStatus status) {
        return new BusinessException(message, status, code);
    }

    private record SkillContent(
            String instructions, List<String> requiredToolIds, String hash) { }
}

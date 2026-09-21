package com.spaceagent.platform.tooling.infrastructure.memory;

import com.spaceagent.platform.tooling.domain.SkillDefinition;
import com.spaceagent.platform.tooling.domain.SkillRegistryRepository;
import com.spaceagent.platform.tooling.domain.SkillVersion;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Repository;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

@Repository
@ConditionalOnProperty(
        prefix = "platform", name = "persistence", havingValue = "memory", matchIfMissing = true)
public class InMemorySkillRegistryRepository implements SkillRegistryRepository {
    private final Map<String, SkillDefinition> definitions = new ConcurrentHashMap<>();
    private final Map<String, SkillVersion> versions = new ConcurrentHashMap<>();

    @Override
    public Optional<SkillDefinition> findDefinition(String id) {
        return Optional.ofNullable(definitions.get(id));
    }

    @Override
    public Optional<SkillDefinition> findDefinitionForUpdate(String id) {
        return findDefinition(id);
    }

    @Override
    public List<SkillDefinition> findDefinitions(String tenantId) {
        return definitions.values().stream()
                .filter(value -> tenantId.equals(value.tenantId()))
                .sorted(Comparator.comparing(SkillDefinition::createdAt).reversed()
                        .thenComparing(SkillDefinition::id))
                .limit(100)
                .toList();
    }

    @Override
    public void saveDefinition(SkillDefinition value) {
        definitions.put(value.id(), value);
    }

    @Override
    public Optional<SkillVersion> findVersion(String id) {
        return Optional.ofNullable(versions.get(id));
    }

    @Override
    public List<SkillVersion> findVersions(String skillId) {
        return versions.values().stream()
                .filter(value -> skillId.equals(value.skillId()))
                .sorted(Comparator.comparingInt(SkillVersion::versionNumber).reversed())
                .limit(100)
                .toList();
    }

    @Override
    public int nextVersionNumber(String skillId) {
        return findVersions(skillId).stream()
                .mapToInt(SkillVersion::versionNumber).max().orElse(0) + 1;
    }

    @Override
    public void insertVersion(SkillVersion value) {
        if (versions.putIfAbsent(value.id(), value) != null) {
            throw new IllegalStateException("SkillVersion already exists");
        }
    }

    @Override
    public void updateVersionLifecycle(SkillVersion value) {
        versions.compute(value.id(), (id, current) -> {
            if (current == null) throw new IllegalStateException("SkillVersion not found");
            if (!current.configHash().equals(value.configHash())) {
                throw new IllegalStateException("SkillVersion content is immutable");
            }
            return value;
        });
    }
}

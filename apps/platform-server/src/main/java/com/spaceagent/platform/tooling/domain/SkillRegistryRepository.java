package com.spaceagent.platform.tooling.domain;

import java.util.List;
import java.util.Optional;

public interface SkillRegistryRepository {

    Optional<SkillDefinition> findDefinition(String id);

    Optional<SkillDefinition> findDefinitionForUpdate(String id);

    List<SkillDefinition> findDefinitions(String tenantId);

    void saveDefinition(SkillDefinition value);

    Optional<SkillVersion> findVersion(String id);

    List<SkillVersion> findVersions(String skillId);

    int nextVersionNumber(String skillId);

    void insertVersion(SkillVersion value);

    void updateVersionLifecycle(SkillVersion value);
}

package com.spaceagent.platform.tooling.infrastructure.persistence;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.spaceagent.platform.tooling.domain.SkillDefinition;
import com.spaceagent.platform.tooling.domain.SkillLifecycle;
import com.spaceagent.platform.tooling.domain.SkillRegistryRepository;
import com.spaceagent.platform.tooling.domain.SkillVersion;
import com.spaceagent.platform.tooling.domain.SkillVersionStatus;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.List;
import java.util.Optional;

@Repository
@ConditionalOnProperty(prefix = "platform", name = "persistence", havingValue = "postgres")
public class PostgresSkillRegistryRepository implements SkillRegistryRepository {
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;

    public PostgresSkillRegistryRepository(JdbcTemplate jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    @Override
    public Optional<SkillDefinition> findDefinition(String id) {
        return jdbc.query("""
                SELECT * FROM platform_skill_definitions WHERE id = CAST(? AS UUID)
                """, this::definition, id).stream().findFirst();
    }

    @Override
    public Optional<SkillDefinition> findDefinitionForUpdate(String id) {
        return jdbc.query("""
                SELECT * FROM platform_skill_definitions
                 WHERE id = CAST(? AS UUID) FOR UPDATE
                """, this::definition, id).stream().findFirst();
    }

    @Override
    public List<SkillDefinition> findDefinitions(String tenantId) {
        return jdbc.query("""
                SELECT * FROM platform_skill_definitions
                 WHERE tenant_id = ?
                 ORDER BY created_at DESC, id
                 LIMIT 100
                """, this::definition, tenantId);
    }

    @Override
    public void saveDefinition(SkillDefinition value) {
        jdbc.update("""
                INSERT INTO platform_skill_definitions(
                    id, tenant_id, owner_user_id, name, description, lifecycle_state,
                    current_version_id, revision, created_at, updated_at, archived_at)
                VALUES(CAST(? AS UUID), ?, ?, ?, ?, ?,
                    CAST(? AS UUID), ?, ?, ?, ?)
                ON CONFLICT (id) DO UPDATE SET
                    name = EXCLUDED.name,
                    description = EXCLUDED.description,
                    lifecycle_state = EXCLUDED.lifecycle_state,
                    current_version_id = EXCLUDED.current_version_id,
                    revision = EXCLUDED.revision,
                    updated_at = EXCLUDED.updated_at,
                    archived_at = EXCLUDED.archived_at
                """, value.id(), value.tenantId(), value.ownerUserId(), value.name(),
                value.description(), value.lifecycle().name(), value.currentVersionId(),
                value.revision(), Timestamp.from(value.createdAt()), Timestamp.from(value.updatedAt()),
                timestamp(value.archivedAt()));
    }

    @Override
    public Optional<SkillVersion> findVersion(String id) {
        return jdbc.query("""
                SELECT * FROM platform_skill_versions WHERE id = CAST(? AS UUID)
                """, this::version, id).stream().findFirst();
    }

    @Override
    public List<SkillVersion> findVersions(String skillId) {
        return jdbc.query("""
                SELECT * FROM platform_skill_versions
                 WHERE skill_id = CAST(? AS UUID)
                 ORDER BY version_number DESC
                 LIMIT 100
                """, this::version, skillId);
    }

    @Override
    public int nextVersionNumber(String skillId) {
        Integer value = jdbc.queryForObject("""
                SELECT COALESCE(MAX(version_number), 0) + 1
                  FROM platform_skill_versions WHERE skill_id = CAST(? AS UUID)
                """, Integer.class, skillId);
        return value == null ? 1 : value;
    }

    @Override
    public void insertVersion(SkillVersion value) {
        jdbc.update("""
                INSERT INTO platform_skill_versions(
                    id, skill_id, version_number, lifecycle_state, config_hash, instructions,
                    required_tool_ids, created_by, created_at, published_by, published_at,
                    deprecated_by, deprecated_at)
                VALUES(CAST(? AS UUID), CAST(? AS UUID), ?, ?, ?, ?, CAST(? AS JSONB),
                    ?, ?, ?, ?, ?, ?)
                """, value.id(), value.skillId(), value.versionNumber(), value.status().name(),
                value.configHash(), value.instructions(), json(value.requiredToolIds()),
                value.createdBy(), Timestamp.from(value.createdAt()), value.publishedBy(),
                timestamp(value.publishedAt()), value.deprecatedBy(), timestamp(value.deprecatedAt()));
    }

    @Override
    public void updateVersionLifecycle(SkillVersion value) {
        int updated = jdbc.update("""
                UPDATE platform_skill_versions
                   SET lifecycle_state = ?, published_by = ?, published_at = ?,
                       deprecated_by = ?, deprecated_at = ?
                 WHERE id = CAST(? AS UUID) AND config_hash = ?
                """, value.status().name(), value.publishedBy(), timestamp(value.publishedAt()),
                value.deprecatedBy(), timestamp(value.deprecatedAt()), value.id(), value.configHash());
        if (updated != 1) throw new IllegalStateException("SkillVersion lifecycle update failed");
    }

    private SkillDefinition definition(ResultSet rs, int row) throws SQLException {
        return new SkillDefinition(
                rs.getString("id"), rs.getString("tenant_id"), rs.getString("owner_user_id"),
                rs.getString("name"), rs.getString("description"),
                SkillLifecycle.valueOf(rs.getString("lifecycle_state")),
                rs.getString("current_version_id"), rs.getLong("revision"),
                rs.getTimestamp("created_at").toInstant(), rs.getTimestamp("updated_at").toInstant(),
                instant(rs.getTimestamp("archived_at")));
    }

    private SkillVersion version(ResultSet rs, int row) throws SQLException {
        return new SkillVersion(
                rs.getString("id"), rs.getString("skill_id"), rs.getInt("version_number"),
                SkillVersionStatus.valueOf(rs.getString("lifecycle_state")),
                rs.getString("config_hash"), rs.getString("instructions"),
                list(rs.getString("required_tool_ids")), rs.getString("created_by"),
                rs.getTimestamp("created_at").toInstant(), rs.getString("published_by"),
                instant(rs.getTimestamp("published_at")), rs.getString("deprecated_by"),
                instant(rs.getTimestamp("deprecated_at")));
    }

    private String json(List<String> value) {
        try {
            return json.writeValueAsString(value);
        } catch (Exception error) {
            throw new IllegalStateException("Unable to serialize Skill Tool IDs", error);
        }
    }

    private List<String> list(String value) {
        try {
            return json.readValue(value, new TypeReference<>() { });
        } catch (Exception error) {
            throw new IllegalStateException("Unable to read Skill Tool IDs", error);
        }
    }

    private static Timestamp timestamp(java.time.Instant value) {
        return value == null ? null : Timestamp.from(value);
    }

    private static java.time.Instant instant(Timestamp value) {
        return value == null ? null : value.toInstant();
    }
}

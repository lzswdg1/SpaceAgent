package com.spaceagent.platform.knowledge.infrastructure.persistence;

import com.spaceagent.platform.knowledge.domain.KnowledgeBase;
import com.spaceagent.platform.knowledge.domain.KnowledgeBaseGrant;
import com.spaceagent.platform.knowledge.domain.KnowledgeBaseRepository;
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
public class PostgresKnowledgeBaseRepository implements KnowledgeBaseRepository {
    private final JdbcTemplate jdbc;
    public PostgresKnowledgeBaseRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @Override public Optional<KnowledgeBase> find(String id) {
        return jdbc.query("SELECT * FROM platform_knowledge_bases WHERE id=?", this::map, id).stream().findFirst();
    }
    @Override public Optional<KnowledgeBase> lock(String id) {
        return jdbc.query("SELECT * FROM platform_knowledge_bases WHERE id=? FOR UPDATE", this::map, id).stream().findFirst();
    }
    @Override public void insert(KnowledgeBase base) {
        jdbc.update("""
                INSERT INTO platform_knowledge_bases
                    (id, scope, organization_id, owner_id, name, description, state, revision, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, base.id(), base.scope().name(), base.organizationId(), base.ownerId(), base.name(),
                base.description(), base.state().name(), base.revision(), Timestamp.from(base.createdAt()), Timestamp.from(base.updatedAt()));
    }
    @Override public boolean update(KnowledgeBase base, long expected) {
        return jdbc.update("""
                UPDATE platform_knowledge_bases SET name=?, description=?, state=?, revision=?, updated_at=?
                WHERE id=? AND revision=?
                """, base.name(), base.description(), base.state().name(), base.revision(),
                Timestamp.from(base.updatedAt()), base.id(), expected) == 1;
    }
    @Override public KnowledgeBase insertIfAbsent(KnowledgeBase base) {
        jdbc.update("""
                INSERT INTO platform_knowledge_bases
                    (id,scope,organization_id,owner_id,name,description,state,revision,created_at,updated_at)
                VALUES (?,?,?,?,?,?,?,?,?,?) ON CONFLICT(id) DO NOTHING
                """, base.id(), base.scope().name(), base.organizationId(), base.ownerId(), base.name(), base.description(),
                base.state().name(), base.revision(), Timestamp.from(base.createdAt()), Timestamp.from(base.updatedAt()));
        return find(base.id()).orElseThrow();
    }
    @Override public List<KnowledgeBase> listVisible(String actor, String organization, String role, int offset, int limit) {
        return jdbc.query("""
                SELECT b.* FROM platform_knowledge_bases b
                WHERE b.state='ACTIVE' AND (
                    (b.scope='PERSONAL' AND b.owner_id=?) OR
                    (b.scope='ORGANIZATION' AND b.organization_id=? AND
                        (?='OWNER' OR b.owner_id=? OR EXISTS (
                            SELECT 1 FROM platform_knowledge_base_grants g WHERE g.base_id=b.id
                            AND ((g.subject_type='USER' AND g.subject_id=?)
                              OR (g.subject_type='ROLE' AND g.subject_id=?))))))
                ORDER BY b.created_at DESC, b.id LIMIT ? OFFSET ?
                """, this::map, actor, organization, role, actor, actor, role, limit, offset);
    }
    @Override public List<KnowledgeBaseGrant> grants(String baseId) {
        return jdbc.query("""
                SELECT * FROM platform_knowledge_base_grants WHERE base_id=? ORDER BY subject_type, subject_id
                """, (rs, row) -> new KnowledgeBaseGrant(rs.getString("base_id"), rs.getString("organization_id"),
                KnowledgeBaseGrant.SubjectType.valueOf(rs.getString("subject_type")), rs.getString("subject_id"),
                KnowledgeBase.Permission.valueOf(rs.getString("permission")), rs.getString("granted_by"),
                rs.getTimestamp("updated_at").toInstant()), baseId);
    }
    @Override public void putGrant(KnowledgeBaseGrant grant) {
        jdbc.update("""
                INSERT INTO platform_knowledge_base_grants
                    (base_id, organization_id, subject_type, subject_id, permission, granted_by, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (base_id, subject_type, subject_id) DO UPDATE SET
                    permission=EXCLUDED.permission, granted_by=EXCLUDED.granted_by, updated_at=EXCLUDED.updated_at
                """, grant.baseId(), grant.organizationId(), grant.subjectType().name(), grant.subjectId(),
                grant.permission().name(), grant.grantedBy(), Timestamp.from(grant.updatedAt()));
    }
    @Override public void removeGrant(String base, KnowledgeBaseGrant.SubjectType type, String subject) {
        jdbc.update("DELETE FROM platform_knowledge_base_grants WHERE base_id=? AND subject_type=? AND subject_id=?",
                base, type.name(), subject);
    }
    private KnowledgeBase map(ResultSet rs, int row) throws SQLException {
        return new KnowledgeBase(rs.getString("id"), KnowledgeBase.Scope.valueOf(rs.getString("scope")),
                rs.getString("organization_id"), rs.getString("owner_id"), rs.getString("name"),
                rs.getString("description"), KnowledgeBase.State.valueOf(rs.getString("state")),
                rs.getLong("revision"), rs.getTimestamp("created_at").toInstant(), rs.getTimestamp("updated_at").toInstant());
    }
}

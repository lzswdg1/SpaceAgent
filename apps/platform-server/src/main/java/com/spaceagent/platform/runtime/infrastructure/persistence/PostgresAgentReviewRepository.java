package com.spaceagent.platform.runtime.infrastructure.persistence;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.spaceagent.platform.runtime.domain.AgentReview;
import com.spaceagent.platform.runtime.domain.AgentReviewDecision;
import com.spaceagent.platform.runtime.domain.AgentReviewRepository;
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
public class PostgresAgentReviewRepository implements AgentReviewRepository {

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    public PostgresAgentReviewRepository(JdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    @Override
    public void save(AgentReview review) {
        try {
            jdbc.update("""
                    INSERT INTO platform_agent_reviews(
                        id,tenant_id,parent_run_id,child_run_id,reviewer_agent_id,
                        artifact_ids_json,decision,evidence,created_at,decided_at)
                    VALUES(CAST(? AS UUID),?,?,?,?,CAST(? AS JSONB),?,?,?,?)
                    ON CONFLICT(id) DO UPDATE SET
                        decision=EXCLUDED.decision,evidence=EXCLUDED.evidence,
                        decided_at=EXCLUDED.decided_at
                    """,
                    review.id(),
                    review.tenantId(),
                    review.parentRunId(),
                    review.childRunId(),
                    review.reviewerAgentId(),
                    objectMapper.writeValueAsString(review.artifactIds()),
                    review.decision().name(),
                    review.evidence(),
                    Timestamp.from(review.createdAt()),
                    review.decidedAt() == null ? null : Timestamp.from(review.decidedAt()));
        } catch (Exception error) {
            throw new IllegalStateException("Unable to persist Agent review", error);
        }
    }

    @Override
    public Optional<AgentReview> findById(String id) {
        return jdbc.query(
                "SELECT * FROM platform_agent_reviews WHERE id=CAST(? AS UUID)",
                this::map,
                id).stream().findFirst();
    }

    @Override
    public List<AgentReview> findByParentRunId(String parentRunId) {
        return jdbc.query(
                "SELECT * FROM platform_agent_reviews WHERE parent_run_id=? ORDER BY created_at,id",
                this::map,
                parentRunId);
    }

    private AgentReview map(ResultSet result, int row) throws SQLException {
        try {
            return new AgentReview(
                    result.getString("id"),
                    result.getString("tenant_id"),
                    result.getString("parent_run_id"),
                    result.getString("child_run_id"),
                    result.getString("reviewer_agent_id"),
                    objectMapper.readValue(
                            result.getString("artifact_ids_json"),
                            new TypeReference<List<String>>() {}),
                    AgentReviewDecision.valueOf(result.getString("decision")),
                    result.getString("evidence"),
                    result.getTimestamp("created_at").toInstant(),
                    result.getTimestamp("decided_at") == null
                            ? null
                            : result.getTimestamp("decided_at").toInstant());
        } catch (Exception error) {
            throw new SQLException("Unable to read Agent review", error);
        }
    }
}

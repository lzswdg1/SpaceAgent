package com.spaceagent.platform.tooling.infrastructure.persistence;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.spaceagent.platform.tooling.domain.McpRegistryCandidate;
import com.spaceagent.platform.tooling.domain.McpRegistryCompatibility;
import com.spaceagent.platform.tooling.domain.McpRegistryRepository;
import com.spaceagent.platform.tooling.domain.McpRegistryReviewState;
import com.spaceagent.platform.tooling.domain.McpRegistrySnapshot;
import com.spaceagent.platform.tooling.domain.McpRegistrySource;
import com.spaceagent.platform.tooling.domain.McpRegistryStatus;
import com.spaceagent.platform.tooling.domain.McpRegistrySyncJob;
import com.spaceagent.platform.tooling.domain.McpRegistrySyncState;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@Repository
@ConditionalOnProperty(prefix = "platform", name = "persistence", havingValue = "postgres")
public class PostgresMcpRegistryRepository implements McpRegistryRepository {
    private static final String JOB_SELECT = """
            SELECT job.*, source.source_key
              FROM platform_mcp_registry_sync_jobs job
              JOIN platform_mcp_registry_sources source ON source.id = job.source_id
            """;
    private static final String CANDIDATE_SELECT = """
            SELECT candidate.*, source.source_key
              FROM platform_mcp_registry_candidates candidate
              JOIN platform_mcp_registry_sources source ON source.id = candidate.source_id
            """;

    private final JdbcTemplate jdbc;
    private final ObjectMapper json;

    public PostgresMcpRegistryRepository(JdbcTemplate jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    @Override
    public Optional<McpRegistrySource> findSource(String sourceKey) {
        return jdbc.query("SELECT * FROM platform_mcp_registry_sources WHERE source_key = ?",
                this::source, sourceKey).stream().findFirst();
    }

    @Override
    public Optional<McpRegistrySyncJob> findActiveJob(String sourceId) {
        return jdbc.query(JOB_SELECT + """
                 WHERE job.source_id = CAST(? AS UUID) AND job.state IN ('PENDING', 'RUNNING')
                 ORDER BY job.created_at LIMIT 1
                """, this::job, sourceId).stream().findFirst();
    }

    @Override
    public void insertJob(McpRegistrySyncJob value) {
        jdbc.update("""
                INSERT INTO platform_mcp_registry_sync_jobs(
                    id, source_id, requested_by, state, updated_since, watermark_at,
                    fetched_count, snapshot_count, candidate_count, safe_error_code, attempt,
                    claim_owner, claim_token, lease_until, created_at, started_at,
                    updated_at, completed_at)
                VALUES(CAST(? AS UUID), CAST(? AS UUID), CAST(? AS UUID), ?, ?, ?, ?, ?, ?, ?, ?,
                    ?, CAST(? AS UUID), ?, ?, ?, ?, ?)
                """, value.id(), value.sourceId(), value.requestedBy(), value.state().name(),
                timestamp(value.updatedSince()), Timestamp.from(value.watermarkAt()),
                value.fetchedCount(), value.snapshotCount(), value.candidateCount(),
                value.safeErrorCode(), value.attempt(), value.claimOwner(), value.claimToken(),
                timestamp(value.leaseUntil()), Timestamp.from(value.createdAt()),
                timestamp(value.startedAt()), Timestamp.from(value.updatedAt()),
                timestamp(value.completedAt()));
    }

    @Override
    @Transactional
    public Optional<McpRegistrySyncJob> claim(
            String workerId, String claimToken, Instant now, Instant leaseUntil,
            int maximumAttempts) {
        jdbc.update("""
                UPDATE platform_mcp_registry_sync_jobs
                   SET state = 'FAILED', safe_error_code = 'MCP_REGISTRY_ATTEMPTS_EXHAUSTED',
                       claim_owner = NULL, claim_token = NULL, lease_until = NULL,
                       updated_at = ?, completed_at = ?
                 WHERE state = 'RUNNING' AND lease_until <= ? AND attempt >= ?
                """, Timestamp.from(now), Timestamp.from(now), Timestamp.from(now), maximumAttempts);
        List<String> ids = jdbc.query("""
                WITH candidate AS (
                    SELECT id
                      FROM platform_mcp_registry_sync_jobs
                     WHERE (state = 'PENDING' OR (state = 'RUNNING' AND lease_until <= ?))
                       AND attempt < ?
                     ORDER BY created_at, id
                     FOR UPDATE SKIP LOCKED
                     LIMIT 1
                )
                UPDATE platform_mcp_registry_sync_jobs job
                   SET state = 'RUNNING', claim_owner = ?, claim_token = CAST(? AS UUID),
                       lease_until = ?, attempt = attempt + 1,
                       started_at = COALESCE(started_at, ?), updated_at = ?
                  FROM candidate
                 WHERE job.id = candidate.id
                RETURNING job.id::text
                """, (result, row) -> result.getString(1), Timestamp.from(now), maximumAttempts,
                workerId, claimToken, Timestamp.from(leaseUntil), Timestamp.from(now),
                Timestamp.from(now));
        if (ids.isEmpty()) return Optional.empty();
        return jdbc.query(JOB_SELECT + " WHERE job.id = CAST(? AS UUID)",
                this::job, ids.getFirst()).stream().findFirst();
    }

    @Override
    public boolean renewLease(
            String jobId, String claimOwner, String claimToken,
            Instant now, Instant leaseUntil) {
        return jdbc.update("""
                UPDATE platform_mcp_registry_sync_jobs
                   SET lease_until = ?, updated_at = ?
                 WHERE id = CAST(? AS UUID) AND state = 'RUNNING'
                   AND claim_owner = ? AND claim_token = CAST(? AS UUID)
                   AND lease_until > ?
                """, Timestamp.from(leaseUntil), Timestamp.from(now), jobId,
                claimOwner, claimToken, Timestamp.from(now)) == 1;
    }

    @Override
    @Transactional
    public CompletionResult complete(
            String jobId, String claimOwner, String claimToken,
            List<SnapshotImport> snapshots, Instant completedAt) {
        int snapshotCount = 0;
        int candidateCount = 0;
        for (SnapshotImport item : snapshots) {
            McpRegistrySnapshot value = item.snapshot();
            int inserted = jdbc.update("""
                    INSERT INTO platform_mcp_registry_snapshots(
                        id, source_id, sync_job_id, registry_name, registry_version,
                        registry_status, status_message, display_title, description,
                        manifest_schema_uri, repository_uri, manifest_json, manifest_sha256,
                        transports_json, compatibility, compatibility_reason,
                        source_published_at, source_updated_at, fetched_at)
                    VALUES(CAST(? AS UUID), CAST(? AS UUID), CAST(? AS UUID), ?, ?, ?, ?, ?, ?, ?, ?,
                        CAST(? AS JSONB), ?, CAST(? AS JSONB), ?, ?, ?, ?, ?)
                    ON CONFLICT(source_id, registry_name, registry_version, manifest_sha256)
                    DO NOTHING
                    """, item.snapshotId(), value.sourceId(), jobId, value.registryName(),
                    value.registryVersion(), value.registryStatus().name(), value.statusMessage(),
                    value.title(), value.description(), value.manifestSchemaUri(),
                    value.repositoryUri(), value.manifestJson(), value.manifestSha256(),
                    write(value.transports()), value.compatibility().name(),
                    value.compatibilityReason(), timestamp(value.sourcePublishedAt()),
                    timestamp(value.sourceUpdatedAt()), Timestamp.from(value.fetchedAt()));
            snapshotCount += inserted;
            String snapshotId = inserted == 1 ? item.snapshotId() : jdbc.queryForObject("""
                    SELECT id::text FROM platform_mcp_registry_snapshots
                     WHERE source_id = CAST(? AS UUID) AND registry_name = ?
                       AND registry_version = ? AND manifest_sha256 = ?
                    """, String.class, value.sourceId(), value.registryName(),
                    value.registryVersion(), value.manifestSha256());
            candidateCount += jdbc.update("""
                    INSERT INTO platform_mcp_registry_candidates(
                        id, snapshot_id, source_id, registry_name, registry_version,
                        review_state, revision, created_at, updated_at)
                    VALUES(CAST(? AS UUID), CAST(? AS UUID), CAST(? AS UUID), ?, ?,
                        'PENDING_REVIEW', 1, ?, ?)
                    ON CONFLICT(snapshot_id) DO NOTHING
                    """, item.candidateId(), snapshotId, value.sourceId(), value.registryName(),
                    value.registryVersion(), Timestamp.from(completedAt), Timestamp.from(completedAt));
        }

        int updated = jdbc.update("""
                UPDATE platform_mcp_registry_sync_jobs
                   SET state = 'SUCCEEDED', fetched_count = ?, snapshot_count = ?,
                       candidate_count = ?, safe_error_code = NULL, claim_owner = NULL,
                       claim_token = NULL, lease_until = NULL, updated_at = ?, completed_at = ?
                 WHERE id = CAST(? AS UUID) AND state = 'RUNNING' AND claim_owner = ?
                   AND claim_token = CAST(? AS UUID)
                """, snapshots.size(), snapshotCount, candidateCount,
                Timestamp.from(completedAt), Timestamp.from(completedAt), jobId,
                claimOwner, claimToken);
        if (updated != 1) throw new IllegalStateException("MCP Registry synchronization lease was lost");
        jdbc.update("""
                UPDATE platform_mcp_registry_sources source
                   SET last_successful_sync_at = job.watermark_at,
                       revision = source.revision + 1, updated_at = ?
                  FROM platform_mcp_registry_sync_jobs job
                 WHERE job.id = CAST(? AS UUID) AND source.id = job.source_id
                """, Timestamp.from(completedAt), jobId);
        return new CompletionResult(snapshotCount, candidateCount);
    }

    @Override
    public boolean fail(String jobId, String claimOwner, String claimToken,
                        String safeErrorCode, Instant completedAt) {
        return jdbc.update("""
                UPDATE platform_mcp_registry_sync_jobs
                   SET state = 'FAILED', safe_error_code = ?, claim_owner = NULL,
                       claim_token = NULL, lease_until = NULL, updated_at = ?, completed_at = ?
                 WHERE id = CAST(? AS UUID) AND state = 'RUNNING' AND claim_owner = ?
                   AND claim_token = CAST(? AS UUID)
                """, safeErrorCode, Timestamp.from(completedAt), Timestamp.from(completedAt),
                jobId, claimOwner, claimToken) == 1;
    }

    @Override
    public List<McpRegistrySyncJob> findJobs(
            int offset, int limit, McpRegistrySyncState state) {
        if (state == null) {
            return jdbc.query(JOB_SELECT + " ORDER BY job.created_at DESC, job.id LIMIT ? OFFSET ?",
                    this::job, limit, offset);
        }
        return jdbc.query(JOB_SELECT + """
                 WHERE job.state = ? ORDER BY job.created_at DESC, job.id LIMIT ? OFFSET ?
                """, this::job, state.name(), limit, offset);
    }

    @Override
    public long countJobs(McpRegistrySyncState state) {
        if (state == null) {
            return jdbc.queryForObject("SELECT count(*) FROM platform_mcp_registry_sync_jobs",
                    Long.class);
        }
        return jdbc.queryForObject(
                "SELECT count(*) FROM platform_mcp_registry_sync_jobs WHERE state = ?",
                Long.class, state.name());
    }

    @Override
    public List<McpRegistryCandidate> findCandidates(
            int offset, int limit, McpRegistryReviewState state, String query) {
        Query filter = candidateFilter(state, query);
        List<Object> args = new ArrayList<>(filter.args());
        args.add(limit);
        args.add(offset);
        return jdbc.query(CANDIDATE_SELECT + filter.sql()
                        + " ORDER BY candidate.created_at DESC, candidate.id LIMIT ? OFFSET ?",
                this::candidate, args.toArray());
    }

    @Override
    public long countCandidates(McpRegistryReviewState state, String query) {
        Query filter = candidateFilter(state, query);
        return jdbc.queryForObject("SELECT count(*) FROM platform_mcp_registry_candidates candidate "
                + filter.sql(), Long.class, filter.args().toArray());
    }

    @Override
    public Optional<McpRegistryCandidate> findCandidate(String id) {
        return jdbc.query(CANDIDATE_SELECT + " WHERE candidate.id = CAST(? AS UUID)",
                this::candidate, id).stream().findFirst();
    }

    @Override
    public Optional<McpRegistryCandidate> findCandidateForUpdate(String id) {
        return jdbc.query(CANDIDATE_SELECT + """
                 WHERE candidate.id = CAST(? AS UUID) FOR UPDATE OF candidate
                """, this::candidate, id).stream().findFirst();
    }

    @Override
    public Optional<McpRegistrySnapshot> findSnapshot(String id) {
        return jdbc.query("SELECT * FROM platform_mcp_registry_snapshots WHERE id = CAST(? AS UUID)",
                this::snapshot, id).stream().findFirst();
    }

    @Override
    public boolean markApproved(
            String candidateId, long expectedRevision, String reviewedBy, String reason,
            String entryId, String versionId, Instant reviewedAt) {
        return jdbc.update("""
                UPDATE platform_mcp_registry_candidates
                   SET review_state = 'APPROVED', reviewed_by = CAST(? AS UUID),
                       review_reason = ?, reviewed_at = ?, published_entry_id = CAST(? AS UUID),
                       published_version_id = CAST(? AS UUID), revision = revision + 1, updated_at = ?
                 WHERE id = CAST(? AS UUID) AND revision = ? AND review_state = 'PENDING_REVIEW'
                """, reviewedBy, reason, Timestamp.from(reviewedAt), entryId, versionId,
                Timestamp.from(reviewedAt), candidateId, expectedRevision) == 1;
    }

    @Override
    public boolean markRejected(
            String candidateId, long expectedRevision, String reviewedBy, String reason,
            Instant reviewedAt) {
        return jdbc.update("""
                UPDATE platform_mcp_registry_candidates
                   SET review_state = 'REJECTED', reviewed_by = CAST(? AS UUID),
                       review_reason = ?, reviewed_at = ?, revision = revision + 1, updated_at = ?
                 WHERE id = CAST(? AS UUID) AND revision = ? AND review_state = 'PENDING_REVIEW'
                """, reviewedBy, reason, Timestamp.from(reviewedAt),
                Timestamp.from(reviewedAt), candidateId, expectedRevision) == 1;
    }

    private Query candidateFilter(McpRegistryReviewState state, String query) {
        List<String> clauses = new ArrayList<>();
        List<Object> args = new ArrayList<>();
        if (state != null) {
            clauses.add("candidate.review_state = ?");
            args.add(state.name());
        }
        if (query != null && !query.isBlank()) {
            clauses.add("lower(candidate.registry_name) LIKE ?");
            args.add("%" + query.trim().toLowerCase(java.util.Locale.ROOT) + "%");
        }
        return new Query(clauses.isEmpty() ? "" : " WHERE " + String.join(" AND ", clauses), args);
    }

    private McpRegistrySource source(ResultSet result, int row) throws SQLException {
        return new McpRegistrySource(
                result.getString("id"), result.getString("source_key"),
                result.getString("display_name"), result.getString("base_url"),
                result.getBoolean("enabled"), instant(result, "last_successful_sync_at"),
                result.getLong("revision"), result.getTimestamp("created_at").toInstant(),
                result.getTimestamp("updated_at").toInstant());
    }

    private McpRegistrySyncJob job(ResultSet result, int row) throws SQLException {
        return new McpRegistrySyncJob(
                result.getString("id"), result.getString("source_id"),
                result.getString("source_key"), result.getString("requested_by"),
                McpRegistrySyncState.valueOf(result.getString("state")),
                instant(result, "updated_since"), result.getTimestamp("watermark_at").toInstant(),
                result.getInt("fetched_count"), result.getInt("snapshot_count"),
                result.getInt("candidate_count"), result.getString("safe_error_code"),
                result.getInt("attempt"), result.getString("claim_owner"),
                result.getString("claim_token"), instant(result, "lease_until"),
                result.getTimestamp("created_at").toInstant(), instant(result, "started_at"),
                result.getTimestamp("updated_at").toInstant(), instant(result, "completed_at"));
    }

    private McpRegistryCandidate candidate(ResultSet result, int row) throws SQLException {
        return new McpRegistryCandidate(
                result.getString("id"), result.getString("snapshot_id"),
                result.getString("source_id"), result.getString("source_key"),
                result.getString("registry_name"), result.getString("registry_version"),
                McpRegistryReviewState.valueOf(result.getString("review_state")),
                result.getString("reviewed_by"), result.getString("review_reason"),
                instant(result, "reviewed_at"), result.getString("published_entry_id"),
                result.getString("published_version_id"), result.getLong("revision"),
                result.getTimestamp("created_at").toInstant(),
                result.getTimestamp("updated_at").toInstant());
    }

    private McpRegistrySnapshot snapshot(ResultSet result, int row) throws SQLException {
        return new McpRegistrySnapshot(
                result.getString("id"), result.getString("source_id"),
                result.getString("sync_job_id"), result.getString("registry_name"),
                result.getString("registry_version"),
                McpRegistryStatus.valueOf(result.getString("registry_status")),
                result.getString("status_message"), result.getString("display_title"),
                result.getString("description"), result.getString("manifest_schema_uri"),
                result.getString("repository_uri"), result.getString("manifest_json"),
                result.getString("manifest_sha256"),
                McpRegistryCompatibility.valueOf(result.getString("compatibility")),
                result.getString("compatibility_reason"),
                instant(result, "source_published_at"), instant(result, "source_updated_at"),
                result.getTimestamp("fetched_at").toInstant(), readTransports(result.getString("transports_json")));
    }

    private String write(Object value) {
        try {
            return json.writeValueAsString(value);
        } catch (Exception error) {
            throw new IllegalStateException("Unable to serialize MCP Registry evidence", error);
        }
    }

    private List<McpRegistrySnapshot.RemoteTransport> readTransports(String value) {
        try {
            return json.readValue(value, new TypeReference<>() {});
        } catch (Exception error) {
            throw new IllegalStateException("Invalid MCP Registry transport evidence", error);
        }
    }

    private static Instant instant(ResultSet result, String column) throws SQLException {
        Timestamp value = result.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }

    private static Timestamp timestamp(Instant value) {
        return value == null ? null : Timestamp.from(value);
    }

    private record Query(String sql, List<Object> args) {
    }
}

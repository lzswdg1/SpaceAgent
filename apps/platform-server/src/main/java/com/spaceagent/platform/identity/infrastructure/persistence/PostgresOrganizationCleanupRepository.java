package com.spaceagent.platform.identity.infrastructure.persistence;

import com.spaceagent.platform.identity.domain.OrganizationCleanupJob;
import com.spaceagent.platform.identity.domain.OrganizationCleanupJobState;
import com.spaceagent.platform.identity.domain.OrganizationCleanupRepository;
import com.spaceagent.platform.identity.domain.OrganizationCleanupStep;
import com.spaceagent.platform.identity.domain.OrganizationCleanupStepKey;
import com.spaceagent.platform.identity.domain.OrganizationCleanupStepState;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Repository
@ConditionalOnProperty(prefix = "platform", name = "persistence", havingValue = "postgres")
public class PostgresOrganizationCleanupRepository implements OrganizationCleanupRepository {

    private static final String JOB_COLUMNS = """
            organization_id, state, retention_not_before, next_attempt_at, attempt,
            max_attempts, lease_owner, lease_token, fencing_token, lease_until,
            last_error_code, last_error_summary, revision, created_at, updated_at, completed_at
            """;
    private static final String STEP_COLUMNS = """
            organization_id, step_key, step_sequence, state, attempt, last_error_code,
            last_error_summary, created_at, updated_at, completed_at
            """;

    private final JdbcTemplate jdbc;

    public PostgresOrganizationCleanupRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void enqueue(OrganizationCleanupJob job, List<OrganizationCleanupStep> steps) {
        int inserted = jdbc.update("""
                INSERT INTO platform_organization_cleanup_jobs (
                    organization_id, state, retention_not_before, next_attempt_at,
                    attempt, max_attempts, lease_owner, lease_token, fencing_token,
                    lease_until, last_error_code, last_error_summary, revision,
                    created_at, updated_at, completed_at
                ) VALUES (?, ?, ?, ?, ?, ?, NULL, NULL, ?, NULL, NULL, NULL, ?, ?, ?, NULL)
                ON CONFLICT (organization_id) DO NOTHING
                """,
                job.organizationId(), job.state().name(), Timestamp.from(job.retentionNotBefore()),
                Timestamp.from(job.nextAttemptAt()), job.attempt(), job.maxAttempts(),
                job.fencingToken(), job.revision(), Timestamp.from(job.createdAt()),
                Timestamp.from(job.updatedAt()));
        if (inserted == 0) {
            return;
        }
        jdbc.batchUpdate("""
                INSERT INTO platform_organization_cleanup_steps (
                    organization_id, step_key, step_sequence, state, attempt,
                    last_error_code, last_error_summary, created_at, updated_at, completed_at
                ) VALUES (?, ?, ?, ?, ?, NULL, NULL, ?, ?, NULL)
                """, steps, steps.size(), (statement, step) -> {
            statement.setString(1, step.organizationId());
            statement.setString(2, step.stepKey().name());
            statement.setInt(3, step.sequence());
            statement.setString(4, step.state().name());
            statement.setInt(5, step.attempt());
            statement.setTimestamp(6, Timestamp.from(step.createdAt()));
            statement.setTimestamp(7, Timestamp.from(step.updatedAt()));
        });
    }

    @Override
    public Optional<OrganizationCleanupJob> findJob(String organizationId) {
        return jdbc.query("SELECT " + JOB_COLUMNS
                        + " FROM platform_organization_cleanup_jobs WHERE organization_id = ?",
                this::mapJob, organizationId).stream().findFirst();
    }

    @Override
    public List<OrganizationCleanupStep> findSteps(String organizationId) {
        return jdbc.query("SELECT " + STEP_COLUMNS
                        + " FROM platform_organization_cleanup_steps "
                        + "WHERE organization_id = ? ORDER BY step_sequence",
                this::mapStep, organizationId);
    }

    @Override
    public Optional<OrganizationCleanupJob> claimNext(
            String leaseOwner,
            String leaseToken,
            int leaseSeconds,
            Instant ignoredNow) {
        jdbc.update("""
                WITH exhausted AS (
                    SELECT organization_id
                    FROM platform_organization_cleanup_jobs
                    WHERE state = 'CLAIMED' AND lease_until <= clock_timestamp()
                      AND attempt >= max_attempts
                    FOR UPDATE SKIP LOCKED
                )
                UPDATE platform_organization_cleanup_jobs job
                SET state = 'BLOCKED', lease_owner = NULL, lease_token = NULL,
                    lease_until = NULL, last_error_code = 'CLEANUP_ATTEMPTS_EXHAUSTED',
                    last_error_summary = 'Cleanup claim attempts exhausted',
                    revision = revision + 1, updated_at = clock_timestamp()
                FROM exhausted
                WHERE job.organization_id = exhausted.organization_id
                """);
        return jdbc.query("""
                WITH candidate AS (
                    SELECT organization_id
                    FROM platform_organization_cleanup_jobs
                    WHERE retention_not_before <= clock_timestamp()
                      AND attempt < max_attempts
                      AND (
                        (state IN ('PENDING', 'RETRY')
                            AND next_attempt_at <= clock_timestamp())
                        OR (state = 'CLAIMED' AND lease_until <= clock_timestamp())
                      )
                    ORDER BY next_attempt_at, created_at, organization_id
                    FOR UPDATE SKIP LOCKED
                    LIMIT 1
                )
                UPDATE platform_organization_cleanup_jobs job
                SET state = 'CLAIMED', lease_owner = ?, lease_token = CAST(? AS UUID),
                    fencing_token = job.fencing_token + 1,
                    lease_until = clock_timestamp() + make_interval(secs => ?),
                    attempt = job.attempt + 1, revision = job.revision + 1,
                    updated_at = clock_timestamp()
                FROM candidate
                WHERE job.organization_id = candidate.organization_id
                RETURNING job.*
                """, this::mapJob, leaseOwner, leaseToken, leaseSeconds).stream().findFirst();
    }

    @Override
    public Optional<OrganizationCleanupJob> heartbeat(
            String organizationId,
            String leaseOwner,
            String leaseToken,
            long fencingToken,
            int leaseSeconds,
            Instant ignoredNow) {
        return updateClaimedJob("""
                UPDATE platform_organization_cleanup_jobs
                SET lease_until = clock_timestamp() + make_interval(secs => ?),
                    revision = revision + 1, updated_at = clock_timestamp()
                WHERE organization_id = ? AND state = 'CLAIMED'
                  AND lease_owner = ? AND lease_token = CAST(? AS UUID)
                  AND fencing_token = ? AND lease_until > clock_timestamp()
                RETURNING *
                """, leaseSeconds, organizationId, leaseOwner, leaseToken, fencingToken);
    }

    @Override
    public Optional<OrganizationCleanupStep> completeStep(
            String organizationId,
            OrganizationCleanupStepKey stepKey,
            String leaseOwner,
            String leaseToken,
            long fencingToken,
            Instant ignoredNow) {
        Optional<OrganizationCleanupStep> updated = jdbc.query("""
                UPDATE platform_organization_cleanup_steps step
                SET state = 'COMPLETED', attempt = step.attempt + 1,
                    last_error_code = NULL, last_error_summary = NULL,
                    completed_at = clock_timestamp(), updated_at = clock_timestamp()
                WHERE step.organization_id = ? AND step.step_key = ?
                  AND step.state = 'PENDING'
                  AND NOT EXISTS (
                    SELECT 1 FROM platform_organization_cleanup_steps prior
                    WHERE prior.organization_id = step.organization_id
                      AND prior.state = 'PENDING'
                      AND prior.step_sequence < step.step_sequence)
                  AND EXISTS (
                    SELECT 1 FROM platform_organization_cleanup_jobs job
                    WHERE job.organization_id = step.organization_id
                      AND job.state = 'CLAIMED' AND job.lease_owner = ?
                      AND job.lease_token = CAST(? AS UUID) AND job.fencing_token = ?
                      AND job.lease_until > clock_timestamp())
                RETURNING step.*
                """, this::mapStep, organizationId, stepKey.name(), leaseOwner,
                leaseToken, fencingToken).stream().findFirst();
        if (updated.isPresent()) {
            return updated;
        }
        return jdbc.query("""
                SELECT step.*
                FROM platform_organization_cleanup_steps step
                JOIN platform_organization_cleanup_jobs job
                  ON job.organization_id = step.organization_id
                WHERE step.organization_id = ? AND step.step_key = ?
                  AND step.state = 'COMPLETED' AND job.state = 'CLAIMED'
                  AND job.lease_owner = ? AND job.lease_token = CAST(? AS UUID)
                  AND job.fencing_token = ? AND job.lease_until > clock_timestamp()
                """, this::mapStep, organizationId, stepKey.name(), leaseOwner,
                leaseToken, fencingToken).stream().findFirst();
    }

    @Override
    public Optional<OrganizationCleanupJob> defer(
            String organizationId,
            OrganizationCleanupStepKey stepKey,
            String leaseOwner,
            String leaseToken,
            long fencingToken,
            Instant nextAttemptAt,
            String errorCode,
            String errorSummary,
            Instant ignoredNow) {
        return releaseClaim(organizationId, stepKey, leaseOwner, leaseToken, fencingToken,
                nextAttemptAt, errorCode, errorSummary, false, false);
    }

    @Override
    public Optional<OrganizationCleanupJob> fail(
            String organizationId,
            OrganizationCleanupStepKey stepKey,
            String leaseOwner,
            String leaseToken,
            long fencingToken,
            Instant nextAttemptAt,
            String errorCode,
            String errorSummary,
            Instant ignoredNow) {
        return releaseClaim(organizationId, stepKey, leaseOwner, leaseToken, fencingToken,
                nextAttemptAt, errorCode, errorSummary, true, false);
    }

    @Override
    public Optional<OrganizationCleanupJob> block(String organizationId,OrganizationCleanupStepKey stepKey,
            String leaseOwner,String leaseToken,long fencingToken,String errorCode,String errorSummary,Instant now){
        return releaseClaim(organizationId,stepKey,leaseOwner,leaseToken,fencingToken,now,
                errorCode,errorSummary,true,true);
    }

    @Override
    public Optional<OrganizationCleanupJob> complete(
            String organizationId,
            String leaseOwner,
            String leaseToken,
            long fencingToken,
            Instant ignoredNow) {
        return updateClaimedJob("""
                UPDATE platform_organization_cleanup_jobs job
                SET state = 'COMPLETED', lease_owner = NULL, lease_token = NULL,
                    lease_until = NULL, completed_at = clock_timestamp(),
                    revision = revision + 1, updated_at = clock_timestamp()
                WHERE organization_id = ? AND state = 'CLAIMED'
                  AND lease_owner = ? AND lease_token = CAST(? AS UUID)
                  AND fencing_token = ? AND lease_until > clock_timestamp()
                  AND NOT EXISTS (
                    SELECT 1 FROM platform_organization_cleanup_steps step
                    WHERE step.organization_id = job.organization_id
                      AND step.state <> 'COMPLETED')
                RETURNING *
                """, organizationId, leaseOwner, leaseToken, fencingToken);
    }

    @Override
    public void resumeStorageDeletionAfterAdminApproval(String organizationId, Instant ignoredNow) {
        jdbc.update("""
                UPDATE platform_organization_cleanup_jobs
                SET state = 'PENDING', next_attempt_at = clock_timestamp(),
                    last_error_code = NULL, last_error_summary = NULL,
                    revision = revision + 1, updated_at = clock_timestamp()
                WHERE organization_id = ? AND state = 'BLOCKED'
                  AND last_error_code = 'PROJECT_STORAGE_ADMIN_REQUIRED'
                """, organizationId);
    }

    @Override
    public long countIncompleteSteps(String organizationId) {
        Long count = jdbc.queryForObject("""
                SELECT count(*) FROM platform_organization_cleanup_steps
                WHERE organization_id = ? AND state <> 'COMPLETED'
                """, Long.class, organizationId);
        return count == null ? 0L : count;
    }

    private Optional<OrganizationCleanupJob> releaseClaim(
            String organizationId,
            OrganizationCleanupStepKey stepKey,
            String leaseOwner,
            String leaseToken,
            long fencingToken,
            Instant nextAttemptAt,
            String errorCode,
            String errorSummary,
            boolean enforceAttempts,
            boolean forceBlock) {
        String nextState = forceBlock ? "'BLOCKED'" : enforceAttempts
                ? "CASE WHEN attempt >= max_attempts THEN 'BLOCKED' ELSE 'RETRY' END"
                : "'RETRY'";
        String nextAttemptCount = enforceAttempts || forceBlock ? "attempt" : "GREATEST(attempt - 1, 0)";
        return updateClaimedJob("""
                WITH step_update AS (
                    UPDATE platform_organization_cleanup_steps step
                    SET attempt = step.attempt + 1, last_error_code = ?,
                        last_error_summary = ?, updated_at = clock_timestamp()
                    WHERE step.organization_id = ? AND step.step_key = ?
                      AND step.state = 'PENDING'
                      AND NOT EXISTS (
                        SELECT 1 FROM platform_organization_cleanup_steps prior
                        WHERE prior.organization_id = step.organization_id
                          AND prior.state = 'PENDING'
                          AND prior.step_sequence < step.step_sequence)
                      AND EXISTS (
                        SELECT 1 FROM platform_organization_cleanup_jobs current_job
                        WHERE current_job.organization_id = step.organization_id
                          AND current_job.state = 'CLAIMED'
                          AND current_job.lease_owner = ?
                          AND current_job.lease_token = CAST(? AS UUID)
                          AND current_job.fencing_token = ?
                          AND current_job.lease_until > clock_timestamp())
                    RETURNING step.organization_id
                )
                UPDATE platform_organization_cleanup_jobs job
                SET state = %s, attempt = %s, next_attempt_at = ?, lease_owner = NULL,
                    lease_token = NULL, lease_until = NULL, last_error_code = ?,
                    last_error_summary = ?, revision = revision + 1,
                    updated_at = clock_timestamp()
                WHERE job.organization_id = ? AND job.state = 'CLAIMED'
                  AND job.lease_owner = ? AND job.lease_token = CAST(? AS UUID)
                  AND job.fencing_token = ? AND job.lease_until > clock_timestamp()
                  AND EXISTS (SELECT 1 FROM step_update)
                RETURNING job.*
                """.formatted(nextState, nextAttemptCount), errorCode, errorSummary, organizationId,
                stepKey.name(), leaseOwner, leaseToken, fencingToken,
                Timestamp.from(nextAttemptAt), errorCode, errorSummary, organizationId,
                leaseOwner, leaseToken, fencingToken);
    }

    private Optional<OrganizationCleanupJob> updateClaimedJob(String sql, Object... args) {
        return jdbc.query(sql, this::mapJob, args).stream().findFirst();
    }

    private OrganizationCleanupJob mapJob(ResultSet rs, int row) throws SQLException {
        return new OrganizationCleanupJob(
                rs.getString("organization_id"),
                OrganizationCleanupJobState.valueOf(rs.getString("state")),
                rs.getTimestamp("retention_not_before").toInstant(),
                rs.getTimestamp("next_attempt_at").toInstant(),
                rs.getInt("attempt"), rs.getInt("max_attempts"),
                rs.getString("lease_owner"), rs.getString("lease_token"),
                rs.getLong("fencing_token"), instant(rs.getTimestamp("lease_until")),
                rs.getString("last_error_code"), rs.getString("last_error_summary"),
                rs.getLong("revision"), rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("updated_at").toInstant(),
                instant(rs.getTimestamp("completed_at")));
    }

    private OrganizationCleanupStep mapStep(ResultSet rs, int row) throws SQLException {
        return new OrganizationCleanupStep(
                rs.getString("organization_id"),
                OrganizationCleanupStepKey.valueOf(rs.getString("step_key")),
                rs.getInt("step_sequence"),
                OrganizationCleanupStepState.valueOf(rs.getString("state")),
                rs.getInt("attempt"), rs.getString("last_error_code"),
                rs.getString("last_error_summary"), rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("updated_at").toInstant(),
                instant(rs.getTimestamp("completed_at")));
    }

    private static Instant instant(Timestamp value) {
        return value == null ? null : value.toInstant();
    }
}

package com.spaceagent.platform.identity.infrastructure.persistence;

import com.spaceagent.platform.identity.domain.UserCleanupJob;
import com.spaceagent.platform.identity.domain.UserCleanupJobState;
import com.spaceagent.platform.identity.domain.UserCleanupRepository;
import com.spaceagent.platform.identity.domain.UserCleanupStep;
import com.spaceagent.platform.identity.domain.UserCleanupStepKey;
import com.spaceagent.platform.identity.domain.UserCleanupStepState;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
@ConditionalOnProperty(prefix = "platform", name = "persistence", havingValue = "postgres")
public class PostgresUserCleanupRepository implements UserCleanupRepository {
    private static final String JOB_COLUMNS = """
            user_id, command_id, requested_by, reason_hash, state, retention_not_before,
            next_attempt_at, attempt, max_attempts, lease_owner, lease_token, fencing_token,
            lease_until, last_error_code, last_error_summary, revision, created_at, updated_at,
            completed_at
            """;
    private static final String STEP_COLUMNS = """
            user_id, step_key, step_sequence, state, attempt, last_error_code,
            last_error_summary, created_at, updated_at, completed_at
            """;

    private final JdbcTemplate jdbc;

    public PostgresUserCleanupRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void enqueue(UserCleanupJob job, List<UserCleanupStep> steps) {
        int inserted = jdbc.update("""
                INSERT INTO platform_user_cleanup_jobs (
                    user_id, command_id, requested_by, reason_hash, state,
                    retention_not_before, next_attempt_at, attempt, max_attempts,
                    fencing_token, revision, created_at, updated_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (user_id) DO NOTHING
                """, job.userId(), job.commandId(), job.requestedBy(), job.reasonHash(),
                job.state().name(), Timestamp.from(job.retentionNotBefore()),
                Timestamp.from(job.nextAttemptAt()), job.attempt(), job.maxAttempts(),
                job.fencingToken(), job.revision(), Timestamp.from(job.createdAt()),
                Timestamp.from(job.updatedAt()));
        if (inserted == 0) return;
        jdbc.batchUpdate("""
                INSERT INTO platform_user_cleanup_steps (
                    user_id, step_key, step_sequence, state, attempt,
                    created_at, updated_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?)
                """, steps, steps.size(), (statement, step) -> {
            statement.setString(1, step.userId());
            statement.setString(2, step.stepKey().name());
            statement.setInt(3, step.sequence());
            statement.setString(4, step.state().name());
            statement.setInt(5, step.attempt());
            statement.setTimestamp(6, Timestamp.from(step.createdAt()));
            statement.setTimestamp(7, Timestamp.from(step.updatedAt()));
        });
    }

    @Override
    public Optional<UserCleanupJob> findJob(String userId) {
        return jdbc.query("SELECT " + JOB_COLUMNS
                        + " FROM platform_user_cleanup_jobs WHERE user_id = ?",
                this::mapJob, userId).stream().findFirst();
    }

    @Override
    public List<UserCleanupStep> findSteps(String userId) {
        return jdbc.query("SELECT " + STEP_COLUMNS
                        + " FROM platform_user_cleanup_steps WHERE user_id = ? ORDER BY step_sequence",
                this::mapStep, userId);
    }

    @Override
    public Optional<UserCleanupJob> claimNext(
            String leaseOwner, String leaseToken, int leaseSeconds, Instant ignoredNow) {
        jdbc.update("""
                WITH exhausted AS (
                    SELECT user_id FROM platform_user_cleanup_jobs
                    WHERE state = 'CLAIMED' AND lease_until <= clock_timestamp()
                      AND attempt >= max_attempts
                    FOR UPDATE SKIP LOCKED
                )
                UPDATE platform_user_cleanup_jobs job
                SET state = 'BLOCKED', lease_owner = NULL, lease_token = NULL,
                    lease_until = NULL, last_error_code = 'CLEANUP_ATTEMPTS_EXHAUSTED',
                    last_error_summary = 'User cleanup claim attempts exhausted',
                    revision = revision + 1, updated_at = clock_timestamp()
                FROM exhausted WHERE job.user_id = exhausted.user_id
                """);
        return jdbc.query("""
                WITH candidate AS (
                    SELECT user_id FROM platform_user_cleanup_jobs
                    WHERE retention_not_before <= clock_timestamp() AND attempt < max_attempts
                      AND (((state IN ('PENDING', 'RETRY')) AND next_attempt_at <= clock_timestamp())
                        OR (state = 'CLAIMED' AND lease_until <= clock_timestamp()))
                    ORDER BY next_attempt_at, created_at, user_id
                    FOR UPDATE SKIP LOCKED LIMIT 1
                )
                UPDATE platform_user_cleanup_jobs job
                SET state = 'CLAIMED', lease_owner = ?, lease_token = CAST(? AS UUID),
                    fencing_token = job.fencing_token + 1,
                    lease_until = clock_timestamp() + make_interval(secs => ?),
                    attempt = job.attempt + 1, revision = job.revision + 1,
                    updated_at = clock_timestamp()
                FROM candidate WHERE job.user_id = candidate.user_id
                RETURNING job.*
                """, this::mapJob, leaseOwner, leaseToken, leaseSeconds).stream().findFirst();
    }

    @Override
    public Optional<UserCleanupJob> heartbeat(String userId, String leaseOwner, String leaseToken,
                                               long fencingToken, int leaseSeconds, Instant ignoredNow) {
        return updateClaimedJob("""
                UPDATE platform_user_cleanup_jobs
                SET lease_until = clock_timestamp() + make_interval(secs => ?),
                    revision = revision + 1, updated_at = clock_timestamp()
                WHERE user_id = ? AND state = 'CLAIMED' AND lease_owner = ?
                  AND lease_token = CAST(? AS UUID) AND fencing_token = ?
                  AND lease_until > clock_timestamp()
                RETURNING *
                """, leaseSeconds, userId, leaseOwner, leaseToken, fencingToken);
    }

    @Override
    public Optional<UserCleanupStep> completeStep(String userId, UserCleanupStepKey stepKey,
                                                   String leaseOwner, String leaseToken,
                                                   long fencingToken, Instant ignoredNow) {
        Optional<UserCleanupStep> updated = jdbc.query("""
                UPDATE platform_user_cleanup_steps step
                SET state = 'COMPLETED', attempt = step.attempt + 1,
                    last_error_code = NULL, last_error_summary = NULL,
                    completed_at = clock_timestamp(), updated_at = clock_timestamp()
                WHERE step.user_id = ? AND step.step_key = ? AND step.state = 'PENDING'
                  AND NOT EXISTS (SELECT 1 FROM platform_user_cleanup_steps prior
                    WHERE prior.user_id = step.user_id AND prior.state = 'PENDING'
                      AND prior.step_sequence < step.step_sequence)
                  AND EXISTS (SELECT 1 FROM platform_user_cleanup_jobs job
                    WHERE job.user_id = step.user_id AND job.state = 'CLAIMED'
                      AND job.lease_owner = ? AND job.lease_token = CAST(? AS UUID)
                      AND job.fencing_token = ? AND job.lease_until > clock_timestamp())
                RETURNING step.*
                """, this::mapStep, userId, stepKey.name(), leaseOwner, leaseToken,
                fencingToken).stream().findFirst();
        if (updated.isPresent()) return updated;
        return jdbc.query("""
                SELECT step.* FROM platform_user_cleanup_steps step
                JOIN platform_user_cleanup_jobs job ON job.user_id = step.user_id
                WHERE step.user_id = ? AND step.step_key = ? AND step.state = 'COMPLETED'
                  AND job.state = 'CLAIMED' AND job.lease_owner = ?
                  AND job.lease_token = CAST(? AS UUID) AND job.fencing_token = ?
                  AND job.lease_until > clock_timestamp()
                """, this::mapStep, userId, stepKey.name(), leaseOwner, leaseToken,
                fencingToken).stream().findFirst();
    }

    @Override
    public Optional<UserCleanupJob> defer(String userId, UserCleanupStepKey stepKey,
                                           String leaseOwner, String leaseToken, long fencingToken,
                                           Instant nextAttemptAt, String errorCode,
                                           String errorSummary, Instant ignoredNow) {
        return releaseClaim(userId, stepKey, leaseOwner, leaseToken, fencingToken,
                nextAttemptAt, errorCode, errorSummary, false);
    }

    @Override
    public Optional<UserCleanupJob> fail(String userId, UserCleanupStepKey stepKey,
                                          String leaseOwner, String leaseToken, long fencingToken,
                                          Instant nextAttemptAt, String errorCode,
                                          String errorSummary, Instant ignoredNow) {
        return releaseClaim(userId, stepKey, leaseOwner, leaseToken, fencingToken,
                nextAttemptAt, errorCode, errorSummary, true);
    }

    @Override
    public Optional<UserCleanupJob> block(String userId, UserCleanupStepKey stepKey,
                                           String leaseOwner, String leaseToken, long fencingToken,
                                           String errorCode, String errorSummary, Instant ignoredNow) {
        return updateClaimedJob("""
                WITH step_update AS (
                    UPDATE platform_user_cleanup_steps step
                    SET attempt = step.attempt + 1, last_error_code = ?,
                        last_error_summary = ?, updated_at = clock_timestamp()
                    WHERE step.user_id = ? AND step.step_key = ? AND step.state = 'PENDING'
                      AND NOT EXISTS (SELECT 1 FROM platform_user_cleanup_steps prior
                        WHERE prior.user_id = step.user_id AND prior.state = 'PENDING'
                          AND prior.step_sequence < step.step_sequence)
                      AND EXISTS (SELECT 1 FROM platform_user_cleanup_jobs current_job
                        WHERE current_job.user_id = step.user_id AND current_job.state = 'CLAIMED'
                          AND current_job.lease_owner = ?
                          AND current_job.lease_token = CAST(? AS UUID)
                          AND current_job.fencing_token = ?
                          AND current_job.lease_until > clock_timestamp())
                    RETURNING step.user_id
                )
                UPDATE platform_user_cleanup_jobs job
                SET state = 'BLOCKED', lease_owner = NULL, lease_token = NULL,
                    lease_until = NULL, last_error_code = ?, last_error_summary = ?,
                    revision = revision + 1, updated_at = clock_timestamp()
                WHERE job.user_id = ? AND job.state = 'CLAIMED' AND job.lease_owner = ?
                  AND job.lease_token = CAST(? AS UUID) AND job.fencing_token = ?
                  AND job.lease_until > clock_timestamp()
                  AND EXISTS (SELECT 1 FROM step_update)
                RETURNING job.*
                """, errorCode, errorSummary, userId, stepKey.name(), leaseOwner,
                leaseToken, fencingToken, errorCode, errorSummary, userId, leaseOwner,
                leaseToken, fencingToken);
    }

    @Override
    public Optional<UserCleanupJob> complete(String userId, String leaseOwner, String leaseToken,
                                              long fencingToken, Instant ignoredNow) {
        return updateClaimedJob("""
                UPDATE platform_user_cleanup_jobs job
                SET state = 'COMPLETED', lease_owner = NULL, lease_token = NULL,
                    lease_until = NULL, completed_at = clock_timestamp(),
                    revision = revision + 1, updated_at = clock_timestamp()
                WHERE user_id = ? AND state = 'CLAIMED' AND lease_owner = ?
                  AND lease_token = CAST(? AS UUID) AND fencing_token = ?
                  AND lease_until > clock_timestamp()
                  AND NOT EXISTS (SELECT 1 FROM platform_user_cleanup_steps step
                    WHERE step.user_id = job.user_id AND step.state <> 'COMPLETED')
                RETURNING *
                """, userId, leaseOwner, leaseToken, fencingToken);
    }

    @Override
    public long countIncompleteSteps(String userId) {
        Long count = jdbc.queryForObject("""
                SELECT count(*) FROM platform_user_cleanup_steps
                WHERE user_id = ? AND state <> 'COMPLETED'
                """, Long.class, userId);
        return count == null ? 0L : count;
    }

    private Optional<UserCleanupJob> releaseClaim(
            String userId, UserCleanupStepKey stepKey, String leaseOwner, String leaseToken,
            long fencingToken, Instant nextAttemptAt, String errorCode, String errorSummary,
            boolean enforceAttempts) {
        String nextState = enforceAttempts
                ? "CASE WHEN attempt >= max_attempts THEN 'BLOCKED' ELSE 'RETRY' END"
                : "'RETRY'";
        String nextAttemptCount = enforceAttempts ? "attempt" : "GREATEST(attempt - 1, 0)";
        return updateClaimedJob("""
                WITH step_update AS (
                    UPDATE platform_user_cleanup_steps step
                    SET attempt = step.attempt + 1, last_error_code = ?,
                        last_error_summary = ?, updated_at = clock_timestamp()
                    WHERE step.user_id = ? AND step.step_key = ? AND step.state = 'PENDING'
                      AND NOT EXISTS (SELECT 1 FROM platform_user_cleanup_steps prior
                        WHERE prior.user_id = step.user_id AND prior.state = 'PENDING'
                          AND prior.step_sequence < step.step_sequence)
                      AND EXISTS (SELECT 1 FROM platform_user_cleanup_jobs current_job
                        WHERE current_job.user_id = step.user_id AND current_job.state = 'CLAIMED'
                          AND current_job.lease_owner = ?
                          AND current_job.lease_token = CAST(? AS UUID)
                          AND current_job.fencing_token = ?
                          AND current_job.lease_until > clock_timestamp())
                    RETURNING step.user_id
                )
                UPDATE platform_user_cleanup_jobs job
                SET state = %s, attempt = %s, next_attempt_at = ?, lease_owner = NULL,
                    lease_token = NULL, lease_until = NULL, last_error_code = ?,
                    last_error_summary = ?, revision = revision + 1,
                    updated_at = clock_timestamp()
                WHERE job.user_id = ? AND job.state = 'CLAIMED' AND job.lease_owner = ?
                  AND job.lease_token = CAST(? AS UUID) AND job.fencing_token = ?
                  AND job.lease_until > clock_timestamp()
                  AND EXISTS (SELECT 1 FROM step_update)
                RETURNING job.*
                """.formatted(nextState, nextAttemptCount), errorCode, errorSummary, userId,
                stepKey.name(), leaseOwner, leaseToken, fencingToken, Timestamp.from(nextAttemptAt),
                errorCode, errorSummary, userId, leaseOwner, leaseToken, fencingToken);
    }

    private Optional<UserCleanupJob> updateClaimedJob(String sql, Object... args) {
        return jdbc.query(sql, this::mapJob, args).stream().findFirst();
    }

    private UserCleanupJob mapJob(ResultSet rs, int row) throws SQLException {
        return new UserCleanupJob(rs.getString("user_id"),
                rs.getObject("command_id", UUID.class), rs.getObject("requested_by", UUID.class),
                rs.getString("reason_hash"), UserCleanupJobState.valueOf(rs.getString("state")),
                rs.getTimestamp("retention_not_before").toInstant(),
                rs.getTimestamp("next_attempt_at").toInstant(), rs.getInt("attempt"),
                rs.getInt("max_attempts"), rs.getString("lease_owner"),
                rs.getString("lease_token"), rs.getLong("fencing_token"),
                instant(rs.getTimestamp("lease_until")), rs.getString("last_error_code"),
                rs.getString("last_error_summary"), rs.getLong("revision"),
                rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("updated_at").toInstant(), instant(rs.getTimestamp("completed_at")));
    }

    private UserCleanupStep mapStep(ResultSet rs, int row) throws SQLException {
        return new UserCleanupStep(rs.getString("user_id"),
                UserCleanupStepKey.valueOf(rs.getString("step_key")), rs.getInt("step_sequence"),
                UserCleanupStepState.valueOf(rs.getString("state")), rs.getInt("attempt"),
                rs.getString("last_error_code"), rs.getString("last_error_summary"),
                rs.getTimestamp("created_at").toInstant(), rs.getTimestamp("updated_at").toInstant(),
                instant(rs.getTimestamp("completed_at")));
    }

    private static Instant instant(Timestamp value) {
        return value == null ? null : value.toInstant();
    }
}

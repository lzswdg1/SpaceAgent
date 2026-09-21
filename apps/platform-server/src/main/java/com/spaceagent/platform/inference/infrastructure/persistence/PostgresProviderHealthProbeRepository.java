package com.spaceagent.platform.inference.infrastructure.persistence;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.spaceagent.platform.inference.domain.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Repository
@ConditionalOnProperty(prefix = "platform", name = "persistence", havingValue = "postgres")
public class PostgresProviderHealthProbeRepository implements ProviderHealthProbeRepository {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final ObjectMapper json;

    public PostgresProviderHealthProbeRepository(
            JdbcTemplate jdbc, PlatformTransactionManager transactionManager, ObjectMapper json) {
        this.jdbc = jdbc;
        this.transactions = new TransactionTemplate(transactionManager);
        this.transactions.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.json = json;
    }

    @Override
    public void synchronize(ModelProvider provider, Instant now) {
        transactions.executeWithoutResult(ignored -> jdbc.update("""
                INSERT INTO platform_provider_health_probe_jobs(
                 provider_id,tenant_id,enabled,next_probe_at,revision)
                VALUES(?,?,?,?,1)
                ON CONFLICT(provider_id) DO UPDATE SET
                 tenant_id=EXCLUDED.tenant_id,enabled=EXCLUDED.enabled,
                 next_probe_at=CASE WHEN EXCLUDED.enabled AND NOT platform_provider_health_probe_jobs.enabled
                                    THEN EXCLUDED.next_probe_at
                                    ELSE platform_provider_health_probe_jobs.next_probe_at END,
                 claim_token=CASE WHEN EXCLUDED.enabled THEN platform_provider_health_probe_jobs.claim_token ELSE NULL END,
                 claim_owner=CASE WHEN EXCLUDED.enabled THEN platform_provider_health_probe_jobs.claim_owner ELSE NULL END,
                 lease_until=CASE WHEN EXCLUDED.enabled THEN platform_provider_health_probe_jobs.lease_until ELSE NULL END,
                 revision=platform_provider_health_probe_jobs.revision+1
                """, provider.id(), provider.tenantId(), provider.enabled(), Timestamp.from(now)));
    }

    @Override
    public Optional<ProviderHealthProbeClaim> claimDue(String owner, long leaseSeconds) {
        return required(transactions.execute(ignored -> jdbc.query("""
                WITH due AS(
                 SELECT provider_id FROM platform_provider_health_probe_jobs
                 WHERE enabled AND next_probe_at<=clock_timestamp()
                   AND (claim_token IS NULL OR lease_until<=clock_timestamp())
                 ORDER BY next_probe_at,provider_id FOR UPDATE SKIP LOCKED LIMIT 1)
                UPDATE platform_provider_health_probe_jobs job
                 SET claim_token=gen_random_uuid(),claim_owner=?,
                     lease_until=clock_timestamp()+(?*INTERVAL '1 second'),
                     fencing_token=fencing_token+1,attempt_count=attempt_count+1,
                     revision=revision+1,last_started_at=clock_timestamp()
                FROM due WHERE job.provider_id=due.provider_id
                RETURNING job.provider_id,job.tenant_id,job.claim_token::text,job.claim_owner,
                          job.fencing_token,job.revision,job.attempt_count,job.lease_until
                """, (rs, row) -> new ProviderHealthProbeClaim(
                        rs.getString("provider_id"), rs.getString("tenant_id"),
                        rs.getString("claim_token"), rs.getString("claim_owner"),
                        rs.getLong("fencing_token"), rs.getLong("revision"),
                        rs.getInt("attempt_count"), rs.getTimestamp("lease_until").toInstant()),
                owner, leaseSeconds).stream().findFirst()));
    }

    @Override
    public boolean complete(Completion value) {
        return Boolean.TRUE.equals(transactions.execute(ignored -> {
            ProviderConnectionStatus status = value.result().success()
                    ? ProviderConnectionStatus.ACTIVE : ProviderConnectionStatus.UNHEALTHY;
            int updated = jdbc.update("""
                    UPDATE platform_provider_health_probe_jobs
                     SET next_probe_at=?,claim_token=NULL,claim_owner=NULL,lease_until=NULL,
                         attempt_count=CASE WHEN ? THEN 0 ELSE attempt_count END,
                         revision=revision+1,last_completed_at=?,last_error_code=?
                    WHERE provider_id=? AND tenant_id=? AND enabled
                      AND claim_token=CAST(? AS UUID) AND claim_owner=?
                      AND fencing_token=? AND revision=? AND lease_until>clock_timestamp()
                    """, Timestamp.from(value.observedAt().plusSeconds(
                            value.result().success()
                                    ? value.successIntervalSeconds() : value.retryDelaySeconds())),
                    value.result().success(), Timestamp.from(value.observedAt()),
                    value.result().success() ? null : value.result().errorCode(),
                    value.claim().providerId(), value.claim().tenantId(),
                    value.claim().claimToken(), value.claim().claimOwner(),
                    value.claim().fencingToken(), value.claim().revision());
            if (updated != 1) return false;
            jdbc.update("""
                    UPDATE platform_model_providers
                     SET connection_status=?,last_tested_at=?,last_test_latency_ms=?,
                         last_test_error_code=?,updated_at=?
                    WHERE id=? AND tenant_id=? AND enabled
                    """, status.name(), Timestamp.from(value.observedAt()),
                    value.result().latencyMs(),
                    value.result().success() ? null : value.result().errorCode(),
                    Timestamp.from(value.observedAt()), value.claim().providerId(),
                    value.claim().tenantId());
            insertObservation(value.observationId(), value.claim().providerId(),
                    value.claim().tenantId(), "SCHEDULED", value.result(), value.observedAt());
            return true;
        }));
    }

    @Override
    public void recordManual(ManualObservation value) {
        transactions.executeWithoutResult(ignored -> {
            ProviderConnectionStatus status = value.result().success()
                    ? ProviderConnectionStatus.ACTIVE : ProviderConnectionStatus.UNHEALTHY;
            jdbc.update("""
                    UPDATE platform_model_providers
                     SET connection_status=?,last_tested_at=?,last_test_latency_ms=?,
                         last_test_error_code=?,updated_at=?
                    WHERE id=? AND tenant_id=?
                    """, status.name(), Timestamp.from(value.observedAt()),
                    value.result().latencyMs(),
                    value.result().success() ? null : value.result().errorCode(),
                    Timestamp.from(value.observedAt()), value.provider().id(),
                    value.provider().tenantId());
            synchronizeInTransaction(value.provider(), value.observedAt(), value.nextProbeSeconds());
            insertObservation(value.observationId(), value.provider().id(),
                    value.provider().tenantId(), "MANUAL", value.result(), value.observedAt());
        });
    }

    @Override
    public List<ProviderHealthObservation> findObservations(
            String tenantId, String providerId, int limit) {
        return jdbc.query("""
                SELECT id::text,provider_id,tenant_id,source,success,connection_status,
                       latency_ms,discovered_models::text,error_code,observed_at
                FROM platform_provider_health_observations
                WHERE tenant_id=? AND provider_id=? ORDER BY observed_at DESC,id LIMIT ?
                """, (rs, row) -> new ProviderHealthObservation(
                        rs.getString("id"), rs.getString("provider_id"),
                        rs.getString("tenant_id"), rs.getString("source"),
                        rs.getBoolean("success"),
                        ProviderConnectionStatus.valueOf(rs.getString("connection_status")),
                        rs.getInt("latency_ms"), strings(rs.getString("discovered_models")),
                        rs.getString("error_code"), rs.getTimestamp("observed_at").toInstant()),
                tenantId, providerId, Math.max(1, Math.min(limit, 100)));
    }

    private void synchronizeInTransaction(
            ModelProvider provider, Instant observedAt, long nextProbeSeconds) {
        jdbc.update("""
                INSERT INTO platform_provider_health_probe_jobs(
                 provider_id,tenant_id,enabled,next_probe_at,revision)
                VALUES(?,?,?,?,1)
                ON CONFLICT(provider_id) DO UPDATE SET
                 enabled=EXCLUDED.enabled,next_probe_at=EXCLUDED.next_probe_at,
                 claim_token=NULL,claim_owner=NULL,lease_until=NULL,attempt_count=0,
                 revision=platform_provider_health_probe_jobs.revision+1,
                 last_completed_at=?,last_error_code=NULL
                """, provider.id(), provider.tenantId(), provider.enabled(),
                Timestamp.from(observedAt.plusSeconds(nextProbeSeconds)),
                Timestamp.from(observedAt));
    }

    private void insertObservation(
            String id, String providerId, String tenantId, String source,
            ProviderConnectionProbeResult result, Instant at) {
        jdbc.update("""
                INSERT INTO platform_provider_health_observations(
                 id,provider_id,tenant_id,source,success,connection_status,latency_ms,
                 discovered_models,error_code,observed_at)
                VALUES(CAST(? AS UUID),?,?,?,?,?,?,CAST(? AS JSONB),?,?)
                """, id, providerId, tenantId, source, result.success(),
                (result.success() ? ProviderConnectionStatus.ACTIVE
                        : ProviderConnectionStatus.UNHEALTHY).name(),
                result.latencyMs(), write(result.discoveredModelIds()),
                result.success() ? null : result.errorCode(), Timestamp.from(at));
    }

    private String write(Object value) {
        try { return json.writeValueAsString(value); }
        catch (Exception error) { throw new IllegalStateException("Unable to encode probe evidence"); }
    }

    private List<String> strings(String value) {
        try { return json.readValue(value, new TypeReference<List<String>>() {}); }
        catch (Exception error) { throw new IllegalStateException("Unable to read probe evidence"); }
    }

    private static <T> T required(T value) {
        if (value == null) throw new IllegalStateException("Probe transaction returned no result");
        return value;
    }
}

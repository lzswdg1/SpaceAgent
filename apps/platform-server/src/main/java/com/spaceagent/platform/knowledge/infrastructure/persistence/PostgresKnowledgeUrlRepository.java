package com.spaceagent.platform.knowledge.infrastructure.persistence;

import com.spaceagent.platform.knowledge.domain.KnowledgeUrlEvidence;
import com.spaceagent.platform.knowledge.domain.KnowledgeUrlJob;
import com.spaceagent.platform.knowledge.domain.KnowledgeUrlRepository;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Repository
@ConditionalOnProperty(prefix = "platform", name = "persistence", havingValue = "postgres")
public class PostgresKnowledgeUrlRepository implements KnowledgeUrlRepository {
    private static final String JOB_FIELDS = "id,tenant_id,owner_id,knowledge_document_id,"
            + "normalized_url,origin,refresh_mode,refresh_interval_seconds,use_conditional_requests,"
            + "maximum_redirects,maximum_bytes,state,revision,next_refresh_at,created_at,updated_at,archived_at";
    private static final String OBSERVATION_FIELDS = "id,url_job_id,tenant_id,request_url_sha256,"
            + "final_url,final_url_sha256,http_status,etag,last_modified,content_sha256,outcome,"
            + "safe_error_code,observed_at";
    private static final String VERSION_FIELDS = "id,url_job_id,knowledge_document_id,tenant_id,version,"
            + "content_sha256,object_reference,media_type,charset,byte_size,state,source_observation_id,"
            + "created_at,activated_at";

    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;

    public PostgresKnowledgeUrlRepository(
            JdbcTemplate jdbc,
            PlatformTransactionManager transactionManager) {
        this.jdbc = jdbc;
        this.transaction = new TransactionTemplate(transactionManager);
    }

    @Override
    public Instant currentTime() {
        return jdbc.queryForObject("SELECT clock_timestamp()", Timestamp.class).toInstant();
    }

    @Override
    public void insertJob(KnowledgeUrlJob value) {
        jdbc.update("INSERT INTO platform_knowledge_url_jobs(id,tenant_id,owner_id,knowledge_document_id,"
                        + "normalized_url,origin,refresh_mode,refresh_interval_seconds,use_conditional_requests,"
                        + "maximum_redirects,maximum_bytes,state,revision,next_refresh_at,created_at,updated_at,"
                        + "archived_at) VALUES(CAST(? AS UUID),?,?,?, ?,?,?,?,?,?,?,?, ?,?,?,?,?)",
                value.id(), value.tenantId(), value.ownerId(), value.knowledgeDocumentId(),
                value.normalizedUrl(), value.origin(), value.refreshPolicy().mode().name(),
                value.refreshPolicy().intervalSeconds(), value.refreshPolicy().useConditionalRequests(),
                value.refreshPolicy().maximumRedirects(), value.refreshPolicy().maximumBytes(),
                value.state().name(), value.revision(), timestamp(value.nextRefreshAt()),
                timestamp(value.createdAt()), timestamp(value.updatedAt()), timestamp(value.archivedAt()));
    }

    @Override
    public Optional<KnowledgeUrlJob> findJob(String tenantId, String ownerId, String jobId) {
        return jdbc.query("SELECT " + JOB_FIELDS
                        + " FROM platform_knowledge_url_jobs WHERE id=CAST(? AS UUID) AND tenant_id=? AND owner_id=?",
                this::job, jobId, tenantId, ownerId).stream().findFirst();
    }

    @Override
    public Optional<KnowledgeUrlJob> findJob(
            String tenantId, String ownerId, String documentId, String normalizedUrl) {
        return jdbc.query("SELECT " + JOB_FIELDS
                        + " FROM platform_knowledge_url_jobs WHERE tenant_id=? AND owner_id=?"
                        + " AND knowledge_document_id=? AND normalized_url=?",
                this::job, tenantId, ownerId, documentId, normalizedUrl).stream().findFirst();
    }

    @Override
    public List<KnowledgeUrlJob> findJobsByDocument(
            String tenantId, String ownerId, String documentId, int offset, int limit) {
        return jdbc.query("SELECT " + JOB_FIELDS
                        + " FROM platform_knowledge_url_jobs WHERE tenant_id=? AND owner_id=?"
                        + " AND knowledge_document_id=? ORDER BY updated_at DESC,id LIMIT ? OFFSET ?",
                this::job, tenantId, ownerId, documentId, limit, offset);
    }

    @Override
    public long countJobsByDocument(String tenantId, String ownerId, String documentId) {
        return jdbc.queryForObject("SELECT count(*) FROM platform_knowledge_url_jobs"
                        + " WHERE tenant_id=? AND owner_id=? AND knowledge_document_id=?",
                Long.class, tenantId, ownerId, documentId);
    }

    @Override
    public Optional<KnowledgeUrlJob> updateJob(KnowledgeUrlJob value, long expectedRevision) {
        return jdbc.query("UPDATE platform_knowledge_url_jobs SET state=?,revision=revision+1,"
                        + "next_refresh_at=?,updated_at=?,archived_at=? WHERE id=CAST(? AS UUID)"
                        + " AND tenant_id=? AND owner_id=? AND revision=? RETURNING " + JOB_FIELDS,
                this::job, value.state().name(), timestamp(value.nextRefreshAt()),
                timestamp(value.updatedAt()), timestamp(value.archivedAt()), value.id(),
                value.tenantId(), value.ownerId(), expectedRevision).stream().findFirst();
    }

    @Override
    public Optional<KnowledgeUrlEvidence.RefreshLease> claimNext(
            String workerId, String claimToken, int leaseSeconds) {
        return jdbc.query("WITH candidate AS(SELECT id FROM platform_knowledge_url_jobs"
                        + " WHERE state='ACTIVE' AND next_refresh_at<=clock_timestamp()"
                        + " AND (lease_until IS NULL OR lease_until<=clock_timestamp())"
                        + " ORDER BY next_refresh_at,id LIMIT 1 FOR UPDATE SKIP LOCKED)"
                        + " UPDATE platform_knowledge_url_jobs u SET claim_token=CAST(? AS UUID),claim_owner=?,"
                        + " lease_until=clock_timestamp()+(?*interval '1 second'),"
                        + " fencing_token=fencing_token+1,revision=revision+1,updated_at=clock_timestamp()"
                        + " FROM candidate WHERE u.id=candidate.id RETURNING u.*,clock_timestamp() database_now",
                (result, row) -> new KnowledgeUrlEvidence.RefreshLease(
                        job(result, row), result.getString("claim_token"), result.getString("claim_owner"),
                        result.getTimestamp("lease_until").toInstant(), result.getLong("fencing_token"),
                        result.getLong("revision"), result.getTimestamp("database_now").toInstant()),
                claimToken, workerId, leaseSeconds).stream().findFirst();
    }

    @Override
    public boolean renewLease(
            String jobId, String claimToken, long fencingToken,
            long expectedRevision, int leaseSeconds) {
        return jdbc.update("UPDATE platform_knowledge_url_jobs"
                        + " SET lease_until=clock_timestamp()+(?*interval '1 second'),updated_at=clock_timestamp()"
                        + " WHERE id=CAST(? AS UUID) AND state='ACTIVE' AND claim_token=CAST(? AS UUID)"
                        + " AND fencing_token=? AND revision=? AND lease_until>clock_timestamp()",
                leaseSeconds, jobId, claimToken, fencingToken, expectedRevision) == 1;
    }

    @Override
    public boolean markLeaseUnknown(
            String jobId, String claimToken, long fencingToken, long expectedRevision) {
        return jdbc.update("UPDATE platform_knowledge_url_jobs SET state='PAUSED',"
                        + "claim_token=NULL,claim_owner=NULL,lease_until=NULL,revision=revision+1,"
                        + "updated_at=clock_timestamp() WHERE id=CAST(? AS UUID)"
                        + " AND claim_token=CAST(? AS UUID) AND fencing_token=? AND revision=?",
                jobId, claimToken, fencingToken, expectedRevision) == 1;
    }

    @Override
    public boolean releaseLease(
            String jobId, String claimToken, long fencingToken,
            long expectedRevision, Instant nextRefreshAt) {
        return jdbc.update("UPDATE platform_knowledge_url_jobs SET claim_token=NULL,claim_owner=NULL,"
                        + "lease_until=NULL,next_refresh_at=?,revision=revision+1,updated_at=clock_timestamp()"
                        + " WHERE id=CAST(? AS UUID) AND claim_token=CAST(? AS UUID)"
                        + " AND fencing_token=? AND revision=?",
                timestamp(nextRefreshAt), jobId, claimToken, fencingToken, expectedRevision) == 1;
    }

    @Override
    public void appendObservation(KnowledgeUrlEvidence.Observation value) {
        jdbc.update("INSERT INTO platform_knowledge_url_observations(id,url_job_id,tenant_id,"
                        + "request_url_sha256,final_url,final_url_sha256,http_status,etag,last_modified,"
                        + "content_sha256,outcome,safe_error_code,observed_at)"
                        + " VALUES(CAST(? AS UUID),CAST(? AS UUID),?,?,?,?,?,?,?,?,?,?,?)",
                value.id(), value.urlJobId(), value.tenantId(), value.requestUrlSha256(),
                value.finalUrl(), value.finalUrlSha256(), value.httpStatus(), value.etag(),
                value.lastModified(), value.contentSha256(), value.outcome().name(),
                value.safeErrorCode(), timestamp(value.observedAt()));
    }

    @Override
    public List<KnowledgeUrlEvidence.Observation> observations(
            String tenantId, String jobId, int limit) {
        return jdbc.query("SELECT " + OBSERVATION_FIELDS
                        + " FROM platform_knowledge_url_observations WHERE tenant_id=?"
                        + " AND url_job_id=CAST(? AS UUID) ORDER BY observed_at DESC,id LIMIT ?",
                this::observation, tenantId, jobId, limit);
    }

    @Override
    public void insertContentVersion(KnowledgeUrlEvidence.ContentVersion value) {
        jdbc.update("INSERT INTO platform_knowledge_url_content_versions(id,url_job_id,"
                        + "knowledge_document_id,tenant_id,version,content_sha256,object_reference,"
                        + "media_type,charset,byte_size,state,source_observation_id,created_at,activated_at)"
                        + " VALUES(CAST(? AS UUID),CAST(? AS UUID),?,?, ?,?,?,?,?,?,?,CAST(? AS UUID),?,?)",
                value.id(), value.urlJobId(), value.knowledgeDocumentId(), value.tenantId(), value.version(),
                value.contentSha256(), value.objectReference(), value.mediaType(), value.charset(),
                value.byteSize(), value.state().name(), value.sourceObservationId(),
                timestamp(value.createdAt()), timestamp(value.activatedAt()));
    }

    @Override
    public List<KnowledgeUrlEvidence.ContentVersion> contentVersions(
            String tenantId, String jobId) {
        return jdbc.query("SELECT " + VERSION_FIELDS
                        + " FROM platform_knowledge_url_content_versions WHERE tenant_id=?"
                        + " AND url_job_id=CAST(? AS UUID) ORDER BY version",
                this::contentVersion, tenantId, jobId);
    }

    @Override
    public Optional<KnowledgeUrlEvidence.ContentVersion> activateContentVersion(
            String tenantId, String jobId, String versionId, String claimToken,
            long fencingToken, long expectedLeaseRevision, Instant at) {
        return transaction.execute(status -> {
            boolean claimMatches = !jdbc.query("SELECT id FROM platform_knowledge_url_jobs"
                            + " WHERE id=CAST(? AS UUID) AND tenant_id=? AND state='ACTIVE'"
                            + " AND claim_token=CAST(? AS UUID) AND fencing_token=? AND revision=?"
                            + " AND lease_until>clock_timestamp() FOR UPDATE",
                    (result, row) -> result.getString(1), jobId, tenantId, claimToken,
                    fencingToken, expectedLeaseRevision).isEmpty();
            if (!claimMatches) return Optional.empty();
            var target = jdbc.query("SELECT " + VERSION_FIELDS
                            + " FROM platform_knowledge_url_content_versions WHERE id=CAST(? AS UUID)"
                            + " AND tenant_id=? AND url_job_id=CAST(? AS UUID)"
                            + " AND state IN('STAGED','SUPERSEDED') FOR UPDATE",
                    this::contentVersion, versionId, tenantId, jobId).stream().findFirst();
            if (target.isEmpty()) return Optional.empty();
            jdbc.update("UPDATE platform_knowledge_url_content_versions SET state='SUPERSEDED'"
                    + " WHERE tenant_id=? AND url_job_id=CAST(? AS UUID) AND state='ACTIVE'", tenantId, jobId);
            return jdbc.query("UPDATE platform_knowledge_url_content_versions"
                            + " SET state='ACTIVE',activated_at=? WHERE id=CAST(? AS UUID) RETURNING "
                            + VERSION_FIELDS,
                    this::contentVersion, timestamp(at), versionId).stream().findFirst();
        });
    }

    private KnowledgeUrlJob job(ResultSet result, int row) throws SQLException {
        return new KnowledgeUrlJob(
                result.getString("id"), result.getString("tenant_id"), result.getString("owner_id"),
                result.getString("knowledge_document_id"), result.getString("normalized_url"),
                result.getString("origin"), new KnowledgeUrlJob.RefreshPolicy(
                        KnowledgeUrlJob.RefreshMode.valueOf(result.getString("refresh_mode")),
                        result.getInt("refresh_interval_seconds"),
                        result.getBoolean("use_conditional_requests"), result.getInt("maximum_redirects"),
                        result.getInt("maximum_bytes")), KnowledgeUrlJob.State.valueOf(result.getString("state")),
                result.getLong("revision"), instant(result.getTimestamp("next_refresh_at")),
                result.getTimestamp("created_at").toInstant(), result.getTimestamp("updated_at").toInstant(),
                instant(result.getTimestamp("archived_at")));
    }

    private KnowledgeUrlEvidence.Observation observation(ResultSet result, int row) throws SQLException {
        return new KnowledgeUrlEvidence.Observation(
                result.getString("id"), result.getString("url_job_id"), result.getString("tenant_id"),
                result.getString("request_url_sha256"), result.getString("final_url"),
                result.getString("final_url_sha256"), result.getInt("http_status"), result.getString("etag"),
                result.getString("last_modified"), result.getString("content_sha256"),
                KnowledgeUrlEvidence.ObservationOutcome.valueOf(result.getString("outcome")),
                result.getString("safe_error_code"), result.getTimestamp("observed_at").toInstant());
    }

    private KnowledgeUrlEvidence.ContentVersion contentVersion(ResultSet result, int row)
            throws SQLException {
        return new KnowledgeUrlEvidence.ContentVersion(
                result.getString("id"), result.getString("url_job_id"),
                result.getString("knowledge_document_id"), result.getString("tenant_id"),
                result.getInt("version"), result.getString("content_sha256"),
                result.getString("object_reference"), result.getString("media_type"),
                result.getString("charset"), result.getLong("byte_size"),
                KnowledgeUrlEvidence.ContentState.valueOf(result.getString("state")),
                result.getString("source_observation_id"), result.getTimestamp("created_at").toInstant(),
                instant(result.getTimestamp("activated_at")));
    }

    private static Timestamp timestamp(Instant value) {
        return value == null ? null : Timestamp.from(value);
    }

    private static Instant instant(Timestamp value) {
        return value == null ? null : value.toInstant();
    }
}

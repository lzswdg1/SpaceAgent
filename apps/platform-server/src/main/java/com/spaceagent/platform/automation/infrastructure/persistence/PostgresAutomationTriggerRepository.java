package com.spaceagent.platform.automation.infrastructure.persistence;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.spaceagent.platform.automation.domain.AutomationTrigger;
import com.spaceagent.platform.automation.domain.AutomationTriggerPersistence;
import com.spaceagent.platform.automation.domain.AutomationTriggerRepository;
import com.spaceagent.platform.automation.domain.AutomationTriggerSource;
import com.spaceagent.platform.automation.domain.AutomationTriggerState;
import com.spaceagent.platform.automation.domain.AutomationTriggerType;
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
public class PostgresAutomationTriggerRepository implements AutomationTriggerRepository {
    private static final String TRIGGER_COLUMNS = """
            id,lineage_id,version,previous_version_id,tenant_id,owner_id,agent_id,description,prompt,
            trigger_type,source_config::text source_config,config_sha256,state,revision,created_at,
            activated_at,archived_at
            """;
    private static final String SUBSCRIPTION_COLUMNS = """
            id,trigger_version_id,trigger_lineage_id,tenant_id,owner_id,trigger_type,
            source_binding_sha256,state,revision,created_at,updated_at,archived_at
            """;
    private static final String OCCURRENCE_COLUMNS = """
            id,trigger_version_id,trigger_lineage_id,subscription_id,tenant_id,owner_id,
            source_event_sha256,payload_sha256,state,occurred_at,admitted_at,revision,created_at,updated_at
            """;
    private static final String DELIVERY_COLUMNS = """
            id,occurrence_id,tenant_id,owner_id,attempt,state,next_attempt_at,claim_token,worker_id,
            lease_until,fencing_token,safe_error_code,evidence_sha256,revision,created_at,updated_at
            """;
    private static final String DEAD_LETTER_COLUMNS = """
            id,occurrence_id,delivery_id,tenant_id,owner_id,reason_code,evidence_sha256,created_at
            """;

    private final JdbcTemplate jdbc;
    private final ObjectMapper json;

    public PostgresAutomationTriggerRepository(JdbcTemplate jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    @Override
    public void insertTrigger(AutomationTrigger trigger) {
        try {
            jdbc.update("""
                    INSERT INTO platform_automation_triggers(
                        id,lineage_id,version,previous_version_id,tenant_id,owner_id,agent_id,
                        description,prompt,trigger_type,source_config,config_sha256,state,revision,
                        created_at,activated_at,archived_at)
                    VALUES(CAST(? AS UUID),CAST(? AS UUID),?,CAST(? AS UUID),?,?,?,?,?,?,CAST(? AS JSONB),
                        ?,?,?,?, ?,?)
                    """, trigger.id(), trigger.lineageId(), trigger.version(), trigger.previousVersionId(),
                    trigger.tenantId(), trigger.ownerId(), trigger.agentId(), trigger.description(),
                    trigger.prompt(), trigger.type().name(), json.writeValueAsString(trigger.source()),
                    trigger.configSha256(), trigger.state().name(), trigger.revision(),
                    timestamp(trigger.createdAt()), timestamp(trigger.activatedAt()),
                    timestamp(trigger.archivedAt()));
        } catch (Exception error) {
            throw new IllegalStateException("Unable to insert Automation Trigger", error);
        }
    }

    @Override
    public Optional<AutomationTrigger> findTrigger(
            String tenantId, String ownerId, String triggerVersionId) {
        return jdbc.query("SELECT " + TRIGGER_COLUMNS
                        + " FROM platform_automation_triggers WHERE id=CAST(? AS UUID)"
                        + " AND tenant_id=? AND owner_id=?",
                this::mapTrigger, triggerVersionId, tenantId, ownerId).stream().findFirst();
    }

    @Override
    public List<AutomationTrigger> findTriggerLineage(
            String tenantId, String ownerId, String lineageId) {
        return jdbc.query("SELECT " + TRIGGER_COLUMNS
                        + " FROM platform_automation_triggers WHERE lineage_id=CAST(? AS UUID)"
                        + " AND tenant_id=? AND owner_id=? ORDER BY version",
                this::mapTrigger, lineageId, tenantId, ownerId);
    }

    @Override
    public List<AutomationTrigger> findByAgent(String tenantId,String ownerId,String agentId){
        return jdbc.query("SELECT "+TRIGGER_COLUMNS+" FROM platform_automation_triggers WHERE tenant_id=? AND owner_id=? AND agent_id=? ORDER BY lineage_id,version",this::mapTrigger,tenantId,ownerId,agentId);
    }

    @Override
    public Optional<AutomationTrigger> updateTrigger(AutomationTrigger trigger, long expectedRevision) {
        return jdbc.query("""
                UPDATE platform_automation_triggers
                   SET state=?,revision=revision+1,activated_at=?,archived_at=?
                 WHERE id=CAST(? AS UUID) AND tenant_id=? AND owner_id=? AND revision=? AND config_sha256=?
                RETURNING """ + " " + TRIGGER_COLUMNS,
                this::mapTrigger, trigger.state().name(), timestamp(trigger.activatedAt()),
                timestamp(trigger.archivedAt()), trigger.id(), trigger.tenantId(), trigger.ownerId(),
                expectedRevision, trigger.configSha256()).stream().findFirst();
    }

    @Override
    public void insertSubscription(AutomationTriggerPersistence.Subscription value) {
        jdbc.update("""
                INSERT INTO platform_automation_trigger_subscriptions(
                    id,trigger_version_id,trigger_lineage_id,tenant_id,owner_id,trigger_type,
                    source_binding_sha256,state,revision,created_at,updated_at,archived_at)
                VALUES(CAST(? AS UUID),CAST(? AS UUID),CAST(? AS UUID),?,?,?,?,?,?,?, ?,?)
                """, value.id(), value.triggerVersionId(), value.triggerLineageId(), value.tenantId(),
                value.ownerId(), value.triggerType().name(), value.sourceBindingSha256(),
                value.state().name(), value.revision(), timestamp(value.createdAt()),
                timestamp(value.updatedAt()), timestamp(value.archivedAt()));
    }

    @Override
    public Optional<AutomationTriggerPersistence.Subscription> findSubscription(
            String tenantId, String ownerId, String subscriptionId) {
        return jdbc.query("SELECT " + SUBSCRIPTION_COLUMNS
                        + " FROM platform_automation_trigger_subscriptions WHERE id=CAST(? AS UUID)"
                        + " AND tenant_id=? AND owner_id=?",
                this::mapSubscription, subscriptionId, tenantId, ownerId).stream().findFirst();
    }

    @Override
    public Optional<AutomationTriggerPersistence.Subscription> findSubscriptionById(String subscriptionId) {
        return jdbc.query("SELECT " + SUBSCRIPTION_COLUMNS
                        + " FROM platform_automation_trigger_subscriptions WHERE id=CAST(? AS UUID)",
                this::mapSubscription, subscriptionId).stream().findFirst();
    }

    @Override
    public Optional<AutomationTriggerPersistence.Subscription> findSubscriptionByTrigger(String triggerVersionId){
        return jdbc.query("SELECT "+SUBSCRIPTION_COLUMNS+" FROM platform_automation_trigger_subscriptions WHERE trigger_version_id=CAST(? AS UUID)",this::mapSubscription,triggerVersionId).stream().findFirst();
    }

    @Override
    public AutomationTriggerPersistence.Occurrence createOrFindOccurrence(
            AutomationTriggerPersistence.Occurrence value) {
        jdbc.update("""
                INSERT INTO platform_automation_trigger_occurrences(
                    id,trigger_version_id,trigger_lineage_id,subscription_id,tenant_id,owner_id,
                    source_event_sha256,payload_sha256,state,occurred_at,admitted_at,revision,created_at,updated_at)
                VALUES(CAST(? AS UUID),CAST(? AS UUID),CAST(? AS UUID),CAST(? AS UUID),?,?,?,?,?,?,?, ?,?,?)
                ON CONFLICT(trigger_lineage_id,source_event_sha256) DO NOTHING
                """, value.id(), value.triggerVersionId(), value.triggerLineageId(), value.subscriptionId(),
                value.tenantId(), value.ownerId(), value.sourceEventSha256(), value.payloadSha256(),
                value.state().name(), timestamp(value.occurredAt()), timestamp(value.admittedAt()),
                value.revision(), timestamp(value.createdAt()), timestamp(value.updatedAt()));
        return jdbc.query("SELECT " + OCCURRENCE_COLUMNS
                        + " FROM platform_automation_trigger_occurrences"
                        + " WHERE trigger_lineage_id=CAST(? AS UUID) AND source_event_sha256=?",
                this::mapOccurrence, value.triggerLineageId(), value.sourceEventSha256())
                .stream().findFirst().orElseThrow();
    }

    @Override
    public Optional<AutomationTriggerPersistence.Occurrence> findOccurrence(
            String tenantId, String ownerId, String occurrenceId) {
        return jdbc.query("SELECT " + OCCURRENCE_COLUMNS
                        + " FROM platform_automation_trigger_occurrences WHERE id=CAST(? AS UUID)"
                        + " AND tenant_id=? AND owner_id=?",
                this::mapOccurrence, occurrenceId, tenantId, ownerId).stream().findFirst();
    }

    @Override
    public Optional<AutomationTriggerPersistence.Occurrence> findOccurrenceById(String occurrenceId) {
        return jdbc.query("SELECT " + OCCURRENCE_COLUMNS
                        + " FROM platform_automation_trigger_occurrences WHERE id=CAST(? AS UUID)",
                this::mapOccurrence, occurrenceId).stream().findFirst();
    }

    @Override public Optional<AutomationTriggerPersistence.Occurrence> claimNextDispatchable(java.time.Instant staleBefore,java.time.Instant now){return jdbc.query("WITH candidate AS (SELECT id FROM platform_automation_trigger_occurrences WHERE state='READY' OR (state='DISPATCHING' AND updated_at<=?) ORDER BY created_at,id LIMIT 1 FOR UPDATE SKIP LOCKED) UPDATE platform_automation_trigger_occurrences o SET state='DISPATCHING',revision=revision+1,updated_at=? FROM candidate WHERE o.id=candidate.id RETURNING o.*",this::mapOccurrence,timestamp(staleBefore),timestamp(now)).stream().findFirst();}

    @Override
    public Optional<AutomationTriggerPersistence.Occurrence> updateOccurrence(
            AutomationTriggerPersistence.Occurrence value, long expectedRevision) {
        return jdbc.query("""
                UPDATE platform_automation_trigger_occurrences
                   SET state=?,admitted_at=?,revision=revision+1,updated_at=?
                 WHERE id=CAST(? AS UUID) AND tenant_id=? AND owner_id=? AND revision=?
                RETURNING """ + " " + OCCURRENCE_COLUMNS,
                this::mapOccurrence, value.state().name(), timestamp(value.admittedAt()),
                timestamp(value.updatedAt()), value.id(), value.tenantId(), value.ownerId(),
                expectedRevision).stream().findFirst();
    }

    @Override
    public void insertDelivery(AutomationTriggerPersistence.Delivery value) {
        jdbc.update("""
                INSERT INTO platform_automation_trigger_deliveries(
                    id,occurrence_id,tenant_id,owner_id,attempt,state,next_attempt_at,claim_token,
                    worker_id,lease_until,fencing_token,safe_error_code,evidence_sha256,revision,
                    created_at,updated_at)
                VALUES(CAST(? AS UUID),CAST(? AS UUID),?,?,?,?,?,CAST(? AS UUID),?,?,?,?,?,?,?,?)
                """, value.id(), value.occurrenceId(), value.tenantId(), value.ownerId(), value.attempt(),
                value.state().name(), timestamp(value.nextAttemptAt()), value.claimToken(), value.workerId(),
                timestamp(value.leaseUntil()), value.fencingToken(), value.safeErrorCode(),
                value.evidenceSha256(), value.revision(), timestamp(value.createdAt()),
                timestamp(value.updatedAt()));
    }

    @Override
    public AutomationTriggerPersistence.Delivery createOrFindDelivery(
            AutomationTriggerPersistence.Delivery value) {
        jdbc.update("""
                INSERT INTO platform_automation_trigger_deliveries(
                    id,occurrence_id,tenant_id,owner_id,attempt,state,next_attempt_at,claim_token,
                    worker_id,lease_until,fencing_token,safe_error_code,evidence_sha256,revision,
                    created_at,updated_at)
                VALUES(CAST(? AS UUID),CAST(? AS UUID),?,?,?,?,?,CAST(? AS UUID),?,?,?,?,?,?,?,?)
                ON CONFLICT(occurrence_id,attempt) DO NOTHING
                """, value.id(), value.occurrenceId(), value.tenantId(), value.ownerId(), value.attempt(),
                value.state().name(), timestamp(value.nextAttemptAt()), value.claimToken(), value.workerId(),
                timestamp(value.leaseUntil()), value.fencingToken(), value.safeErrorCode(),
                value.evidenceSha256(), value.revision(), timestamp(value.createdAt()),
                timestamp(value.updatedAt()));
        return findLatestDelivery(value.occurrenceId()).orElseThrow();
    }

    @Override
    public Optional<AutomationTriggerPersistence.Delivery> findDelivery(
            String tenantId, String ownerId, String deliveryId) {
        return jdbc.query("SELECT " + DELIVERY_COLUMNS
                        + " FROM platform_automation_trigger_deliveries WHERE id=CAST(? AS UUID)"
                        + " AND tenant_id=? AND owner_id=?",
                this::mapDelivery, deliveryId, tenantId, ownerId).stream().findFirst();
    }

    @Override
    public Optional<AutomationTriggerPersistence.Delivery> findLatestDelivery(String occurrenceId) {
        return jdbc.query("SELECT " + DELIVERY_COLUMNS
                        + " FROM platform_automation_trigger_deliveries WHERE occurrence_id=CAST(? AS UUID)"
                        + " ORDER BY attempt DESC LIMIT 1",
                this::mapDelivery, occurrenceId).stream().findFirst();
    }

    @Override
    public Optional<AutomationTriggerPersistence.Delivery> updateDelivery(
            AutomationTriggerPersistence.Delivery value, long expectedRevision) {
        return jdbc.query("""
                UPDATE platform_automation_trigger_deliveries
                   SET state=?,next_attempt_at=?,claim_token=CAST(? AS UUID),worker_id=?,lease_until=?,
                       fencing_token=?,safe_error_code=?,evidence_sha256=?,revision=revision+1,updated_at=?
                 WHERE id=CAST(? AS UUID) AND tenant_id=? AND owner_id=? AND revision=?
                RETURNING """ + " " + DELIVERY_COLUMNS,
                this::mapDelivery, value.state().name(), timestamp(value.nextAttemptAt()), value.claimToken(),
                value.workerId(), timestamp(value.leaseUntil()), value.fencingToken(), value.safeErrorCode(),
                value.evidenceSha256(), timestamp(value.updatedAt()), value.id(), value.tenantId(),
                value.ownerId(), expectedRevision).stream().findFirst();
    }

    @Override
    public AutomationTriggerPersistence.DeadLetter createOrFindDeadLetter(
            AutomationTriggerPersistence.DeadLetter value) {
        jdbc.update("""
                INSERT INTO platform_automation_trigger_dead_letters(
                    id,occurrence_id,delivery_id,tenant_id,owner_id,reason_code,evidence_sha256,created_at)
                VALUES(CAST(? AS UUID),CAST(? AS UUID),CAST(? AS UUID),?,?,?,?,?)
                ON CONFLICT(occurrence_id) DO NOTHING
                """, value.id(), value.occurrenceId(), value.deliveryId(), value.tenantId(), value.ownerId(),
                value.reasonCode(), value.evidenceSha256(), timestamp(value.createdAt()));
        return jdbc.query("SELECT " + DEAD_LETTER_COLUMNS
                        + " FROM platform_automation_trigger_dead_letters WHERE occurrence_id=CAST(? AS UUID)",
                this::mapDeadLetter, value.occurrenceId()).stream().findFirst().orElseThrow();
    }

    private AutomationTrigger mapTrigger(ResultSet row, int index) throws SQLException {
        AutomationTriggerType type = AutomationTriggerType.valueOf(row.getString("trigger_type"));
        try {
            return new AutomationTrigger(row.getString("id"), row.getString("lineage_id"),
                    row.getInt("version"), row.getString("previous_version_id"),
                    row.getString("tenant_id"), row.getString("owner_id"), row.getString("agent_id"),
                    row.getString("description"), row.getString("prompt"), type,
                    json.readValue(row.getString("source_config"), sourceClass(type)),
                    row.getString("config_sha256"), AutomationTriggerState.valueOf(row.getString("state")),
                    row.getLong("revision"), instant(row.getTimestamp("created_at")),
                    instant(row.getTimestamp("activated_at")), instant(row.getTimestamp("archived_at")));
        } catch (Exception error) {
            throw new SQLException("Unable to map Automation Trigger", error);
        }
    }

    private AutomationTriggerPersistence.Subscription mapSubscription(ResultSet row, int index)
            throws SQLException {
        return new AutomationTriggerPersistence.Subscription(row.getString("id"),
                row.getString("trigger_version_id"), row.getString("trigger_lineage_id"),
                row.getString("tenant_id"), row.getString("owner_id"),
                AutomationTriggerType.valueOf(row.getString("trigger_type")),
                row.getString("source_binding_sha256"),
                AutomationTriggerPersistence.SubscriptionState.valueOf(row.getString("state")),
                row.getLong("revision"), instant(row.getTimestamp("created_at")),
                instant(row.getTimestamp("updated_at")), instant(row.getTimestamp("archived_at")));
    }

    private AutomationTriggerPersistence.Occurrence mapOccurrence(ResultSet row, int index)
            throws SQLException {
        return new AutomationTriggerPersistence.Occurrence(row.getString("id"),
                row.getString("trigger_version_id"), row.getString("trigger_lineage_id"),
                row.getString("subscription_id"), row.getString("tenant_id"), row.getString("owner_id"),
                row.getString("source_event_sha256"), row.getString("payload_sha256"),
                AutomationTriggerPersistence.OccurrenceState.valueOf(row.getString("state")),
                instant(row.getTimestamp("occurred_at")), instant(row.getTimestamp("admitted_at")),
                row.getLong("revision"), instant(row.getTimestamp("created_at")),
                instant(row.getTimestamp("updated_at")));
    }

    private AutomationTriggerPersistence.Delivery mapDelivery(ResultSet row, int index)
            throws SQLException {
        return new AutomationTriggerPersistence.Delivery(row.getString("id"), row.getString("occurrence_id"),
                row.getString("tenant_id"), row.getString("owner_id"), row.getInt("attempt"),
                AutomationTriggerPersistence.DeliveryState.valueOf(row.getString("state")),
                instant(row.getTimestamp("next_attempt_at")), row.getString("claim_token"),
                row.getString("worker_id"), instant(row.getTimestamp("lease_until")),
                row.getLong("fencing_token"), row.getString("safe_error_code"),
                row.getString("evidence_sha256"), row.getLong("revision"),
                instant(row.getTimestamp("created_at")), instant(row.getTimestamp("updated_at")));
    }

    private AutomationTriggerPersistence.DeadLetter mapDeadLetter(ResultSet row, int index)
            throws SQLException {
        return new AutomationTriggerPersistence.DeadLetter(row.getString("id"),
                row.getString("occurrence_id"), row.getString("delivery_id"),
                row.getString("tenant_id"), row.getString("owner_id"), row.getString("reason_code"),
                row.getString("evidence_sha256"), instant(row.getTimestamp("created_at")));
    }

    private static Class<? extends AutomationTriggerSource> sourceClass(AutomationTriggerType type) {
        return switch (type) {
            case WEBHOOK -> AutomationTriggerSource.Webhook.class;
            case REPOSITORY -> AutomationTriggerSource.Repository.class;
            case TASK_COMPLETION -> AutomationTriggerSource.TaskCompletion.class;
            case FOLLOW_UP -> AutomationTriggerSource.FollowUp.class;
            default -> throw new IllegalArgumentException("Unsupported event Trigger type");
        };
    }

    private static Timestamp timestamp(Instant value) {
        return value == null ? null : Timestamp.from(value);
    }

    private static Instant instant(Timestamp value) {
        return value == null ? null : value.toInstant();
    }
}

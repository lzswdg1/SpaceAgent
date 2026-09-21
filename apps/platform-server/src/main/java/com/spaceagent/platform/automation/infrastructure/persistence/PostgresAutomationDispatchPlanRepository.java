package com.spaceagent.platform.automation.infrastructure.persistence;

import com.spaceagent.platform.automation.domain.AutomationDispatchPlan;
import com.spaceagent.platform.automation.domain.AutomationDispatchPlanRepository;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.Optional;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
@ConditionalOnProperty(prefix="platform",name="persistence",havingValue="postgres")
public class PostgresAutomationDispatchPlanRepository implements AutomationDispatchPlanRepository {
    private static final String C="id,occurrence_id,delivery_id,tenant_id,owner_id,operation_hash,approval_id,conversation_id,dispatch_run_id,continuation_id,phase,safe_error_code,revision,created_at,updated_at";
    private final JdbcTemplate jdbc;
    public PostgresAutomationDispatchPlanRepository(JdbcTemplate jdbc){this.jdbc=jdbc;}
    @Override public AutomationDispatchPlan createOrFind(AutomationDispatchPlan value){jdbc.update("INSERT INTO platform_automation_dispatch_plans(id,occurrence_id,delivery_id,tenant_id,owner_id,operation_hash,approval_id,conversation_id,dispatch_run_id,continuation_id,phase,safe_error_code,revision,created_at,updated_at) VALUES(CAST(? AS UUID),CAST(? AS UUID),CAST(? AS UUID),?,?,?,CAST(? AS UUID),?,?,CAST(? AS UUID),?,?,1,?,?) ON CONFLICT(occurrence_id) DO NOTHING",value.id(),value.occurrenceId(),value.deliveryId(),value.tenantId(),value.ownerId(),value.operationHash(),value.approvalId(),value.conversationId(),value.dispatchRunId(),value.continuationId(),value.phase().name(),value.safeErrorCode(),ts(value.createdAt()),ts(value.updatedAt()));return findByOccurrence(value.tenantId(),value.ownerId(),value.occurrenceId()).orElseThrow();}
    @Override public Optional<AutomationDispatchPlan> findByOccurrence(String tenant,String owner,String occurrence){return jdbc.query("SELECT "+C+" FROM platform_automation_dispatch_plans WHERE occurrence_id=CAST(? AS UUID) AND tenant_id=? AND owner_id=?",this::map,occurrence,tenant,owner).stream().findFirst();}
    @Override public Optional<AutomationDispatchPlan> update(AutomationDispatchPlan value,long revision){return jdbc.query("UPDATE platform_automation_dispatch_plans SET delivery_id=CAST(? AS UUID),approval_id=CAST(? AS UUID),phase=?,safe_error_code=?,revision=revision+1,updated_at=? WHERE id=CAST(? AS UUID) AND tenant_id=? AND owner_id=? AND revision=? AND operation_hash=? RETURNING "+C,this::map,value.deliveryId(),value.approvalId(),value.phase().name(),value.safeErrorCode(),ts(value.updatedAt()),value.id(),value.tenantId(),value.ownerId(),revision,value.operationHash()).stream().findFirst();}
    private AutomationDispatchPlan map(ResultSet row,int number)throws SQLException{return new AutomationDispatchPlan(row.getString("id"),row.getString("occurrence_id"),row.getString("delivery_id"),row.getString("tenant_id"),row.getString("owner_id"),row.getString("operation_hash"),row.getString("approval_id"),row.getString("conversation_id"),row.getString("dispatch_run_id"),row.getString("continuation_id"),AutomationDispatchPlan.Phase.valueOf(row.getString("phase")),row.getString("safe_error_code"),row.getLong("revision"),row.getTimestamp("created_at").toInstant(),row.getTimestamp("updated_at").toInstant());}
    private static Timestamp ts(java.time.Instant value){return value==null?null:Timestamp.from(value);}
}

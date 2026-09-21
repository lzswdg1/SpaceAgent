package com.spaceagent.platform.tooling.infrastructure.persistence;
import com.spaceagent.platform.tooling.api.ToolingAdministrationUsageApi;
import com.spaceagent.platform.shared.api.AdministrationUsage.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import java.sql.Timestamp;
import java.util.*;

@Repository @ConditionalOnProperty(prefix="platform",name="persistence",havingValue="postgres")
public class PostgresToolingAdministrationUsage implements ToolingAdministrationUsageApi {
    private final JdbcTemplate jdbc;public PostgresToolingAdministrationUsage(JdbcTemplate jdbc){this.jdbc=jdbc;}
    private static final String FACTS=" " + """
      FROM (SELECT id,usage_tenant_id tenant_id,usage_actor_id actor_id,agent_run_id run_id,tool_name,status,
        started_at AT TIME ZONE 'UTC' created_at,CASE WHEN completed_at IS NOT NULL AND status IN ('SUCCEEDED','FAILED','CANCELLED')
        THEN GREATEST(0,EXTRACT(EPOCH FROM(completed_at-started_at))*1000)::bigint END duration_ms FROM platform_tool_execution_ledger) facts
      """;
    private static final String WHERE=" WHERE created_at>=? AND created_at<? AND (?::varchar IS NULL OR actor_id=?) AND (?::varchar IS NULL OR tenant_id=?) AND (?::varchar IS NULL OR status=?) ";
    private Object[] args(Filter f){return new Object[]{Timestamp.from(f.from()),Timestamp.from(f.to()),f.userId(),f.userId(),f.tenantId(),f.tenantId(),f.status(),f.status()};}
    public Summary summary(Filter f){Map<String,Long> states=new LinkedHashMap<>();jdbc.query("SELECT status,count(*)"+FACTS+WHERE+" GROUP BY status",r->{states.put(r.getString(1),r.getLong(2));},args(f));
        long unattributed=jdbc.queryForObject("SELECT count(*)"+FACTS+" WHERE created_at>=? AND created_at<? AND (actor_id IS NULL OR tenant_id IS NULL)",Long.class,Timestamp.from(f.from()),Timestamp.from(f.to()));
        return jdbc.queryForObject("SELECT count(*) n,sum(duration_ms) duration,count(*) FILTER(WHERE duration_ms IS NULL) missing"+FACTS+WHERE,
            (r,n)->new Summary("TOOL",r.getLong("n"),states,null,null,null,null,r.getLong("n"),r.getLong("n"),r.getLong("missing"),r.getBigDecimal("duration")==null?null:r.getBigDecimal("duration").longValueExact(),unattributed,
            "EXISTING_TOOL_LEDGER_ROWS; ALL_STATES; TOKEN_AND_COST_NOT_COLLECTED; DURATION_LEDGER_WALL_TIME; MCP_INVOCATIONS_NOT_DOUBLE_COUNTED"),args(f));}
    public History history(Filter f){var values=new ArrayList<>(Arrays.asList(args(f)));values.add(f.pageSize());values.add(f.page()*f.pageSize());
        var rows=jdbc.query("SELECT *"+FACTS+WHERE+" ORDER BY created_at DESC,id LIMIT ? OFFSET ?",(r,n)->new Call("TOOL",r.getString("id"),r.getString("tenant_id"),r.getString("actor_id"),r.getString("run_id"),null,r.getString("tool_name"),r.getString("status"),null,null,null,null,r.getObject("duration_ms",Long.class),r.getTimestamp("created_at").toInstant()),values.toArray());
        return new History(rows,jdbc.queryForObject("SELECT count(*)"+FACTS+WHERE,Long.class,args(f)));}
}

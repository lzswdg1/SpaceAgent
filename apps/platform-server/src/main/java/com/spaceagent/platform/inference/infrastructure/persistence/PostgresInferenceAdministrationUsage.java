package com.spaceagent.platform.inference.infrastructure.persistence;
import com.spaceagent.platform.inference.api.InferenceAdministrationUsageApi;
import com.spaceagent.platform.shared.api.AdministrationUsage.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import java.sql.*;
import java.util.*;

/** Only Inference-owned facts; never fetches bodies, response vectors, prompts or provider secrets. */
@Repository @ConditionalOnProperty(prefix="platform",name="persistence",havingValue="postgres")
public class PostgresInferenceAdministrationUsage implements InferenceAdministrationUsageApi {
    private final JdbcTemplate jdbc;public PostgresInferenceAdministrationUsage(JdbcTemplate jdbc){this.jdbc=jdbc;}
    private String facts(String kind){return switch(kind){
        case "MODEL"->"""
          SELECT id,usage_tenant_id tenant_id,usage_actor_id actor_id,agent_run_id run_id,provider_id,model_id,status,
           COALESCE(CASE WHEN input_tokens>=0 THEN input_tokens END,CASE WHEN usage_payload->>'inputTokens' ~ '^[0-9]{1,18}$' THEN (usage_payload->>'inputTokens')::bigint END) input_tokens,
           COALESCE(CASE WHEN output_tokens>=0 THEN output_tokens END,CASE WHEN usage_payload->>'outputTokens' ~ '^[0-9]{1,18}$' THEN (usage_payload->>'outputTokens')::bigint END) output_tokens,
           cost_micros,CASE WHEN status IN ('SUCCEEDED','FAILED','TIMED_OUT','CANCELLED') THEN GREATEST(0,EXTRACT(EPOCH FROM(updated_at-created_at))*1000)::bigint END duration_ms,created_at
          FROM platform_model_call_ledger
          """;
        case "EMBEDDING"->"""
          SELECT id,tenant_id,actor_id,NULL::varchar run_id,provider_id,model_id,state status,input_tokens,
           CASE WHEN input_tokens IS NOT NULL THEN 0::bigint END output_tokens,cost_micros,
           CASE WHEN state IN ('SUCCEEDED','REJECTED') THEN GREATEST(0,EXTRACT(EPOCH FROM(updated_at-created_at))*1000)::bigint END duration_ms,created_at
          FROM platform_embedding_calls
          """;
        default->throw new IllegalArgumentException("Unsupported model usage kind");};}
    private static final String WHERE=" WHERE created_at>=? AND created_at<? AND (?::varchar IS NULL OR actor_id=?) AND (?::varchar IS NULL OR tenant_id=?) AND (?::varchar IS NULL OR status=?) ";
    private static Object[] args(Filter f){return new Object[]{Timestamp.from(f.from()),Timestamp.from(f.to()),f.userId(),f.userId(),f.tenantId(),f.tenantId(),f.status(),f.status()};}
    public Summary summary(String kind,Filter f){String source=" FROM ("+facts(kind)+") facts";Map<String,Long> states=new LinkedHashMap<>();
        jdbc.query("SELECT status,count(*) n"+source+WHERE+" GROUP BY status",r->{states.put(r.getString(1),r.getLong(2));},args(f));
        long unattributed=jdbc.queryForObject("SELECT count(*)"+source+" WHERE created_at>=? AND created_at<? AND (actor_id IS NULL OR tenant_id IS NULL)",Long.class,Timestamp.from(f.from()),Timestamp.from(f.to()));
        return jdbc.queryForObject("""
          SELECT count(*) n,sum(input_tokens) ins,sum(output_tokens) outs,sum(cost_micros) cost,sum(duration_ms) duration,
            count(*) FILTER(WHERE input_tokens IS NULL OR output_tokens IS NULL) missing_usage,
            count(*) FILTER(WHERE cost_micros IS NULL) missing_cost,count(*) FILTER(WHERE duration_ms IS NULL) missing_duration
          """+source+WHERE,(r,n)->new Summary(kind,r.getLong("n"),states,number(r,"ins"),number(r,"outs"),r.getBigDecimal("cost"),"USD",
            r.getLong("missing_usage"),r.getLong("missing_cost"),r.getLong("missing_duration"),number(r,"duration"),unattributed,
            "EXISTING_OWNER_LEDGER_ROWS; ALL_STATES; KNOWN_SUBTOTALS_ONLY; DURATION_LEDGER_WALL_TIME; MISSING_ATTRIBUTION_NOT_ASSIGNED; DELETED_HISTORY_NOT_RECONSTRUCTED"),args(f));}
    private static Long number(ResultSet r,String field)throws SQLException{var value=r.getBigDecimal(field);return value==null?null:value.longValueExact();}
    public History history(String kind,Filter f){String source=" FROM ("+facts(kind)+") facts";var values=new ArrayList<>(Arrays.asList(args(f)));values.add(f.pageSize());values.add(f.page()*f.pageSize());
        var rows=jdbc.query("SELECT *"+source+WHERE+" ORDER BY created_at DESC,id LIMIT ? OFFSET ?",(r,n)->new Call(kind,r.getString("id"),r.getString("tenant_id"),r.getString("actor_id"),r.getString("run_id"),r.getString("provider_id"),r.getString("model_id"),r.getString("status"),r.getObject("input_tokens",Long.class),r.getObject("output_tokens",Long.class),r.getBigDecimal("cost_micros"),r.getBigDecimal("cost_micros")==null?null:"USD",r.getObject("duration_ms",Long.class),r.getTimestamp("created_at").toInstant()),values.toArray());
        return new History(rows,jdbc.queryForObject("SELECT count(*)"+source+WHERE,Long.class,args(f)));}
}

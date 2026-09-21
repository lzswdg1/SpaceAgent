package com.spaceagent.platform.runtime.infrastructure.persistence;

import com.spaceagent.platform.runtime.api.RuntimeResourceObservationApi;
import com.spaceagent.shared.exception.BusinessException;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import java.sql.Timestamp;
import java.time.*;

/** Owner-only read projection of immutable, redacted execution observations. */
@Repository
@ConditionalOnProperty(prefix="platform",name="persistence",havingValue="postgres")
public class PostgresRuntimeResourceObservationQueries implements RuntimeResourceObservationApi {
    private final JdbcTemplate jdbc;
    public PostgresRuntimeResourceObservationQueries(JdbcTemplate jdbc){this.jdbc=jdbc;}
    @Override @Transactional(readOnly=true)
    public Summary summary(String tenant,String user,Instant from,Instant to){
        if(from==null||to==null||!from.isBefore(to)||Duration.between(from,to).compareTo(Duration.ofDays(90))>0
                ||tenant!=null&&!tenant.matches("[A-Za-z0-9_.:-]{1,36}")||user!=null&&!user.matches("[A-Za-z0-9_.:-]{1,36}"))
            throw new BusinessException("Invalid resource observation range",HttpStatus.BAD_REQUEST);
        String sql="""
            WITH facts AS (
              SELECT e.payload,e.created_at,
                (e.payload->'resourceMetrics'->>'cpuUsageNanos')::numeric cpu,
                (e.payload->'resourceMetrics'->>'maxObservedMemoryBytes')::bigint memory,
                (e.payload->'resourceMetrics'->>'networkRxBytes')::numeric rx,
                (e.payload->'resourceMetrics'->>'networkTxBytes')::numeric tx,
                (e.payload->'resourceMetrics'->>'workspaceApparentBytes')::numeric bytes
              FROM platform_run_events e JOIN platform_agent_runs r ON r.id=e.agent_run_id
              WHERE e.event_type='RESOURCE_OBSERVED' AND e.created_at>=? AND e.created_at<?
                AND (?::varchar IS NULL OR r.tenant_id=?) AND (?::varchar IS NULL OR r.owner_id=?)
            ), latest AS (
              SELECT DISTINCT ON(payload->>'workspaceId') bytes FROM facts
              WHERE payload->>'workspaceId' IS NOT NULL AND bytes IS NOT NULL ORDER BY payload->>'workspaceId',created_at DESC
            )
            SELECT count(*) observations,sum(cpu) cpu,max(memory) memory,sum(rx) rx,sum(tx) tx,
              (SELECT sum(bytes) FROM latest) workspace_bytes,
              count(*) FILTER(WHERE cpu IS NULL) missing_cpu,count(*) FILTER(WHERE memory IS NULL) missing_memory,
              count(*) FILTER(WHERE rx IS NULL OR tx IS NULL) missing_network,count(*) FILTER(WHERE bytes IS NULL) missing_workspace FROM facts
            """;
        return jdbc.queryForObject(sql,(rs,n)->new Summary(rs.getLong("observations"),rs.getBigDecimal("cpu"),rs.getObject("memory",Long.class),
                rs.getBigDecimal("rx"),rs.getBigDecimal("tx"),rs.getBigDecimal("workspace_bytes"),rs.getLong("missing_cpu"),rs.getLong("missing_memory"),
                rs.getLong("missing_network"),rs.getLong("missing_workspace"),
                "RECORDED_RUN_BOUND_OCI_ONLY; PARTIAL_RUNNING_CPU_SAMPLES; MAX_CGROUP_ACCOUNTED_MEMORY_NOT_PEAK_RSS; LATEST_IN_WINDOW_APPARENT_WORKSPACE_BYTES_NOT_ALLOCATED_OR_CURRENT_INVENTORY; REMOTE_PROVIDER_RESOURCES_NOT_MEASURED",
                from,to,Instant.now()),Timestamp.from(from),Timestamp.from(to),tenant,tenant,user,user);
    }
}

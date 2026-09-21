package com.spaceagent.platform.governance.infrastructure.persistence;
import com.spaceagent.platform.governance.domain.BusinessEvidenceRepository;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.*;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;

@Repository @ConditionalOnProperty(prefix="platform",name="persistence",havingValue="postgres")
public class PostgresBusinessEvidenceRepository implements BusinessEvidenceRepository {
    private final JdbcTemplate jdbc;public PostgresBusinessEvidenceRepository(JdbcTemplate jdbc){this.jdbc=jdbc;}
    @Transactional(propagation=Propagation.REQUIRES_NEW) public void admit(String id,String actor,String tenant,String kind,String method,String route,String resourceKind,String resource){
        jdbc.update("INSERT INTO platform_governance_business_attempts(id,actor_id,tenant_id,actor_kind,method,route,resource_kind,resource_id) VALUES (?,?,?,?,?,?,?,?)",id,actor,tenant,kind,method,route,resourceKind,resource);}
    @Transactional(propagation=Propagation.REQUIRES_NEW) public void finish(String id,int status,String outcome){
        jdbc.update("INSERT INTO platform_governance_business_outcomes(attempt_id,http_status,outcome) VALUES (?,?,?) ON CONFLICT DO NOTHING",id,status,outcome);}
    private static final String FROM=" FROM platform_governance_business_attempts a LEFT JOIN platform_governance_business_outcomes o ON o.attempt_id=a.id ";
    private static final String WHERE=" WHERE a.created_at>=? AND a.created_at<? AND (?::varchar IS NULL OR a.actor_id=?) AND (?::varchar IS NULL OR a.tenant_id=?) AND (?::varchar IS NULL OR COALESCE(o.outcome,'UNCONFIRMED')=?) ";
    private Object[] args(String actor,String tenant,String outcome,Instant from,Instant to){return new Object[]{Timestamp.from(from),Timestamp.from(to),actor,actor,tenant,tenant,outcome,outcome};}
    public List<Row> list(String actor,String tenant,String outcome,Instant from,Instant to,int offset,int limit){var args=new ArrayList<>(Arrays.asList(args(actor,tenant,outcome,from,to)));args.add(limit);args.add(offset);
        return jdbc.query("SELECT a.*,COALESCE(o.outcome,'UNCONFIRMED') outcome,o.http_status,o.created_at observed_at"+FROM+WHERE+"ORDER BY a.created_at DESC,a.id LIMIT ? OFFSET ?",(r,n)->new Row(r.getString("id"),r.getString("actor_id"),r.getString("tenant_id"),r.getString("actor_kind"),r.getString("method"),r.getString("route"),r.getString("resource_kind"),r.getString("resource_id"),r.getString("outcome"),r.getObject("http_status",Integer.class),r.getTimestamp("created_at").toInstant(),r.getTimestamp("observed_at")==null?null:r.getTimestamp("observed_at").toInstant()),args.toArray());}
    public long count(String actor,String tenant,String outcome,Instant from,Instant to){return jdbc.queryForObject("SELECT count(*)"+FROM+WHERE,Long.class,args(actor,tenant,outcome,from,to));}
}

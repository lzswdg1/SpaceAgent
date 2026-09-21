package com.spaceagent.platform.integration.infrastructure.persistence;
import com.spaceagent.platform.integration.domain.ProjectStartRequestRepository;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import java.sql.*;
import java.time.Instant;
import java.util.Optional;
@Repository @ConditionalOnProperty(prefix="platform",name="persistence",havingValue="postgres")
public class PostgresProjectStartRequestRepository implements ProjectStartRequestRepository {
    private final JdbcTemplate jdbc;
    public PostgresProjectStartRequestRepository(JdbcTemplate jdbc){this.jdbc=jdbc;}
    public Optional<Request> find(String tenant,String owner,String kind,String key){return jdbc.query(
            "SELECT r.* FROM platform_project_start_requests r JOIN platform_project_start_request_keys k ON k.request_id=r.id WHERE k.tenant_id=? AND k.owner_id=? AND k.kind=? AND k.idempotency_hash=?",
            this::map,tenant,owner,kind,key).stream().findFirst();}
    public Optional<Request> pendingRoot(String tenant,String owner,String hash){return jdbc.query(
            "SELECT * FROM platform_project_start_requests WHERE tenant_id=? AND owner_id=? AND kind='GITHUB_ROOT' AND request_hash=? AND state='PENDING'",
            this::map,tenant,owner,hash).stream().findFirst();}
    public boolean insert(Request r){boolean created=jdbc.update("""
            INSERT INTO platform_project_start_requests(id,tenant_id,owner_id,kind,idempotency_hash,request_hash,state,project_id,result_json,created_at,updated_at)
            VALUES(CAST(? AS UUID),?,?,?,?,?,'PENDING',CAST(? AS UUID),NULL,?,?) ON CONFLICT DO NOTHING
            """,r.id(),r.tenantId(),r.ownerId(),r.kind(),r.keyHash(),r.requestHash(),r.projectId(),Timestamp.from(r.createdAt()),Timestamp.from(r.updatedAt()))==1;
        if(created&&!alias(r,r.keyHash()))throw new com.spaceagent.shared.exception.BusinessException("Startup request key is already in use",org.springframework.http.HttpStatus.CONFLICT,"PROJECT_START_IDEMPOTENCY_CONFLICT");
        return created;
    }
    public boolean alias(Request r,String key){return jdbc.update("""
            INSERT INTO platform_project_start_request_keys(tenant_id,owner_id,kind,idempotency_hash,request_id)
            VALUES(?,?,?,?,CAST(? AS UUID)) ON CONFLICT DO NOTHING
            """,r.tenantId(),r.ownerId(),r.kind(),key,r.id())==1;}
    public void bindProject(String id,String project,Instant at){jdbc.update("UPDATE platform_project_start_requests SET project_id=CAST(? AS UUID),updated_at=? WHERE id=CAST(? AS UUID) AND state='PENDING'",project,Timestamp.from(at),id);}
    public void complete(String id,String result,Instant at){jdbc.update("UPDATE platform_project_start_requests SET state='COMPLETED',result_json=CAST(? AS JSONB),updated_at=? WHERE id=CAST(? AS UUID) AND state='PENDING'",result,Timestamp.from(at),id);}
    private Request map(ResultSet r,int row)throws SQLException{return new Request(r.getString("id"),r.getString("tenant_id"),r.getString("owner_id"),r.getString("kind"),r.getString("idempotency_hash"),r.getString("request_hash"),r.getString("state"),r.getString("project_id"),r.getString("result_json"),r.getTimestamp("created_at").toInstant(),r.getTimestamp("updated_at").toInstant());}
}

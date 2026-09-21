package com.spaceagent.platform.agent.infrastructure.persistence;
import com.spaceagent.platform.agent.api.AgentReviewEvidenceApi;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Administrator projection deliberately excludes proposed names, prompt/tool configuration and decision notes. */
@Repository @ConditionalOnProperty(prefix="platform",name="persistence",havingValue="postgres")
public class PostgresAgentReviewEvidence implements AgentReviewEvidenceApi {
    private final JdbcTemplate jdbc;public PostgresAgentReviewEvidence(JdbcTemplate jdbc){this.jdbc=jdbc;}
    private static final String WHERE=" WHERE (?::varchar IS NULL OR agent_owner_id=? OR requested_by=? OR closed_by=?) AND (?::varchar IS NULL OR tenant_id=?) ";
    public Page reviews(String user,String tenant,int page,int size){
        if(page<0||page>10000||size<1||size>100)throw new IllegalArgumentException("Invalid review page");
        var items=jdbc.query("SELECT id,agent_id,tenant_id,agent_owner_id,requested_by,closed_by,state,base_agent_revision,applied_agent_revision,revision,created_at,closed_at FROM platform_agent_configuration_change_requests"+WHERE+" ORDER BY created_at DESC,id LIMIT ? OFFSET ?",
            (r,n)->new Review(r.getString(1),r.getString(2),r.getString(3),r.getString(4),r.getString(5),r.getString(6),r.getString(7),r.getLong(8),r.getObject(9,Long.class),r.getLong(10),r.getTimestamp(11).toInstant(),r.getTimestamp(12)==null?null:r.getTimestamp(12).toInstant()),user,user,user,user,tenant,tenant,size,page*size);
        return new Page(items,jdbc.queryForObject("SELECT count(*) FROM platform_agent_configuration_change_requests"+WHERE,Long.class,user,user,user,user,tenant,tenant));
    }
}

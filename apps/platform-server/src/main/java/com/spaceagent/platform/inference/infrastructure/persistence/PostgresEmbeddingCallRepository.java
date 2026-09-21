package com.spaceagent.platform.inference.infrastructure.persistence;

import com.spaceagent.platform.inference.domain.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import java.sql.*;
import java.util.Optional;

@Repository
@ConditionalOnProperty(prefix="platform",name="persistence",havingValue="postgres")
public class PostgresEmbeddingCallRepository implements EmbeddingCallRepository {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    public PostgresEmbeddingCallRepository(JdbcTemplate jdbc) {
        this.jdbc=jdbc; tx=new TransactionTemplate(new DataSourceTransactionManager(java.util.Objects.requireNonNull(jdbc.getDataSource())));
        tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }
    public Decision claim(EmbeddingCall c) {
        return tx.execute(s->{
            int inserted=jdbc.update("""
                INSERT INTO platform_embedding_calls(id,tenant_id,actor_id,operation_key,request_hash,provider_id,model_id,
                    dimensions,price_id,input_rate,state,deadline,created_at,updated_at)
                VALUES (?,?,?,?,?,?,?,?,CAST(? AS UUID),?,'PREPARED',clock_timestamp()+interval '120 seconds',clock_timestamp(),clock_timestamp())
                ON CONFLICT(tenant_id,actor_id,operation_key) DO NOTHING
                """,c.id(),c.tenantId(),c.actorId(),c.operationKey(),c.requestHash(),c.providerId(),c.modelId(),c.dimensions(),c.priceId(),c.inputRate());
            var existing=jdbc.query("SELECT * FROM platform_embedding_calls WHERE tenant_id=? AND actor_id=? AND operation_key=?",
                    this::map,c.tenantId(),c.actorId(),c.operationKey()).getFirst();
            if(!existing.requestHash().equals(c.requestHash())) throw new IllegalStateException("Embedding idempotency conflict");
            return new Decision(expireAndRead(existing.id()).orElseThrow(),inserted==1);
        });
    }
    public Optional<EmbeddingCall> find(String id) {
        return tx.execute(s->expireAndRead(id));
    }
    private Optional<EmbeddingCall> expireAndRead(String id) {
            jdbc.update("""
                UPDATE platform_embedding_calls c SET state='REJECTED',response_erased=true,safe_code='EMBEDDING_OUTPUT_ERASED'
                WHERE c.id=? AND c.state='PREPARED' AND EXISTS(SELECT 1 FROM platform_embedding_output_tombstones t
                  WHERE t.tenant_id=c.tenant_id AND t.actor_id=c.actor_id AND starts_with(c.operation_key,t.operation_prefix))
                """,id);
            jdbc.update("""
                UPDATE platform_embedding_calls SET state=CASE WHEN state='PREPARED' THEN 'REJECTED' ELSE 'UNKNOWN' END,
                  safe_code=CASE WHEN state='PREPARED' THEN 'EMBEDDING_DISPATCH_CANCELLED' ELSE 'EMBEDDING_EXECUTION_EXPIRED' END,updated_at=clock_timestamp()
                WHERE id=? AND state IN ('PREPARED','DISPATCHED') AND deadline<=clock_timestamp()
                """,id);
            return jdbc.query("SELECT * FROM platform_embedding_calls WHERE id=?",this::map,id).stream().findFirst();
    }
    public boolean dispatch(String id) { return Boolean.TRUE.equals(tx.execute(s->jdbc.update("""
            UPDATE platform_embedding_calls SET state='DISPATCHED',updated_at=clock_timestamp()
            WHERE id=? AND state='PREPARED' AND deadline>clock_timestamp() AND NOT response_erased
              AND NOT EXISTS(SELECT 1 FROM platform_embedding_output_tombstones t WHERE t.tenant_id=platform_embedding_calls.tenant_id
                AND t.actor_id=platform_embedding_calls.actor_id AND starts_with(platform_embedding_calls.operation_key,t.operation_prefix))
            """,id)==1)); }
    public boolean finish(String id,EmbeddingCall.State expected,EmbeddingCall.State state,String encrypted,Long tokens,Long cost,String code) {
        return Boolean.TRUE.equals(tx.execute(s->jdbc.update("""
            UPDATE platform_embedding_calls c SET state=?,encrypted_response=CASE WHEN response_erased OR EXISTS(SELECT 1 FROM platform_embedding_output_tombstones t
               WHERE t.tenant_id=c.tenant_id AND t.actor_id=c.actor_id AND starts_with(c.operation_key,t.operation_prefix)) THEN NULL ELSE ? END,
             response_erased=response_erased OR EXISTS(SELECT 1 FROM platform_embedding_output_tombstones t
               WHERE t.tenant_id=c.tenant_id AND t.actor_id=c.actor_id AND starts_with(c.operation_key,t.operation_prefix)),
             input_tokens=?,cost_micros=?,safe_code=?,updated_at=clock_timestamp()
            WHERE id=? AND state=?
            """,state.name(),encrypted,tokens,cost,code,id,expected.name())==1));
    }
    public void eraseOutputs(String tenant,String actor,String prefix){tx.executeWithoutResult(s->{
        jdbc.update("INSERT INTO platform_embedding_output_tombstones(tenant_id,actor_id,operation_prefix) VALUES (?,?,?) ON CONFLICT DO NOTHING",tenant,actor,prefix);
        jdbc.update("UPDATE platform_embedding_calls SET encrypted_response=NULL,response_erased=true WHERE tenant_id=? AND actor_id=? AND starts_with(operation_key,?)",tenant,actor,prefix);
    });}
    private EmbeddingCall map(ResultSet r,int n) throws SQLException {
        return new EmbeddingCall(r.getString("id"),r.getString("tenant_id"),r.getString("actor_id"),r.getString("operation_key"),
                r.getString("request_hash"),r.getString("provider_id"),r.getString("model_id"),r.getInt("dimensions"),
                r.getString("price_id"),(Long)r.getObject("input_rate"),EmbeddingCall.State.valueOf(r.getString("state")),
                r.getString("encrypted_response"),(Long)r.getObject("input_tokens"),(Long)r.getObject("cost_micros"),r.getString("safe_code"),
                r.getTimestamp("deadline").toInstant(),r.getTimestamp("created_at").toInstant());
    }
}

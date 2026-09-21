package com.spaceagent.platform.inference.infrastructure.persistence;

import com.spaceagent.platform.inference.domain.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.*;
import java.time.Instant;
import java.util.*;

@Repository
@ConditionalOnProperty(prefix="platform",name="persistence",havingValue="postgres")
public class PostgresModelPriceRepository implements ModelPriceRepository {
    private static final String C="id,tenant_id,provider_model_id,version,input_micros_per_million_tokens,output_micros_per_million_tokens,currency,effective_from,effective_until,created_by,created_at";private final JdbcTemplate j;public PostgresModelPriceRepository(JdbcTemplate j){this.j=j;}
    public void save(ModelPrice v){j.update("INSERT INTO platform_model_prices(id,tenant_id,provider_model_id,version,input_micros_per_million_tokens,output_micros_per_million_tokens,currency,effective_from,effective_until,created_by,created_at) VALUES(CAST(? AS UUID),?,?, ?,?,?,?,?,?,?,?)",v.id(),v.tenantId(),v.providerModelId(),v.version(),v.inputMicrosPerMillionTokens(),v.outputMicrosPerMillionTokens(),v.currency(),Timestamp.from(v.effectiveFrom()),ts(v.effectiveUntil()),v.createdBy(),Timestamp.from(v.createdAt()));}
    public int nextVersion(String id){j.queryForObject("SELECT id FROM platform_provider_models WHERE id=? FOR UPDATE",String.class,id);Integer v=j.queryForObject("SELECT COALESCE(max(version),0)+1 FROM platform_model_prices WHERE provider_model_id=?",Integer.class,id);return v==null?1:v;}
    public Optional<ModelPrice> findEffective(String id,Instant at){return j.query("SELECT "+C+" FROM platform_model_prices WHERE provider_model_id=? AND effective_from<=? AND (effective_until IS NULL OR effective_until>?) ORDER BY version DESC LIMIT 1",this::map,id,Timestamp.from(at),Timestamp.from(at)).stream().findFirst();}
    public List<ModelPrice> findEffectiveByProviderModelIds(List<String> ids,Instant at){if(ids==null||ids.isEmpty())return List.of();String p=String.join(",",Collections.nCopies(ids.size(),"?"));List<Object> a=new ArrayList<>(ids);a.add(Timestamp.from(at));a.add(Timestamp.from(at));return j.query("SELECT "+C+" FROM (SELECT "+C+",row_number() OVER(PARTITION BY provider_model_id ORDER BY version DESC) AS rn FROM platform_model_prices WHERE provider_model_id IN ("+p+") AND effective_from<=? AND (effective_until IS NULL OR effective_until>?)) effective WHERE rn=1",this::map,a.toArray());}
    public List<ModelPrice> findByProviderModelId(String id){return j.query("SELECT "+C+" FROM platform_model_prices WHERE provider_model_id=? ORDER BY version",this::map,id);}
    public boolean overlaps(String id,Instant from,Instant until){Boolean v=j.queryForObject("SELECT EXISTS(SELECT 1 FROM platform_model_prices WHERE provider_model_id=? AND (effective_until IS NULL OR effective_until>?) AND (CAST(? AS TIMESTAMPTZ) IS NULL OR CAST(? AS TIMESTAMPTZ)>effective_from))",Boolean.class,id,Timestamp.from(from),ts(until),ts(until));return Boolean.TRUE.equals(v);}
    private ModelPrice map(ResultSet r,int n)throws SQLException{return new ModelPrice(r.getString("id"),r.getString("tenant_id"),r.getString("provider_model_id"),r.getInt("version"),r.getLong("input_micros_per_million_tokens"),r.getLong("output_micros_per_million_tokens"),r.getString("currency"),r.getTimestamp("effective_from").toInstant(),instant(r.getTimestamp("effective_until")),r.getString("created_by"),r.getTimestamp("created_at").toInstant());}private static Timestamp ts(Instant v){return v==null?null:Timestamp.from(v);}private static Instant instant(Timestamp v){return v==null?null:v.toInstant();}
}

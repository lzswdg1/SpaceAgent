package com.spaceagent.platform.integration;
import com.fasterxml.jackson.databind.*;
import com.spaceagent.platform.governance.api.BusinessEvidenceApi;
import com.spaceagent.platform.inference.domain.*;
import com.spaceagent.platform.tooling.domain.ToolExecutionClaimRequest;
import com.spaceagent.platform.tooling.domain.ToolExecutionLedgerRepository;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.*;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest @AutoConfigureMockMvc @Testcontainers @DirtiesContext(classMode=DirtiesContext.ClassMode.AFTER_CLASS)
class AdministrationBusinessEvidencePostgresTest {
    static final String SECRET="business-evidence-fixture-secret-0123456789-abcdef";
    @Container static PostgreSQLContainer<?> pg=new PostgreSQLContainer<>("pgvector/pgvector:pg17").withDatabaseName("business_evidence").withUsername("fixture").withPassword("fixture-only-password");
    @DynamicPropertySource static void properties(DynamicPropertyRegistry r){
        r.add("spring.datasource.url",pg::getJdbcUrl);r.add("spring.datasource.username",pg::getUsername);r.add("spring.datasource.password",pg::getPassword);r.add("spring.datasource.driver-class-name",()->"org.postgresql.Driver");
        r.add("spring.flyway.enabled",()->true);r.add("spring.flyway.locations",()->"classpath:db/platform-server,classpath:db/platform-runtime");r.add("platform.persistence",()->"postgres");
        r.add("platform.security.allow-insecure-local",()->true);r.add("platform.system-admin.security.allow-insecure-local",()->true);r.add("platform.system-admin.security.jwt-secret",()->SECRET);
        r.add("platform.runtime.coordination.enabled",()->false);r.add("platform.tooling.mcp.registry.worker-enabled",()->false);r.add("platform.identity.cleanup.worker-enabled",()->false);r.add("platform.identity.user-cleanup.worker-enabled",()->false);
    }
    @Autowired MockMvc mvc;@Autowired ObjectMapper json;@Autowired JdbcTemplate jdbc;@Autowired BusinessEvidenceApi evidence;
    @Autowired ModelCallLedgerRepository models;@Autowired ToolExecutionLedgerRepository tools;
    @Autowired com.spaceagent.platform.identity.api.IdentityApplicationApi identity;
    @org.springframework.test.context.bean.override.mockito.MockitoSpyBean
    com.spaceagent.platform.inference.api.InferenceAdministrationUsageApi usage;
    @Test void ownerApprovalMetadataIsVisibleButProposalContentIsNeverReturned()throws Exception{
        var owner=register();var member=register();identity.addTenantMembership(new com.spaceagent.platform.identity.api.AddTenantMembershipCommand(owner.org(),member.id(),com.spaceagent.platform.identity.domain.TenantRole.MEMBER));
        var switched=mvc.perform(post("/api/v1/organizations/"+owner.org()+"/switch").header("Authorization","Bearer "+member.token())).andExpect(status().isOk()).andReturn();
        String memberToken=json.readTree(switched.getResponse().getContentAsByteArray()).path("data").path("token").asText();
        var created=mvc.perform(post("/api/v1/agents").header("Authorization","Bearer "+owner.token()).contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"Owner agent\"}")).andExpect(status().isCreated()).andReturn();
        String agent=json.readTree(created.getResponse().getContentAsByteArray()).path("data").path("id").asText();
        var change=mvc.perform(put("/api/v1/agents/"+agent).header("Authorization","Bearer "+memberToken).contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"PRIVATE_PROPOSED_NAME\",\"systemPrompt\":\"PRIVATE_PROPOSED_PROMPT\"}")).andExpect(status().isAccepted()).andReturn();
        String attempt=change.getResponse().getHeader("X-Business-Audit-ID");assertThat(jdbc.queryForObject("SELECT outcome FROM platform_governance_business_outcomes WHERE attempt_id=?",String.class,attempt)).isEqualTo("HTTP_ACCEPTED");
        var page=read("/agent-change-evidence","system-admin:business-evidence:read",Map.of("userId",member.id(),"organizationId",owner.org()));
        assertThat(page.path("total").asInt()).isEqualTo(1);assertThat(page.path("items").get(0).path("state").asText()).isEqualTo("PENDING");
        assertThat(page.toString()).doesNotContain("PRIVATE_PROPOSED_NAME","PRIVATE_PROPOSED_PROMPT","proposal","decisionNote");
    }
    @Test void v1094UpgradeBackfillsAttributionWithoutRewritingLedgerOutcomes(){
        var ds=com.spaceagent.platform.support.PostgresSchemaDataSource.forSchema(pg,"m78_attribution_upgrade");
        // Frozen migration proof, not a latest-schema counter. Do not advance these targets for unrelated migrations.
        org.flywaydb.core.Flyway.configure().dataSource(ds).locations("classpath:db/platform-server","classpath:db/platform-runtime").target("1094").load().migrate();
        var db=new JdbcTemplate(ds);
        db.update("INSERT INTO platform_tenants(id,name,slug,status,created_at,updated_at) VALUES ('org','fixture','fixture','ACTIVE',now(),now())");
        db.update("INSERT INTO platform_users(id,tenant_id,external_id,display_name,created_at,updated_at) VALUES ('owner','org','fixture@example.test','fixture',now(),now())");
        db.update("INSERT INTO platform_agent_definitions(id,tenant_id,owner_id,name,status,revision,created_at,updated_at) VALUES ('agent','org','owner','fixture','ACTIVE',1,now(),now())");
        db.update("INSERT INTO platform_agent_runs(id,agent_id,tenant_id,owner_id,conversation_id,state,created_at,updated_at) VALUES ('run','agent','org','owner','conversation','IN_PROGRESS',now(),now())");
        db.update("INSERT INTO platform_run_steps(id,agent_run_id,sequence,type,state,created_at) VALUES ('step','run',0,'inference','IN_PROGRESS',now())");
        db.update("INSERT INTO platform_model_call_ledger(id,agent_run_id,run_step_id,logical_call_id,request_hash,status,provider_id,model_id) VALUES ('call','run','step','logical',?,'UNKNOWN','provider','model')","a".repeat(64));
        var migration=org.flywaydb.core.Flyway.configure().dataSource(ds).locations("classpath:db/platform-server","classpath:db/platform-runtime").target("1095").load();
        assertThat(migration.migrate().migrationsExecuted).isEqualTo(1);assertThat(migration.migrate().migrationsExecuted).isZero();
        assertThat(db.queryForMap("SELECT usage_tenant_id,usage_actor_id,status FROM platform_model_call_ledger WHERE id='call'")).containsEntry("usage_actor_id","owner").containsEntry("usage_tenant_id","org").containsEntry("status","UNKNOWN");
    }
    @Test void authenticatedMutationsProduceAppendOnlyRedactedEvidenceAndWrongScopeIsDenied()throws Exception {
        var user=register();String secret="PRIVATE_PROMPT_AND_NAME_"+UUID.randomUUID();
        var response=mvc.perform(post("/api/v1/agents").header("Authorization","Bearer "+user.token()).contentType(MediaType.APPLICATION_JSON)
            .content(json.writeValueAsBytes(Map.of("name",secret,"systemPrompt",secret)))).andExpect(status().isCreated()).andReturn();
        String auditId=response.getResponse().getHeader("X-Business-Audit-ID");assertThat(auditId).isNotBlank();
        var page=read("/business-audit","system-admin:business-evidence:read",Map.of("userId",user.id()));
        assertThat(page.path("items")).anySatisfy(e->{assertThat(e.path("id").asText()).isEqualTo(auditId);assertThat(e.path("outcome").asText()).isEqualTo("HTTP_SUCCEEDED");});
        assertThat(page.toString()).doesNotContain(secret,user.token(),"systemPrompt","proposal_json");
        assertThatThrownBy(()->jdbc.update("UPDATE platform_governance_business_attempts SET actor_id='changed' WHERE id=?",auditId)).isInstanceOf(org.springframework.dao.DataAccessException.class);
        assertThatThrownBy(()->jdbc.update("DELETE FROM platform_governance_business_outcomes WHERE attempt_id=?",auditId)).isInstanceOf(org.springframework.dao.DataAccessException.class);
        mvc.perform(get("/internal/system-admin/v1/business-audit").header("Authorization","Bearer "+user.token())).andExpect(status().isUnauthorized());
        mvc.perform(get("/internal/system-admin/v1/business-audit").header("Authorization","Bearer "+token("system-admin:overview:read"))).andExpect(status().isUnauthorized());
        mvc.perform(get("/internal/system-admin/v1/usage/history").param("kind","MODEL").param("pageSize","999").header("Authorization","Bearer "+token("system-admin:usage:read"))).andExpect(status().isBadRequest());
        var capabilities=read("/resource-capabilities","system-admin:business-evidence:read",Map.of());assertThat(capabilities.toString()).contains("USER_SESSIONS_REVOKE","RECENT_MFA","OWNER_APPROVAL_NOT_BYPASSED");
        assertThat(read("/agent-change-evidence","system-admin:business-evidence:read",Map.of("userId",user.id())).path("items").isArray()).isTrue();
    }
    @Test void admittedAttemptSurvivesBusinessRollbackAndMissingCompletionRemainsUnconfirmed(){
        var tx=new org.springframework.transaction.support.TransactionTemplate(new org.springframework.jdbc.datasource.DataSourceTransactionManager(jdbc.getDataSource()));
        String id=tx.execute(s->{String value=evidence.admit(new BusinessEvidenceApi.Attempt("actor","tenant","USER","POST","/api/v1/agents","AGENT",null));s.setRollbackOnly();return value;});
        var filter=new BusinessEvidenceApi.Filter("actor","tenant","UNCONFIRMED",Instant.now().minusSeconds(60),Instant.now().plusSeconds(60),0,10);
        assertThat(evidence.timeline(filter).items()).extracting(BusinessEvidenceApi.Entry::id).contains(id);
        evidence.finish(id,202);evidence.finish(id,200);
        assertThat(jdbc.queryForObject("SELECT outcome FROM platform_governance_business_outcomes WHERE attempt_id=?",String.class,id)).isEqualTo("HTTP_ACCEPTED");
    }
    @Test void allStatusUsageIncludesFailureUnknownAndEmbeddingWithoutExposingBodies()throws Exception{
        org.mockito.Mockito.doAnswer(call->{
            assertThat(jdbc.queryForObject("SHOW transaction_isolation",String.class)).isEqualTo("repeatable read");
            assertThat(jdbc.queryForObject("SHOW transaction_read_only",String.class)).isEqualTo("on");
            return call.callRealMethod();
        }).when(usage).summary(org.mockito.ArgumentMatchers.anyString(),org.mockito.ArgumentMatchers.any());
        var user=register();String[] run=run(user);
        for(String state:List.of("SUCCEEDED","FAILED","UNKNOWN")){
            String call=UUID.randomUUID().toString();models.claim(new ModelCallClaimRequest(call,run[0],run[1],"call-"+state,"a".repeat(64),"provider","model",UUID.randomUUID().toString(),"worker",60));
            jdbc.update("UPDATE platform_model_call_ledger SET status=?,input_tokens=?,output_tokens=?,cost_micros=?,cost_currency=?,response_payload=?::jsonb,error_summary=? WHERE id=?",state,state.equals("UNKNOWN")?null:10L,state.equals("UNKNOWN")?null:2L,state.equals("SUCCEEDED")?7L:null,state.equals("SUCCEEDED")?"USD":null,"{\"secret\":\"PRIVATE_RESPONSE\"}","PRIVATE_ERROR",call);
        }
        String tool=UUID.randomUUID().toString();tools.claim(new ToolExecutionClaimRequest(tool,run[0],run[1],"file_read","tool","key","{\"private_path\":\"/secret\"}","b".repeat(64),UUID.randomUUID().toString(),"worker",60));
        jdbc.update("UPDATE platform_tool_execution_ledger SET status='FAILED',completed_at=clock_timestamp(),result='PRIVATE_TOOL_RESULT' WHERE id=?",tool);
        jdbc.update("INSERT INTO platform_embedding_calls(id,tenant_id,actor_id,operation_key,request_hash,provider_id,model_id,dimensions,state,deadline,created_at,updated_at) VALUES (?,?,?,'embed',?,'provider','embedding',2,'UNKNOWN',clock_timestamp(),clock_timestamp(),clock_timestamp())",UUID.randomUUID().toString(),user.org(),user.id(),"c".repeat(64));
        var filter=Map.of("userId",user.id(),"organizationId",user.org(),"to",Instant.now().plusSeconds(60).toString());
        var summary=read("/usage/summary","system-admin:usage:read",filter);var model=summary.path("summaries").get(0);
        assertThat(model.path("calls").asInt()).isEqualTo(3);assertThat(model.path("states").path("FAILED").asInt()).isEqualTo(1);
        assertThat(model.path("knownInputTokens").asInt()).isEqualTo(20);assertThat(model.path("knownCostMicros").asInt()).isEqualTo(7);
        assertThat(model.path("missingUsageCalls").asInt()).isEqualTo(1);assertThat(model.path("missingCostCalls").asInt()).isEqualTo(2);
        assertThat(summary.path("summaries").get(1).path("knownCostMicros").isNull()).isTrue();assertThat(summary.path("summaries").get(2).path("calls").asInt()).isEqualTo(1);
        var history=new HashMap<>(filter);history.put("kind","MODEL");var calls=read("/usage/history","system-admin:usage:read",history);
        assertThat(calls.path("total").asInt()).isEqualTo(3);assertThat(calls.toString()).doesNotContain("PRIVATE_RESPONSE","PRIVATE_ERROR","responsePayload");
        history.put("kind","TOOL");assertThat(read("/usage/history","system-admin:usage:read",history).toString()).doesNotContain("PRIVATE_TOOL_RESULT","private_path");
        assertThat(jdbc.queryForObject("SELECT usage_actor_id FROM platform_tool_execution_ledger WHERE id=?",String.class,tool)).isEqualTo(user.id());
        assertThatThrownBy(()->jdbc.update("UPDATE platform_model_call_ledger SET usage_actor_id='intruder' WHERE agent_run_id=?",run[0])).isInstanceOf(org.springframework.dao.DataAccessException.class);
        var other=register();assertThat(read("/usage/summary","system-admin:usage:read",Map.of("userId",other.id())).path("summaries").get(0).path("calls").asInt()).isZero();
    }
    String[] run(User u){String agent=UUID.randomUUID().toString(),run=UUID.randomUUID().toString(),step=UUID.randomUUID().toString();
        jdbc.update("INSERT INTO platform_agent_definitions(id,tenant_id,owner_id,name,status,revision,created_at,updated_at) VALUES (?,?,?,'fixture','ACTIVE',1,now(),now())",agent,u.org(),u.id());
        jdbc.update("INSERT INTO platform_agent_runs(id,agent_id,tenant_id,owner_id,conversation_id,state,created_at,updated_at) VALUES (?,?,?,?,'fixture-conversation','IN_PROGRESS',now(),now())",run,agent,u.org(),u.id());
        jdbc.update("INSERT INTO platform_run_steps(id,agent_run_id,sequence,type,state,created_at) VALUES (?,?,0,'inference','IN_PROGRESS',now())",step,run);return new String[]{run,step};}
    User register()throws Exception{var response=mvc.perform(post("/api/v1/auth/register").contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsBytes(Map.of("username",UUID.randomUUID()+"@example.test","password","password123","displayName","fixture")))).andExpect(status().isOk()).andReturn();var data=json.readTree(response.getResponse().getContentAsByteArray()).path("data");return new User(data.path("userId").asText(),data.path("tenantId").asText(),data.path("token").asText());}
    JsonNode read(String path,String scope,Map<String,String> filters)throws Exception{var request=get("/internal/system-admin/v1"+path).header("Authorization","Bearer "+token(scope));filters.forEach(request::param);var result=mvc.perform(request).andReturn();
        assertThat(result.getResolvedException()).as("disposable fixture exception").isNull();assertThat(result.getResponse().getStatus()).isEqualTo(200);
        return json.readTree(result.getResponse().getContentAsByteArray()).path("data");}
    String token(String scope){var now=Instant.now();return Jwts.builder().id(UUID.randomUUID().toString()).issuer("spaceagent-platform-admin").audience().add("spaceagent-platform-admin-internal").and().subject("platform-admin-server").claim("actor_id",UUID.randomUUID().toString()).claim("scope",scope).claim("request_id",UUID.randomUUID().toString()).issuedAt(Date.from(now)).expiration(Date.from(now.plusSeconds(45))).signWith(Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8)),Jwts.SIG.HS256).compact();}
    record User(String id,String org,String token){}
}

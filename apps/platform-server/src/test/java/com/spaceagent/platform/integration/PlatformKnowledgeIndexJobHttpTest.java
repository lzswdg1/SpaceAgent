package com.spaceagent.platform.integration;

import com.fasterxml.jackson.databind.*;
import com.spaceagent.platform.identity.api.*;
import com.spaceagent.platform.identity.domain.TenantRole;
import com.spaceagent.platform.knowledge.api.*;
import com.spaceagent.platform.knowledge.domain.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import java.time.Instant;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest @AutoConfigureMockMvc
class PlatformKnowledgeIndexJobHttpTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired IdentityApplicationApi identity;
    @Autowired KnowledgeIndexJobApplicationApi api;
    @Autowired KnowledgeIndexCatalogRepository catalog;
    @Test void progressIsPrivateBoundedAndCancellationUsesExactRevision() throws Exception {
        var owner=register(); var other=register(); var job=job(owner,"PERSONAL");
        String path="/api/v1/knowledge/index-jobs/"+job.id();
        var visible=send(get(path),owner,null,200);
        assertThat(visible.path("state").asText()).isEqualTo("QUEUED");
        assertThat(visible.has("input")).isFalse(); assertThat(visible.has("worker")).isFalse();
        send(get(path),other,null,404);
        send(get("/api/v1/knowledge/bases/"+job.baseId()+"/index-jobs"),other,null,404);
        send(get("/api/v1/knowledge/bases/"+job.baseId()+"/index-jobs").param("limit","101"),owner,null,400);
        send(get(path+"/batches").param("stage","PARSING"),owner,null,200);
        send(post(path+"/cancel"),other,Map.of("expectedRevision",1),404);
        send(post(path+"/cancel"),owner,Map.of("expectedRevision",2),409);
        send(post(path+"/cancel"),owner,Map.of("expectedRevision",0),400);
        var cancelled=send(post(path+"/cancel"),owner,Map.of("expectedRevision",1),200);
        assertThat(cancelled.path("state").asText()).isEqualTo("CANCELLED");
        send(post(path+"/retry"),owner,Map.of("expectedRevision",2),409);
        mvc.perform(get(path)).andExpect(status().isUnauthorized());
    }
    @Test void organizationReaderCanSeeProgressButCannotCancelAndRevocationTakesEffect() throws Exception {
        var owner=register(); var member=register(); var outsider=register();
        identity.addTenantMembership(new AddTenantMembershipCommand(owner.org(),member.id(),TenantRole.MEMBER));
        var switched=send(post("/api/v1/organizations/"+owner.org()+"/switch"),member,null,200);
        member=new User(member.id(),owner.org(),switched.path("token").asText());
        var job=job(owner,"ORGANIZATION");
        String base="/api/v1/knowledge/bases/"+job.baseId(), path="/api/v1/knowledge/index-jobs/"+job.id();
        send(put(base+"/grants"),owner,Map.of("subjectType","USER","subjectId",member.id(),"permission","READ","expectedRevision",1),200);
        var reader=new KnowledgeBaseApplicationApi.Actor(member.id(),member.org());
        assertThatThrownBy(()->api.enqueue(reader,job.baseId(),job.generationId(),"reader-key"))
                .isInstanceOfSatisfying(com.spaceagent.shared.exception.BusinessException.class,
                        e->assertThat(e.getStatus()).isEqualTo(org.springframework.http.HttpStatus.FORBIDDEN));
        send(get(path),member,null,200); send(post(path+"/cancel"),member,Map.of("expectedRevision",1),403);
        send(get(path),outsider,null,404);
        send(delete(base+"/grants/USER/"+member.id()).param("expectedRevision","2"),owner,null,200);
        send(get(path),member,null,404);
        send(get(path+"/batches").param("stage","PARSING"),member,null,404);
    }
    private KnowledgeIndexJobApplicationApi.JobView job(User u,String scope) throws Exception {
        String base=send(post("/api/v1/knowledge/bases"),u,Map.of("scope",scope,"name","jobs"),201).path("base").path("id").asText();
        String gen=UUID.randomUUID().toString();
        catalog.insertGenerationIfAbsent(new KnowledgeIndexGeneration(gen,base,UUID.randomUUID().toString(),UUID.randomUUID().toString(),
                "a".repeat(64),"b".repeat(64),"c".repeat(64),"d".repeat(64),u.id(),Instant.now()));
        var actor=new KnowledgeBaseApplicationApi.Actor(u.id(),u.org());
        var job=api.enqueue(actor,base,gen,"ingest-key");
        assertThat(api.enqueue(actor,base,gen,"ingest-key")).isEqualTo(job);
        return job;
    }
    private User register() throws Exception {
        var r=mvc.perform(post("/api/v1/auth/register").contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of(
                "username","index-"+UUID.randomUUID()+"@example.test","password","password123","displayName","Index Test"))))
                .andExpect(status().isOk()).andReturn();
        var a=json.readTree(r.getResponse().getContentAsByteArray()).path("data");
        return new User(a.path("userId").asText(),a.path("tenantId").asText(),a.path("token").asText());
    }
    private JsonNode send(MockHttpServletRequestBuilder request,User u,Object body,int expected) throws Exception {
        request.header("Authorization","Bearer "+u.token());
        if(body!=null) request.contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body));
        var response=mvc.perform(request).andExpect(status().is(expected)).andReturn().getResponse();
        return json.readTree(response.getContentAsByteArray()).path("data");
    }
    private record User(String id,String org,String token) {}
}

package com.spaceagent.platform.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.spaceagent.platform.knowledge.api.KnowledgeIndexCatalogApplicationApi;
import com.spaceagent.platform.knowledge.api.KnowledgeBaseApplicationApi;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest @AutoConfigureMockMvc
class PlatformKnowledgeIndexCatalogHttpTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired KnowledgeIndexCatalogApplicationApi catalog;

    @Test void legacyDocumentScopeAndExplicitAgentBindingsRemainDistinct() throws Exception {
        User user=register(), other=register();
        JsonNode document=send(post("/api/v1/knowledge/documents"),user,Map.of("name","legacy","contentType","text/plain","storageLocation","inline:private text"),200);
        String documentId=document.path("id").asText();
        JsonNode bases=send(get("/api/v1/knowledge/bases"),user,null,200);
        String baseId=bases.path("items").get(0).path("base").path("id").asText();
        assertThat(baseId).isNotEqualTo(documentId);
        JsonNode scoped=send(get("/api/v1/knowledge/bases/"+baseId+"/documents"),user,null,200);
        assertThat(scoped.get(0).path("documentId").asText()).isEqualTo(documentId);
        send(get("/api/v1/knowledge/bases/"+baseId+"/documents"),other,null,404);
        JsonNode agent=send(post("/api/v1/agents"),user,Map.of("name","catalog-agent","knowledgeBaseIds",List.of(documentId),
                "knowledgeCollectionIds",List.of(baseId)),201);
        assertThat(agent.path("knowledgeBaseIds").get(0).asText()).isEqualTo(documentId);
        assertThat(agent.path("knowledgeCollectionIds").get(0).asText()).isEqualTo(baseId);
        String agentId=agent.path("id").asText();
        // Old clients omit the new field: they must not clear the existing collection binding.
        JsonNode updated=send(put("/api/v1/agents/"+agentId),user,Map.of("name","renamed"),200);
        assertThat(updated.path("knowledgeCollectionIds").get(0).asText()).isEqualTo(baseId);
        send(post("/api/v1/agents"),other,Map.of("name","forbidden","knowledgeCollectionIds",List.of(baseId)),404);
        send(delete("/api/v1/knowledge/bases/"+baseId).param("expectedRevision","1"),user,null,200);
        send(get("/api/v1/knowledge/documents/"+documentId),user,null,404);
        send(post("/api/v1/knowledge/retrieve"),user,Map.of("documentIds",List.of(documentId),"query","private"),404);
    }

    @Test void modelSpacesAreIdempotentButDifferentRevisionsNeverShareIdentity() throws Exception {
        User user=register();
        String doc=send(post("/api/v1/knowledge/documents"),user,Map.of("name","doc","contentType","text/plain","storageLocation","inline:example"),200).path("id").asText();
        String base=send(get("/api/v1/knowledge/bases"),user,null,200).path("items").get(0).path("base").path("id").asText();
        String provider=send(post("/api/v1/model-providers"),user,Map.of("name","embedding","type","openai","baseUrl","https://example.com/v1", "apiKey","fixture-secret",
                "models",List.of(Map.of("modelId","embedding-fixture","displayName","Fixture"))),201).path("id").asText();
        Map<String,Object> body=new HashMap<>(Map.of("providerId",provider,"modelId","embedding-fixture","modelRevision","release-one",
                "dimensions",2,"preprocessingHash","a".repeat(64)));
        String route="/api/v1/knowledge/bases/"+base+"/embedding-spaces";
        JsonNode first=send(post(route),user,body,201);
        assertThat(first.path("status").asText()).isEqualTo("REGISTERED_UNVERIFIED");
        String spaceId=first.path("space").path("id").asText();
        assertThat(send(post(route),user,body,201).path("space").path("id").asText()).isEqualTo(spaceId);
        body.put("modelRevision","release-two");
        assertThat(send(post(route),user,body,201).path("space").path("id").asText()).isNotEqualTo(spaceId);
        var command=new KnowledgeIndexCatalogApplicationApi.StageGenerationCommand(new KnowledgeBaseApplicationApi.Actor(user.id(),user.org()),
                base,doc,spaceId,"b".repeat(64),"c".repeat(64),"d".repeat(64));
        var generation=catalog.stage(command);
        assertThat(generation.searchable()).isFalse();
        assertThat(catalog.stage(command).generation().id()).isEqualTo(generation.generation().id());
        send(get("/api/v1/knowledge/bases/"+base+"/documents/"+doc+"/index-generations"),user,null,200);
        body.put("dimensions",0); send(post(route),user,body,400);
    }

    private User register()throws Exception {
        String name="index-"+UUID.randomUUID()+"@example.test";
        JsonNode data=json.readTree(mvc.perform(post("/api/v1/auth/register").contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("username",name,"password","password123","displayName","Index Test"))))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray()).path("data");
        return new User(data.path("userId").asText(),data.path("tenantId").asText(),data.path("token").asText());
    }
    private JsonNode send(MockHttpServletRequestBuilder r,User user,Object body,int expected)throws Exception {
        r.header("Authorization","Bearer "+user.token());
        if(body!=null)r.contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body));
        return json.readTree(mvc.perform(r).andExpect(status().is(expected)).andReturn().getResponse().getContentAsByteArray()).path("data");
    }
    private record User(String id,String org,String token){}
}

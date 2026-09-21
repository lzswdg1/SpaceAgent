package com.spaceagent.platform.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import java.util.Map;
import java.util.UUID;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class ConversationSettingsHttpTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;

    @Test void chatRenameAndAgentSwitchPersistWithoutChangingAgentDefinitions() throws Exception {
        String token=register(),other=register();
        String a=postData(token,"/api/v1/agents",Map.of("name","A","systemPrompt","Keep A prompt")).path("id").asText();
        String b=postData(token,"/api/v1/agents",Map.of("name","B","systemPrompt","Keep B prompt")).path("id").asText();
        String id=postData(token,"/api/v1/chat/conversations",Map.of("agentId",a,"name","Before")).path("conversationId").asText();
        String path="/api/v1/chat/conversations/"+id;
        var renamed=putData(token,path+"/title",Map.of("name","  我的会话  "));
        assertThat(renamed.path("id").asText()).isEqualTo(id);
        assertThat(renamed.path("title").asText()).isEqualTo("我的会话");
        putData(token,path+"/agent",Map.of("agentId",b));
        var loaded=getData(token,path);
        assertThat(loaded.path("title").asText()).isEqualTo("我的会话");
        assertThat(loaded.path("agentId").asText()).isEqualTo(b);
        assertThat(getData(token,"/api/v1/agents/"+a).path("systemPrompt").asText()).isEqualTo("Keep A prompt");
        assertThat(getData(token,"/api/v1/agents/"+b).path("systemPrompt").asText()).isEqualTo("Keep B prompt");
        mvc.perform(put(path+"/title").header("Authorization","Bearer "+other).contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"bad\"}")).andExpect(status().isNotFound());
        for(String name:new String[]{" ","x".repeat(201)})mvc.perform(put(path+"/title").header("Authorization","Bearer "+token).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of("name",name)))).andExpect(status().isBadRequest());
        assertThat(getData(token,path).path("title").asText()).isEqualTo("我的会话");
    }

    @Test void projectConversationRenameDoesNotRenameItsProjectOrRoot() throws Exception {
        String token=register();
        String agent=postData(token,"/api/v1/agents",Map.of("name","Project Agent")).path("id").asText();
        String project=postData(token,"/api/v1/projects",Map.of("name","Keep project name")).path("id").asText();
        var roots=getData(token,"/api/v1/projects/"+project+"/directories");
        String directory=roots.get(0).path("id").asText(),name=roots.get(0).path("name").asText();
        String id=postData(token,"/api/v1/chat/conversations",Map.of("agentId",agent,"projectId",project,"projectDirectoryId",directory,"name","Initial task")).path("conversationId").asText();
        putData(token,"/api/v1/chat/conversations/"+id+"/title",Map.of("name","Implement API"));
        var loaded=getData(token,"/api/v1/chat/conversations/"+id);
        assertThat(loaded.path("title").asText()).isEqualTo("Implement API");
        assertThat(loaded.path("projectDirectoryId").asText()).isEqualTo(directory);
        assertThat(getData(token,"/api/v1/projects/"+project).path("name").asText()).isEqualTo("Keep project name");
        assertThat(getData(token,"/api/v1/projects/"+project+"/directories").get(0).path("name").asText()).isEqualTo(name);
    }
    private String register()throws Exception{return postData(null,"/api/v1/auth/register",Map.of("username",UUID.randomUUID()+"@example.com","password","password123","displayName","Settings owner")).path("token").asText();}
    private JsonNode postData(String token,String path,Object body)throws Exception{
        var request=post(path).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body));
        if(token!=null)request.header("Authorization","Bearer "+token);
        return json.readTree(mvc.perform(request).andExpect(status().is2xxSuccessful()).andReturn().getResponse().getContentAsByteArray()).path("data");
    }
    private JsonNode putData(String token,String path,Object body)throws Exception{return json.readTree(mvc.perform(put(path).header("Authorization","Bearer "+token).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body))).andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray()).path("data");}
    private JsonNode getData(String token,String path)throws Exception{return json.readTree(mvc.perform(get(path).header("Authorization","Bearer "+token)).andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray()).path("data");}
}

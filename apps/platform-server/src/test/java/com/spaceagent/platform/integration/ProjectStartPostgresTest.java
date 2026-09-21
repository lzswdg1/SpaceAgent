package com.spaceagent.platform.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.spaceagent.platform.agent.api.AgentApplicationApi;
import com.spaceagent.platform.conversation.api.ConversationApplicationApi;
import com.spaceagent.platform.conversation.api.StartConversationCommand;
import com.spaceagent.platform.inference.domain.*;
import com.spaceagent.platform.integration.api.GithubMcpProjectImportApplicationApi;
import com.spaceagent.platform.integration.api.ProjectStartApplicationApi;
import com.spaceagent.platform.integration.application.ProjectCodingCoordinator;
import com.spaceagent.platform.project.api.*;
import com.spaceagent.platform.project.domain.*;
import com.spaceagent.shared.exception.BusinessException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.*;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties={"platform.sandbox.mode=http","platform.sandbox.base-url=http://127.0.0.1:9200",
        "platform.sandbox.internal-token=start-fixture-token-123456789-abcdef-123456","platform.project.coding.worker-enabled=false",
        "platform.identity.cleanup.worker-enabled=false","platform.runtime.coordination.enabled=false"})
@AutoConfigureMockMvc @Import(ProjectStartPostgresTest.Fixtures.class)
@Testcontainers(disabledWithoutDocker=true) @DirtiesContext(classMode=DirtiesContext.ClassMode.AFTER_CLASS)
class ProjectStartPostgresTest {
    @Container static final PostgreSQLContainer<?> PG=new PostgreSQLContainer<>("pgvector/pgvector:pg17");
    @DynamicPropertySource static void properties(DynamicPropertyRegistry r){
        r.add("spring.datasource.url",PG::getJdbcUrl);r.add("spring.datasource.username",PG::getUsername);r.add("spring.datasource.password",PG::getPassword);
        r.add("spring.datasource.driver-class-name",()->"org.postgresql.Driver");r.add("platform.persistence",()->"postgres");
        r.add("spring.flyway.enabled",()->true);r.add("spring.flyway.locations",()->"classpath:db/platform-server,classpath:db/platform-runtime");
        r.add("platform.security.allow-insecure-local",()->true);
    }
    @Autowired MockMvc mvc;@Autowired ObjectMapper json;@Autowired JdbcTemplate jdbc;
    @Autowired ProjectStartApplicationApi starts;@Autowired ProjectApplicationApi projects;
    @Autowired ProjectDirectoryApplicationApi directories;@Autowired ProjectRootApplicationApi roots;
    @Autowired ConversationApplicationApi conversations;@Autowired SourceRepositoryRepository sources;
    @Autowired AgentApplicationApi agents;
    @MockitoSpyBean ProjectCodingCoordinator coordinator;
    @MockitoBean GithubMcpProjectImportApplicationApi github;

    @Test void codingStartupIsAtomicAndResponseRetryDoesNotCreateNewTasksOrPlans()throws Exception{
        var f=fixture();String key=UUID.randomUUID().toString();
        var input=new ProjectStartApplicationApi.CodingInput(f.project,f.directory,f.source,f.conversation,f.agent,f.reviewer,"feature/code","Implement safely");
        var first=starts.coding(f.tenant,f.user,key,input);
        var again=starts.coding(f.tenant,f.user,key,input);
        assertThat(again.execution().id()).isEqualTo(first.execution().id());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM platform_tasks WHERE project_id=CAST(? AS UUID)",Long.class,f.project)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM platform_project_start_requests WHERE project_id=CAST(? AS UUID)",Long.class,f.project)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM platform_project_plan_step_assignments WHERE project_id=CAST(? AS UUID) AND model_pool_id IS NULL",Long.class,f.project)).isEqualTo(1);
        assertThatThrownBy(()->starts.coding(f.tenant,f.user,key,new ProjectStartApplicationApi.CodingInput(f.project,f.directory,f.source,f.conversation,f.agent,f.reviewer,"main","Changed goal")))
                .isInstanceOfSatisfying(BusinessException.class,e->assertThat(e.getCode()).isEqualTo("PROJECT_START_IDEMPOTENCY_CONFLICT"));
    }

    @Test void failureAfterTaskCreationRollsBackBindingsAndCanRetryTheSameKey()throws Exception{
        var f=fixture();String key=UUID.randomUUID().toString();
        var input=new ProjectStartApplicationApi.CodingInput(f.project,f.directory,f.source,f.conversation,f.agent,f.reviewer,"main","Rollback fixture");
        var original=agents.findById(f.agent).orElseThrow();
        doThrow(new BusinessException("fixture dispatch failure",HttpStatus.CONFLICT,"FIXTURE_FAIL"))
                .when(coordinator).dispatch(argThat(c->c!=null&&c.conversationId().equals(f.conversation)));
        assertThatThrownBy(()->starts.coding(f.tenant,f.user,key,input)).isInstanceOf(BusinessException.class);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM platform_tasks WHERE project_id=CAST(? AS UUID)",Long.class,f.project)).isZero();
        assertThat(conversations.find(f.conversation).orElseThrow().activeTaskId()).isNull();
        assertThat(agents.findById(f.agent).orElseThrow().enabledToolIds()).isEqualTo(original.enabledToolIds());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM platform_project_start_requests WHERE project_id=CAST(? AS UUID)",Long.class,f.project)).isZero();
        doCallRealMethod().when(coordinator).dispatch(argThat(c->c!=null&&c.conversationId().equals(f.conversation)));
        assertThat(starts.coding(f.tenant,f.user,key,input).execution().id()).isNotBlank();
    }

    @Test void firstRepositoryRootResumesAfterImportFailureWithoutAnExtraVisibleEmptyRoot()throws Exception{
        var auth=register();String tenant=auth.path("tenantId").asText(),user=auth.path("userId").asText();
        String name="root-"+UUID.randomUUID();AtomicInteger attempts=new AtomicInteger();
        when(github.importRepository(argThat(c->c!=null&&c.userId().equals(user)))).thenAnswer(invocation->{
            var c=(GithubMcpProjectImportApplicationApi.Command)invocation.getArgument(0);
            if(attempts.getAndIncrement()==0)throw new BusinessException("fixture import failure",HttpStatus.BAD_GATEWAY);
            String source=seedSource(tenant,user,c.projectId());
            return new SourceRepositoryView(source,c.projectId(),null,null,null,"fixture/repo","fixture/repo","https://github.com/fixture/repo.git",null,"main",SourceRepositoryType.GITHUB,SourceRepositoryState.READY,SourceRepositoryVisibility.PUBLIC,user,Instant.now(),Instant.now());
        });
        var input=new ProjectStartApplicationApi.RootInput(name,UUID.randomUUID().toString(),"https://github.com/fixture/repo");
        assertThatThrownBy(()->starts.githubRoot(tenant,user,UUID.randomUUID().toString(),input)).isInstanceOf(BusinessException.class);
        assertThat(roots.list(tenant,user)).isEmpty();
        String resumedKey=UUID.randomUUID().toString();
        var root=starts.githubRoot(tenant,user,resumedKey,input);
        assertThat(starts.githubRoot(tenant,user,resumedKey,input).id()).isEqualTo(root.id());
        assertThat(root.name()).isEqualTo(name);assertThat(roots.list(tenant,user)).hasSize(1);
        assertThat(projects.listProjects(new ListProjectsQuery(tenant,user))).hasSize(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM platform_project_start_requests WHERE owner_id=?",Long.class,user)).isEqualTo(1);
    }

    private Fixture fixture()throws Exception{
        var auth=register();String token=auth.path("token").asText(),tenant=auth.path("tenantId").asText(),user=auth.path("userId").asText();
        String provider=postData(token,"/api/v1/model-providers",Map.of("name","fixture","type","openai-compatible","baseUrl","https://example.com/v1","apiKey","fixture","models",List.of(Map.of("modelId","reader","displayName","reader","maxContextTokens",200000,"isDefault",true)))).path("id").asText();
        String agent=postData(token,"/api/v1/agents",Map.of("name","Coder","modelProviderId",provider,"modelId","reader")).path("id").asText();
        String reviewer=postData(token,"/api/v1/agents",Map.of("name","Reviewer","modelProviderId",provider,"modelId","reader")).path("id").asText();
        String project=projects.createProject(new CreateProjectCommand(tenant,user,"project-"+UUID.randomUUID(),null)).id();
        String source=seedSource(tenant,user,project);
        String directory=directories.create(new ProjectDirectoryApplicationApi.CreateCommand(tenant,user,project,source,"repo",".")).id();
        String conversation=conversations.start(new StartConversationCommand(project,directory,null,null,tenant,user,agent,"coding")).id();
        return new Fixture(tenant,user,project,directory,source,conversation,agent,reviewer);
    }
    private String seedSource(String tenant,String user,String project){
        String id=UUID.randomUUID().toString();Instant now=Instant.now();
        sources.save(new SourceRepository(id,project,tenant,null,null,null,"fixture/repo","fixture/repo","https://github.com/fixture/repo.git",null,"main",SourceRepositoryType.GITHUB,SourceRepositoryState.READY,SourceRepositoryVisibility.PUBLIC,user,now,now));return id;
    }
    private JsonNode register()throws Exception{return postData(null,"/api/v1/auth/register",Map.of("username",UUID.randomUUID()+"@example.com","password","password123","displayName","Starter"));}
    private JsonNode postData(String token,String path,Object body)throws Exception{
        var request=post(path).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body));if(token!=null)request.header("Authorization","Bearer "+token);
        var response=mvc.perform(request).andReturn().getResponse();
        assertThat(response.getStatus()).withFailMessage("Fixture request %s returned %s: %s",path,response.getStatus(),response.getContentAsString()).isBetween(200,299);
        return json.readTree(response.getContentAsByteArray()).path("data");
    }
    record Fixture(String tenant,String user,String project,String directory,String source,String conversation,String agent,String reviewer){}
    @TestConfiguration static class Fixtures {
        @Bean @Primary ModelProviderConnectionTester tester(){return provider->new ProviderConnectionProbeResult(true,1,List.of("reader"),null);}
        @Bean @Primary InferenceExecutor inference(){return request->{throw new AssertionError("Startup must not call a model");};}
        @Bean @Primary WorkspaceProvisioningGateway git(){var mock=mock(WorkspaceProvisioningGateway.class);when(mock.branches(anyString())).thenReturn(List.of("main","feature/code"));return mock;}
    }
}

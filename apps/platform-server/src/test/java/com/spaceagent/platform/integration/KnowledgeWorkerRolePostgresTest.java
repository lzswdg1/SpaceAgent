package com.spaceagent.platform.integration;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.scheduling.annotation.ScheduledAnnotationBeanPostProcessor;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.*;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties={"platform.runtime.role=knowledge-worker","platform.persistence=postgres","platform.security.allow-insecure-local=true","platform.runtime.coordination.enabled=false"})
@AutoConfigureMockMvc @Testcontainers @DirtiesContext(classMode=DirtiesContext.ClassMode.AFTER_CLASS)
class KnowledgeWorkerRolePostgresTest {
    @Container static PostgreSQLContainer<?> pg=new PostgreSQLContainer<>("pgvector/pgvector:pg17").withDatabaseName("worker").withUsername("fixture").withPassword("fixture-password");
    @DynamicPropertySource static void props(DynamicPropertyRegistry r){r.add("spring.datasource.url",pg::getJdbcUrl);r.add("spring.datasource.username",pg::getUsername);r.add("spring.datasource.password",pg::getPassword);r.add("spring.datasource.driver-class-name",()->"org.postgresql.Driver");r.add("spring.flyway.enabled",()->true);r.add("spring.flyway.locations",()->"classpath:db/platform-server,classpath:db/platform-runtime");}
    @Autowired MockMvc mvc;@Autowired ApplicationContext context;
    @Test void exposesHealthButNoBusinessOrInternalApi() throws Exception {
        mvc.perform(get("/actuator/health")).andExpect(status().isOk());
        mvc.perform(post("/api/v1/auth/register").contentType("application/json").content("{}")).andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/agents")).andExpect(status().isForbidden());
        mvc.perform(post("/internal/runtime/anything")).andExpect(status().isForbidden());
    }
    @Test void schedulesOnlyKnowledgeAndDoesNotInitializeProjectStorage(){
        var schedules=context.getBean(ScheduledAnnotationBeanPostProcessor.class);
        assertThat(schedules).isInstanceOf(com.spaceagent.platform.knowledge.infrastructure.KnowledgeWorkerRoleConfiguration.KnowledgeOnlySchedules.class);
        assertThat(schedules.getScheduledTasks()).hasSize(3);
        assertThat(context.getBeansOfType(com.spaceagent.platform.project.infrastructure.ManagedRootStorageInitializer.class)).isEmpty();
    }
}

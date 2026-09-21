package com.spaceagent.platform.conversation;

import com.spaceagent.platform.conversation.infrastructure.persistence.PostgresConversationRepository;
import com.spaceagent.platform.conversation.infrastructure.persistence.PostgresMessageRepository;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers(disabledWithoutDocker=true)
class ConversationPagingPostgresTest {
    @Container static final PostgreSQLContainer<?> POSTGRES=new PostgreSQLContainer<>("postgres:17-alpine");
    @Test void boundsQueriesByScopeOwnerAndSequenceInPostgres() {
        var jdbc=new JdbcTemplate(new DriverManagerDataSource(POSTGRES.getJdbcUrl(),POSTGRES.getUsername(),POSTGRES.getPassword()));
        jdbc.execute("""
                CREATE TABLE platform_conversations(id text PRIMARY KEY,project_id text,project_uuid uuid,
                project_directory_id uuid,task_id text,task_uuid uuid,active_task_id uuid,tenant_id text,user_id text,
                agent_id text,title text,status text,created_at timestamptz,updated_at timestamptz)
                """);
        jdbc.execute("""
                CREATE TABLE platform_messages(id text,conversation_id text,sequence_number int,role text,content text,created_at timestamptz)
                """);
        jdbc.update("""
                INSERT INTO platform_conversations(id,tenant_id,user_id,agent_id,title,status,created_at,updated_at)
                VALUES ('chat','t','u','a','Chat title','ACTIVE',now(),now()),('foreign','other','u','a','Chat title','ACTIVE',now(),now())
                """);
        jdbc.update("""
                INSERT INTO platform_conversations(id,project_uuid,tenant_id,user_id,agent_id,title,status,created_at,updated_at)
                VALUES ('project',gen_random_uuid(),'t','u','a','Project title','ACTIVE',now(),now())
                """);
        var conversations=new PostgresConversationRepository(jdbc);
        assertThat(conversations.findFiltered("t","u","CHAT","title",100,0)).extracting(v->v.id()).containsExactly("chat");
        assertThat(conversations.countFiltered("t","u","PROJECT","")).isEqualTo(1);
        assertThat(conversations.findFiltered("t","u","ALL","missing",100,0)).isEmpty();
        var service=new com.spaceagent.platform.conversation.application.ConversationApplicationService(conversations,
                new PostgresMessageRepository(jdbc),()->"new-id",()->java.time.Instant.now());
        service.rename(new com.spaceagent.platform.conversation.api.RenameConversationCommand("t","u","chat","Renamed in PostgreSQL"));
        assertThat(new PostgresConversationRepository(jdbc).findById("chat").orElseThrow().title()).isEqualTo("Renamed in PostgreSQL");
        assertThat(conversations.findById("chat").orElseThrow().agentId()).isEqualTo("a");
        for(int i=0;i<205;i++)jdbc.update("INSERT INTO platform_messages VALUES (?, 'chat',?,'USER',?,now())","m"+i,i,"text"+i);
        jdbc.update("INSERT INTO platform_messages VALUES ('pending','chat',205,'ASSISTANT_PENDING','',now())");
        var messages=new PostgresMessageRepository(jdbc);
        assertThat(messages.findBeforeSequence("chat",Integer.MAX_VALUE,200)).hasSize(200).first().extracting(v->v.sequence()).isEqualTo(204);
        assertThat(messages.findBeforeSequence("chat",5,200)).hasSize(5).last().extracting(v->v.sequence()).isEqualTo(0);
        var partialAt=java.time.Instant.parse("2026-09-12T12:00:00Z");
        assertThat(messages.updateReservedDraft("pending","Already generated",partialAt)).isTrue();
        assertThat(messages.findBeforeSequence("chat",Integer.MAX_VALUE,1)).singleElement().satisfies(message->{
            assertThat(message.role()).isEqualTo("ASSISTANT_PARTIAL");
            assertThat(message.content()).isEqualTo("Already generated");
            assertThat(message.createdAt()).isEqualTo(partialAt);
        });
        assertThat(messages.completeReserved("pending","Complete answer",partialAt.plusSeconds(1))).isTrue();
        assertThat(messages.updateReservedDraft("pending","Late partial",partialAt.plusSeconds(2))).isFalse();
        assertThat(messages.findBeforeSequence("chat",Integer.MAX_VALUE,1)).singleElement().satisfies(message->{
            assertThat(message.role()).isEqualTo("ASSISTANT");
            assertThat(message.content()).isEqualTo("Complete answer");
        });
    }
}

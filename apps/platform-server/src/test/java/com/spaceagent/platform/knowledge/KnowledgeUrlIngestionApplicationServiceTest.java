package com.spaceagent.platform.knowledge;

import com.spaceagent.platform.knowledge.api.KnowledgeUrlIngestionApplicationApi;
import com.spaceagent.platform.knowledge.application.KnowledgeUrlIngestionApplicationService;
import com.spaceagent.platform.knowledge.domain.*;
import com.spaceagent.platform.knowledge.infrastructure.memory.InMemoryKnowledgeUrlRepository;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class KnowledgeUrlIngestionApplicationServiceTest {
    @Test void createNormalizesActivatesAndLifecycleUsesCas(){var documents=mock(KnowledgeDocumentRepository.class);Instant now=Instant.parse("2026-09-09T00:00:00Z");when(documents.findById("document")).thenReturn(Optional.of(new KnowledgeDocument("document","owner","URL","text/html","managed:pending",KnowledgeDocumentStatus.UPLOADED,now,now)));var service=new KnowledgeUrlIngestionApplicationService(new InMemoryKnowledgeUrlRepository(()->now),documents,()->"00000000-0000-4000-8000-000000000001");var policy=new KnowledgeUrlJob.RefreshPolicy(KnowledgeUrlJob.RefreshMode.PERIODIC,3600,true,3,1000000);var created=service.create(new KnowledgeUrlIngestionApplicationApi.CreateUrlJobCommand("tenant","owner","document","HTTPS://Docs.Example.com",policy,"request"));assertThat(created.normalizedUrl()).isEqualTo("https://docs.example.com/");assertThat(created.state()).isEqualTo("ACTIVE");var page=service.listByDocument(new KnowledgeUrlIngestionApplicationApi.ListByDocumentQuery("tenant","owner","document",1,20));assertThat(page.items()).containsExactly(created);assertThat(page.total()).isEqualTo(1);var paused=service.pause(new KnowledgeUrlIngestionApplicationApi.LifecycleCommand("tenant","owner",created.id(),created.revision()));assertThat(paused.state()).isEqualTo("PAUSED");assertThatThrownBy(()->service.resume(new KnowledgeUrlIngestionApplicationApi.LifecycleCommand("tenant","owner",created.id(),created.revision()))).isInstanceOf(RuntimeException.class);assertThat(service.listByDocument(new KnowledgeUrlIngestionApplicationApi.ListByDocumentQuery("other","owner","document",1,20)).items()).isEmpty();assertThatThrownBy(()->service.listByDocument(new KnowledgeUrlIngestionApplicationApi.ListByDocumentQuery("tenant","other","document",1,20))).isInstanceOf(RuntimeException.class);assertThatThrownBy(()->service.listByDocument(new KnowledgeUrlIngestionApplicationApi.ListByDocumentQuery("tenant","owner","document",0,20))).isInstanceOf(RuntimeException.class);}
}

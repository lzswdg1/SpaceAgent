package com.spaceagent.platform.knowledge;

import com.spaceagent.platform.knowledge.domain.KnowledgeIndexJobRepository;
import com.spaceagent.platform.knowledge.infrastructure.memory.InMemoryKnowledgeIndexJobRepository;
import org.junit.jupiter.api.BeforeEach;
import java.time.*;

class KnowledgeIndexJobMemoryTest extends KnowledgeIndexJobRepositoryContract {
    private Instant current;
    @BeforeEach void setup() {
        current=Instant.now();
        repo=new InMemoryKnowledgeIndexJobRepository(new Clock() {
            public ZoneId getZone() { return ZoneOffset.UTC; }
            public Clock withZone(ZoneId zone) { return this; }
            public Instant instant() { return current; }
        });
    }
    protected void expire(String id) { current=current.plusSeconds(61); }
    protected void due(String id) { current=current.plusSeconds(301); }
    protected KnowledgeIndexJobRepository restart() { return repo; } // In-memory mode intentionally has no process durability.
}

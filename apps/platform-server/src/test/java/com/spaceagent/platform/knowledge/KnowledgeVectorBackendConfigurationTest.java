package com.spaceagent.platform.knowledge;

import com.spaceagent.platform.knowledge.infrastructure.KnowledgeConfigurationGuard;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import static org.assertj.core.api.Assertions.*;

class KnowledgeVectorBackendConfigurationTest {
    @Test void onlyKnownPostgresBackendsAreSupportedAndEnabledWorkerCannotSilentlyIdle() {
        for (String backend : new String[]{"milvus", "pgvector"}) {
            new KnowledgeConfigurationGuard(new MockEnvironment().withProperty("platform.persistence", "postgres")
                    .withProperty("platform.knowledge.vector-store.mode", backend)).validate();
            assertThatThrownBy(() -> new KnowledgeConfigurationGuard(new MockEnvironment()
                    .withProperty("platform.knowledge.vector-store.mode", backend)).validate()).hasMessageContaining("require PostgreSQL");
        }
        assertThatThrownBy(() -> new KnowledgeConfigurationGuard(new MockEnvironment()
                .withProperty("platform.knowledge.vector-store.mode", "unknown")).validate()).hasMessageContaining("Unknown Knowledge vector backend");
        assertThatThrownBy(() -> new KnowledgeConfigurationGuard(new MockEnvironment()
                .withProperty("platform.knowledge.indexing.worker-enabled", "true")).validate()).hasMessageContaining("requires a selected vector backend");
        assertThatThrownBy(() -> new KnowledgeConfigurationGuard(new MockEnvironment()
                .withProperty("platform.knowledge.pgvector.query-timeout-seconds", "31")).validate()).hasMessageContaining("capacity configuration");
    }
}

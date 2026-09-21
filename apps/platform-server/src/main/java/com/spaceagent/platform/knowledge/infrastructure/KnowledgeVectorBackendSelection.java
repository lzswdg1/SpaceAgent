package com.spaceagent.platform.knowledge.infrastructure;

import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.sql.init.dependency.DependsOnDatabaseInitialization;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Objects;
import java.util.Set;

/** Atomic deployment pin: API and workers cannot silently read/write different index backends. */
@Component
@DependsOnDatabaseInitialization
@ConditionalOnProperty(prefix = "platform", name = "persistence", havingValue = "postgres")
public class KnowledgeVectorBackendSelection {
    private final JdbcTemplate jdbc;
    private final String mode;

    public KnowledgeVectorBackendSelection(JdbcTemplate jdbc,
            @Value("${platform.knowledge.vector-store.mode:none}") String mode) {
        this.jdbc = jdbc;
        this.mode = mode;
    }

    @PostConstruct
    public void validate() {
        if (!Set.of("none", "milvus", "pgvector").contains(mode)) {
            throw new IllegalStateException("Unknown Knowledge vector backend");
        }
        if (mode.equals("none")) return;
        var tx = new TransactionTemplate(new DataSourceTransactionManager(Objects.requireNonNull(jdbc.getDataSource())));
        tx.executeWithoutResult(status -> {
            jdbc.update("INSERT INTO platform_knowledge_vector_backend(singleton,mode) VALUES(TRUE,?) ON CONFLICT(singleton) DO NOTHING", mode);
            String bound = jdbc.queryForObject("SELECT mode FROM platform_knowledge_vector_backend WHERE singleton=TRUE", String.class);
            if (!mode.equals(bound)) {
                throw new IllegalStateException("KNOWLEDGE_VECTOR_BACKEND_MISMATCH: use the existing backend; switching requires an explicit index migration");
            }
        });
    }
}

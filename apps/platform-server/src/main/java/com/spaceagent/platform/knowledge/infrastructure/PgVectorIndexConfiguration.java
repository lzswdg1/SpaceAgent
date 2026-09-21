package com.spaceagent.platform.knowledge.infrastructure;

import com.spaceagent.platform.knowledge.domain.VectorIndexGateway;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.DependsOn;
import org.springframework.jdbc.core.JdbcTemplate;

@Configuration(proxyBeanMethods = false)
@DependsOn("knowledgeVectorBackendSelection")
@ConditionalOnProperty(prefix = "platform.knowledge.vector-store", name = "mode", havingValue = "pgvector")
public class PgVectorIndexConfiguration {
    @Bean
    public VectorIndexGateway knowledgeVectorIndex(JdbcTemplate jdbc,
            @Value("${platform.knowledge.pgvector.query-timeout-seconds:5}") int timeout) {
        return new PgVectorIndexGateway(jdbc, timeout);
    }
}

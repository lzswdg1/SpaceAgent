package com.spaceagent.platform.knowledge.infrastructure;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(KnowledgeProperties.class)
public class KnowledgeInfrastructureConfiguration {
}

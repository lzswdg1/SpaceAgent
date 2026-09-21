package com.spaceagent.platform.runtime.infrastructure;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

@Configuration
@EnableScheduling
@EnableConfigurationProperties(RuntimeCoordinationProperties.class)
public class RuntimeCoordinationConfiguration {
}

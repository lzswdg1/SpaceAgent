package com.spaceagent.platform.integration.infrastructure;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Configuration
public class RuntimeEventStreamingConfiguration {

    @Bean(destroyMethod = "close")
    @Qualifier("runtimeEventStreamExecutor")
    public ExecutorService runtimeEventStreamExecutor() {
        return Executors.newVirtualThreadPerTaskExecutor();
    }
}

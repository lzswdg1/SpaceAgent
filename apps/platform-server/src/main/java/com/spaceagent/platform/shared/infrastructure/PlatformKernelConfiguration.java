package com.spaceagent.platform.shared.infrastructure;

import com.spaceagent.shared.id.IdGenerator;
import com.spaceagent.shared.id.UuidGenerator;
import com.spaceagent.shared.exception.GlobalExceptionHandler;
import com.spaceagent.shared.time.SystemTimeProvider;
import com.spaceagent.shared.time.TimeProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

/**
 * Exposes the generic shared-kernel primitives to the platform-server without
 * widening the platform-server component scan to the entire shared-kernel module.
 */
@Configuration
@Import(GlobalExceptionHandler.class)
public class PlatformKernelConfiguration {

    @Bean
    public IdGenerator platformIdGenerator() {
        return new UuidGenerator();
    }

    @Bean
    public TimeProvider platformTimeProvider() {
        return new SystemTimeProvider();
    }
}

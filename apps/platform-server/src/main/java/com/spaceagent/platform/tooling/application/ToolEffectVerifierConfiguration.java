package com.spaceagent.platform.tooling.application;

import com.spaceagent.platform.tooling.domain.LocalToolEffectVerifierRegistry;
import com.spaceagent.platform.tooling.domain.ToolEffectVerifier;
import java.util.List;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class ToolEffectVerifierConfiguration {
    @Bean
    LocalToolEffectVerifierRegistry localToolEffectVerifierRegistry(
            List<ToolEffectVerifier> verifiers) {
        return new LocalToolEffectVerifierRegistry(verifiers);
    }
}

package com.spaceagent.platform.inference;

import com.spaceagent.platform.inference.infrastructure.AllowlistedModelProviderEndpointPolicy;
import com.spaceagent.platform.inference.infrastructure.InferenceProperties;
import com.spaceagent.shared.exception.BusinessException;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AllowlistedModelProviderEndpointPolicyTest {

    private final InferenceProperties properties = properties();
    private final AllowlistedModelProviderEndpointPolicy policy =
            new AllowlistedModelProviderEndpointPolicy(properties);

    @Test
    void acceptsAllowlistedHttpsAndNormalizesTrailingSlash() {
        assertEquals("https://api.example.com/v1",
                policy.validateAndNormalize("https://api.example.com/v1///"));
    }

    @Test
    void rejectsUnlistedAndCredentialBearingEndpoints() {
        assertThrows(BusinessException.class,
                () -> policy.validateAndNormalize("https://metadata.internal/v1"));
        assertThrows(BusinessException.class,
                () -> policy.validateAndNormalize("https://user:pass@api.example.com/v1"));
    }

    @Test
    void localHttpRequiresExplicitDevelopmentOptIn() {
        assertThrows(BusinessException.class,
                () -> policy.validateAndNormalize("http://127.0.0.1:8080/v1"));
        properties.setAllowLocalProviderHosts(true);
        assertEquals("http://127.0.0.1:8080/v1",
                policy.validateAndNormalize("http://127.0.0.1:8080/v1"));
    }

    private static InferenceProperties properties() {
        InferenceProperties properties = new InferenceProperties();
        properties.setAllowedProviderHosts(List.of("*.example.com"));
        return properties;
    }
}

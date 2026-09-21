package com.spaceagent.platform.inference.domain;

/**
 * Domain-facing port for validating an external inference endpoint before it is persisted.
 */
public interface ModelProviderEndpointPolicy {

    String validateAndNormalize(String baseUrl);
}

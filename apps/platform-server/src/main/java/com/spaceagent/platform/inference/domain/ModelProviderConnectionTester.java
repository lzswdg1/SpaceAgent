package com.spaceagent.platform.inference.domain;

/** Infrastructure port for a bounded, secret-safe Provider connection probe. */
public interface ModelProviderConnectionTester {

    ProviderConnectionProbeResult test(ModelProvider provider);
}

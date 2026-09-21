package com.spaceagent.platform.inference.api;

public interface ProviderConnectionApplicationApi {

    ProviderConnectionTestView testProvider(TestProviderConnectionCommand command);

    ProviderModelTestView testModel(TestProviderModelCommand command);
}

package com.spaceagent.platform.identity.api;

public interface IdentityActivationApplicationApi {
    AuthenticatedIdentityView activate(String activationToken, String password);
}

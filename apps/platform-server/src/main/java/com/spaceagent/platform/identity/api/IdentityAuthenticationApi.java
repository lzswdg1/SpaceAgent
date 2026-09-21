package com.spaceagent.platform.identity.api;

/**
 * Public identity authentication application API.
 *
 * <p>Access-token signing is an integration concern and therefore remains outside this
 * boundary. A successful login returns a durably admitted refresh session so credential
 * verification and session creation cannot be separated by a revocation race.
 */
public interface IdentityAuthenticationApi {

    AuthenticatedIdentityView register(RegisterIdentityCommand command);

    IdentitySessionView authenticate(AuthenticateIdentityCommand command);

    void changePassword(String userId, String oldPassword, String newPassword);
}

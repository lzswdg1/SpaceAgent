package com.spaceagent.platform.identity.api;

public interface IdentityPasswordResetApi {
    void resetPassword(String resetToken, String newPassword);
}

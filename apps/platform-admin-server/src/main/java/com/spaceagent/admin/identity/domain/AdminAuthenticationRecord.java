package com.spaceagent.admin.identity.domain;

public record AdminAuthenticationRecord(
        SystemAdministrator principal,
        String passwordHash) {
}

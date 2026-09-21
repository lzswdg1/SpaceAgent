package com.spaceagent.platform.inference.domain;

/** Durable connection eligibility for ModelPool membership. */
public enum ProviderConnectionStatus {
    UNTESTED,
    ACTIVE,
    UNHEALTHY,
    DISABLED
}

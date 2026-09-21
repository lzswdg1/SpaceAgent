package com.spaceagent.platform.inference.domain;

public interface ModelCallLeasePolicy {
    long requestTimeoutSeconds();

    long claimLeaseSeconds();
}

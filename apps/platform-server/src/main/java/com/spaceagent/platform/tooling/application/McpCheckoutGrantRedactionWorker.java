package com.spaceagent.platform.tooling.application;

import com.spaceagent.platform.tooling.domain.McpCheckoutGrantRepository;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Stateless wake-up callback; PostgreSQL predicates own multi-replica idempotency. */
@Component
public class McpCheckoutGrantRedactionWorker {
    private final McpCheckoutGrantRepository grants;

    public McpCheckoutGrantRedactionWorker(McpCheckoutGrantRepository grants) {
        this.grants = grants;
    }

    @Scheduled(fixedDelayString =
            "${platform.tooling.mcp-checkout-redaction-delay-ms:60000}")
    public void redactExpired() {
        grants.redactExpired();
    }
}

package com.spaceagent.platform.tooling.domain;

import java.util.Objects;

/**
 * Atomic claim decision returned by the persistence owner.
 */
public record ToolExecutionClaimDecision(
        ToolExecutionClaimDecisionType type,
        ToolExecutionLedger ledger,
        String expectedInputHash,
        String actualInputHash,
        String reason) {

    public ToolExecutionClaimDecision {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(ledger, "ledger");
    }
}

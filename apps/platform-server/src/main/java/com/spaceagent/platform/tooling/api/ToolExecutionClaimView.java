package com.spaceagent.platform.tooling.api;

import com.spaceagent.platform.tooling.domain.ToolExecutionClaimDecisionType;

import java.time.Instant;
import java.util.Objects;

/**
 * Explicit claim decision. The token exists only for the internal CLAIMED path and is
 * never exposed by an HTTP controller.
 */
public record ToolExecutionClaimView(
        ToolExecutionClaimDecisionType type,
        ToolExecutionLedgerView ledger,
        String claimToken,
        long revision,
        Instant leaseUntil,
        String expectedInputHash,
        String actualInputHash,
        String reason) {

    public ToolExecutionClaimView {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(ledger, "ledger");
        if (type == ToolExecutionClaimDecisionType.CLAIMED
                && (claimToken == null || claimToken.isBlank())) {
            throw new IllegalArgumentException("CLAIMED requires a claimToken");
        }
    }
}

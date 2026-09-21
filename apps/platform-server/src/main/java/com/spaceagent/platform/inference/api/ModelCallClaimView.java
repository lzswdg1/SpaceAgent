package com.spaceagent.platform.inference.api;

import com.spaceagent.platform.inference.domain.ModelCallClaimDecisionType;

import java.time.Instant;

public record ModelCallClaimView(
        ModelCallClaimDecisionType decision,
        ModelCallLedgerView ledger,
        String claimToken,
        long revision,
        Instant leaseUntil,
        String expectedRequestHash,
        String actualRequestHash,
        String reason) {
}

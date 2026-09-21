package com.spaceagent.platform.inference.domain;

public record ModelCallClaimDecision(
        ModelCallClaimDecisionType type,
        ModelCallLedger ledger,
        String expectedRequestHash,
        String actualRequestHash,
        String reason) {
}

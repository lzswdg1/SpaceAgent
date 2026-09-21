package com.spaceagent.platform.inference.domain;

public record ModelCallTransitionResult(
        ModelCallTransitionType type,
        ModelCallLedger ledger) {
}

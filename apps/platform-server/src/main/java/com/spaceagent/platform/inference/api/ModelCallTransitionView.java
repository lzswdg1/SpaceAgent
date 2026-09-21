package com.spaceagent.platform.inference.api;

import com.spaceagent.platform.inference.domain.ModelCallTransitionType;

public record ModelCallTransitionView(
        ModelCallTransitionType transition,
        ModelCallLedgerView ledger) {
}

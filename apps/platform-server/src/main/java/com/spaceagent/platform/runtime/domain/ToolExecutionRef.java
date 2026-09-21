package com.spaceagent.platform.runtime.domain;

/**
 * Runtime-facing reference to one durable tool-execution ledger entry.
 *
 * <p>The runtime module deliberately keeps this as a value object instead of importing
 * the tooling module's persistence or domain types.
 */
public record ToolExecutionRef(
        String toolCallId,
        String status,
        String resultRef) {
}

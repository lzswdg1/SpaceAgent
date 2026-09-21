package com.spaceagent.platform.runtime.api;

/**
 * Public runtime-facing tool-ledger reference.
 */
public record ToolExecutionRefView(String toolCallId, String status, String resultRef) {
}

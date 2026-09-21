package com.spaceagent.platform.runtime.domain;

/** Non-authoritative Agent/Tool span boundary. */
public interface RuntimeOperationalTelemetry {
    InvocationSpan startAgent(String version, String workflow);
    InvocationSpan startTool(String toolName);

    interface InvocationSpan extends AutoCloseable {
        void success();
        void error(String errorType);
        @Override void close();
    }

    static RuntimeOperationalTelemetry noop() {
        return new RuntimeOperationalTelemetry() {
            public InvocationSpan startAgent(String version, String workflow) { return span(); }
            public InvocationSpan startTool(String toolName) { return span(); }
            private InvocationSpan span() {
                return new InvocationSpan() {
                    public void success() { }
                    public void error(String errorType) { }
                    public void close() { }
                };
            }
        };
    }
}

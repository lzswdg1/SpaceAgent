package com.spaceagent.platform.inference.domain;

/** Disposable operational telemetry; ModelCallLedger remains authoritative. */
public interface InferenceTelemetry {
    CallSpan start(String model, boolean streaming);

    interface CallSpan extends AutoCloseable {
        void firstChunk(long milliseconds);
        void success(int inputTokens, int outputTokens);
        void error(String errorType);
        @Override void close();
    }

    static InferenceTelemetry noop() {
        return (model, streaming) -> new CallSpan() {
            public void firstChunk(long milliseconds) { }
            public void success(int inputTokens, int outputTokens) { }
            public void error(String errorType) { }
            public void close() { }
        };
    }
}

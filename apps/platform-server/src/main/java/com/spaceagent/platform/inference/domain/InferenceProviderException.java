package com.spaceagent.platform.inference.domain;

/**
 * A provider-adapter failure with an explicit ledger disposition and a controlled,
 * secret-free summary.
 */
public class InferenceProviderException extends RuntimeException {

    private final ModelCallStatus disposition;
    private final String errorCode;
    private final String safeSummary;

    public InferenceProviderException(
            ModelCallStatus disposition,
            String errorCode,
            String safeSummary,
            Throwable cause) {
        super(safeSummary, cause);
        if (disposition == ModelCallStatus.RUNNING || disposition == ModelCallStatus.SUCCEEDED) {
            throw new IllegalArgumentException("invalid provider failure disposition");
        }
        this.disposition = disposition;
        this.errorCode = errorCode;
        this.safeSummary = safeSummary;
    }

    public ModelCallStatus disposition() {
        return disposition;
    }

    public String errorCode() {
        return errorCode;
    }

    public String safeSummary() {
        return safeSummary;
    }
}

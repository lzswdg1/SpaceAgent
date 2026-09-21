package com.spaceagent.platform.governance.domain;

/** Exact capability identity used for request deduplication and one-use consumption. */
public record ApprovalScope(
        String tenantId,
        String requestedBy,
        GovernanceActionType actionType,
        String resourceType,
        String resourceId,
        String operationHash) {

    public ApprovalScope {
        requireNonBlank(tenantId, "tenantId");
        requireNonBlank(requestedBy, "requestedBy");
        if (actionType == null) {
            throw new IllegalArgumentException("actionType must not be null");
        }
        requireNonBlank(resourceType, "resourceType");
        requireNonBlank(resourceId, "resourceId");
        if (operationHash == null || !operationHash.matches("sha256:[0-9a-f]{64}")) {
            throw new IllegalArgumentException("operationHash must be a lowercase SHA-256 digest");
        }
    }

    public boolean matches(ApprovalRequest request) {
        return tenantId.equals(request.tenantId())
                && requestedBy.equals(request.requestedBy())
                && actionType == request.actionType()
                && resourceType.equals(request.resourceType())
                && resourceId.equals(request.resourceId())
                && operationHash.equals(request.operationHash());
    }

    private static void requireNonBlank(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
    }
}

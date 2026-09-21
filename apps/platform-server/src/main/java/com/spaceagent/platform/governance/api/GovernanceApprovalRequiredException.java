package com.spaceagent.platform.governance.api;

import com.spaceagent.shared.exception.BusinessException;
import org.springframework.http.HttpStatus;

public class GovernanceApprovalRequiredException extends BusinessException {
    private final String approvalId;
    public GovernanceApprovalRequiredException(String approvalId) {
        super("Organization approval is required: " + approvalId,
                HttpStatus.CONFLICT, "GOVERNANCE_APPROVAL_REQUIRED");
        this.approvalId = approvalId;
    }
    public String approvalId() { return approvalId; }
}

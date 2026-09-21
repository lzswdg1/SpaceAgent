package com.spaceagent.platform.project.domain;

/** Project-owned port: implementations prove cross-owner resolution evidence before Project CAS. */
public interface ProjectReconciliationEvidenceVerifier {
    void verify(String tenantId, String ownerUserId, ProjectReconciliationStep step,
                ProjectReconciliationStep.ResolutionEvidence evidence);
}

package com.spaceagent.platform.project.domain;

public interface SourceMergeGateway {
    default void stagePreparedCommit(
            SourceRepository source,
            String workspaceId,
            PreparedCommitTransfer transfer) {}

    RefUpdate apply(
            SourceRepository source,
            String targetRef,
            String expectedBaseCommit,
            String preparedCommit);

    RefUpdate rollback(
            SourceRepository source,
            String targetRef,
            String preparedCommit,
            String expectedBaseCommit);

    RefInspection inspect(SourceRepository source, String targetRef);

    enum RefUpdateStatus { APPLIED, ALREADY_APPLIED, CONFLICT }

    record RefUpdate(RefUpdateStatus status, String actualTargetCommit) {}

    record RefInspection(String actualTargetCommit) {}

    record PreparedCommitTransfer(
            String commit,
            String bundleReference,
            String bundleSha256,
            long bundleSizeBytes,
            byte[] bundleBytes) {
        public PreparedCommitTransfer {
            bundleBytes = bundleBytes == null ? null : bundleBytes.clone();
            new WorkspaceSandboxGateway.PreparedCommit(
                    commit, bundleReference, bundleSha256, bundleSizeBytes, bundleBytes);
        }

        @Override
        public byte[] bundleBytes() {
            return bundleBytes.clone();
        }
    }
}

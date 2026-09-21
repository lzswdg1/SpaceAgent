package com.spaceagent.platform.project.domain;

import java.util.List;

public interface ProjectLocalMaterializationSnapshotGateway {
    Snapshot publish(ProjectLocalMaterializationSession session,
                     ProjectLocalMaterializationManifestValidator.Manifest manifest,
                     List<ProjectLocalMaterializationChunk> chunks);
    void deleteSnapshot(String snapshotRef);

    record Snapshot(String snapshotRef, String contentSha256) { }
}

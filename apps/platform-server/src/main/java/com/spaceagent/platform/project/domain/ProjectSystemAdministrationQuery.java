package com.spaceagent.platform.project.domain;

import java.time.Instant;
import java.util.List;

public interface ProjectSystemAdministrationQuery {
    OverviewRow overview();

    PageRows<ResourceRow> projectsByOwner(String userId, int offset, int limit);

    PageRows<ResourceRow> tasksByOwner(String userId, int offset, int limit);

    PageRows<ResourceRow> workspacesByOwner(String userId, int offset, int limit);

    DeletionEvidenceRow deletionEvidence(String userId);

    record OverviewRow(long projects, long activeProjects, long workspaces, long activeWorkspaces) {
    }

    record PageRows<T>(List<T> items, long total) {
        public PageRows { items = items == null ? List.of() : List.copyOf(items); }
    }

    record ResourceRow(String id, String organizationId, String parentId, String displayName,
                       String state, String relation, Instant createdAt, Instant updatedAt,
                       String safeErrorCode, long primaryCount, long secondaryCount) {
    }

    record DeletionEvidenceRow(long ownedProjects, long sharedOwnedProjects,
                               long activeWorkspaces) {
    }
}

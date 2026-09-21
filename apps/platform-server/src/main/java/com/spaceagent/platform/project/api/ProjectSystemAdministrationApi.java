package com.spaceagent.platform.project.api;

import com.spaceagent.platform.shared.api.SystemAdministrationPage;
import com.spaceagent.platform.shared.api.UserResourceSummary;

public interface ProjectSystemAdministrationApi {
    ProjectOverview overview();

    SystemAdministrationPage<UserResourceSummary> projectsByOwner(
            String userId, int page, int pageSize);

    SystemAdministrationPage<UserResourceSummary> tasksByOwner(
            String userId, int page, int pageSize);

    SystemAdministrationPage<UserResourceSummary> workspacesByOwner(
            String userId, int page, int pageSize);

    ProjectDeletionEvidence deletionEvidence(String userId);

    record ProjectOverview(long projects, long activeProjects, long workspaces, long activeWorkspaces) {
    }

    record ProjectDeletionEvidence(long ownedProjects, long sharedOwnedProjects,
                                   long activeWorkspaces) {
    }
}

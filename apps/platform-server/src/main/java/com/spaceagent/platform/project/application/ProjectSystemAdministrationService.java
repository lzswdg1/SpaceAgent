package com.spaceagent.platform.project.application;

import com.spaceagent.platform.project.api.ProjectSystemAdministrationApi;
import com.spaceagent.platform.project.domain.ProjectSystemAdministrationQuery;
import com.spaceagent.platform.shared.api.SystemAdministrationPage;
import com.spaceagent.platform.shared.api.UserResourceSummary;
import com.spaceagent.shared.time.TimeProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@ConditionalOnProperty(prefix = "platform", name = "persistence", havingValue = "postgres")
@Transactional(readOnly = true)
public class ProjectSystemAdministrationService implements ProjectSystemAdministrationApi {
    private final ProjectSystemAdministrationQuery query;
    private final TimeProvider timeProvider;

    public ProjectSystemAdministrationService(
            ProjectSystemAdministrationQuery query, TimeProvider timeProvider) {
        this.query = query;
        this.timeProvider = timeProvider;
    }

    @Override
    public ProjectOverview overview() {
        var row = query.overview();
        return new ProjectOverview(row.projects(), row.activeProjects(), row.workspaces(), row.activeWorkspaces());
    }

    @Override public SystemAdministrationPage<UserResourceSummary> projectsByOwner(
            String userId, int page, int pageSize) {
        return page("PROJECT", userId, page, pageSize, 0);
    }

    @Override public SystemAdministrationPage<UserResourceSummary> tasksByOwner(
            String userId, int page, int pageSize) {
        return page("TASK", userId, page, pageSize, 1);
    }

    @Override public SystemAdministrationPage<UserResourceSummary> workspacesByOwner(
            String userId, int page, int pageSize) {
        return page("WORKSPACE", userId, page, pageSize, 2);
    }

    private SystemAdministrationPage<UserResourceSummary> page(
            String kind, String userId, int page, int pageSize, int source) {
        if (userId == null || userId.isBlank()) throw new IllegalArgumentException("userId is required");
        int safePage = Math.max(0, page);
        int safeSize = Math.max(1, Math.min(100, pageSize));
        ProjectSystemAdministrationQuery.PageRows<ProjectSystemAdministrationQuery.ResourceRow> rows =
                switch (source) {
                    case 0 -> query.projectsByOwner(userId.trim(), safePage * safeSize, safeSize);
                    case 1 -> query.tasksByOwner(userId.trim(), safePage * safeSize, safeSize);
                    default -> query.workspacesByOwner(userId.trim(), safePage * safeSize, safeSize);
                };
        return new SystemAdministrationPage<>(rows.items().stream().map(row -> new UserResourceSummary(
                kind, row.id(), row.organizationId(), row.parentId(), row.displayName(), row.state(),
                row.relation(), row.createdAt(), row.updatedAt(), row.safeErrorCode(),
                row.primaryCount(), row.secondaryCount())).toList(), safePage, safeSize, rows.total(),
                timeProvider.now());
    }

    @Override public ProjectDeletionEvidence deletionEvidence(String userId) {
        var row = query.deletionEvidence(userId);
        return new ProjectDeletionEvidence(row.ownedProjects(), row.sharedOwnedProjects(),
                row.activeWorkspaces());
    }
}

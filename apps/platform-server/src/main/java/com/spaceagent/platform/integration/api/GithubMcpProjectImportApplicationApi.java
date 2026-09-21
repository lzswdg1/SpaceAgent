package com.spaceagent.platform.integration.api;

import com.spaceagent.platform.project.api.SourceRepositoryView;

public interface GithubMcpProjectImportApplicationApi {
    SourceRepositoryView importRepository(Command command);

    record Command(
            String tenantId,
            String userId,
            String projectId,
            String connectionId,
            String providerRepositoryId,
            String githubUrl,
            String idempotencyKey) {
    }
}

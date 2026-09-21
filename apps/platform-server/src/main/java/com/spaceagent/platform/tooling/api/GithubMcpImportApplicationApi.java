package com.spaceagent.platform.tooling.api;

public interface GithubMcpImportApplicationApi {
    ImportView resolve(ImportCommand command);

    record ImportCommand(
            String tenantId,
            String userId,
            String projectId,
            String connectionId,
            String providerRepositoryId,
            String githubUrl,
            String idempotencyKey) {
    }

    record ImportView(
            String invocationId,
            String connectionId,
            String providerRepositoryId,
            String owner,
            String name,
            String htmlUrl,
            String cloneUrl,
            String defaultBranch,
            boolean privateRepository,
            boolean archived) {
    }
}

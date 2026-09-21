package com.spaceagent.platform.project.api;

/** Validated, secret-free repository result supplied by the Tooling public API. */
public record ImportGithubMcpRepositoryCommand(
        String tenantId,
        String userId,
        String projectId,
        String mcpConnectionId,
        String mcpInvocationId,
        String providerRepositoryId,
        String owner,
        String repository,
        String cloneUrl,
        String defaultBranch,
        boolean privateRepository,
        boolean archived) {
}

package com.spaceagent.platform.integration.application;

import com.spaceagent.platform.integration.api.GithubMcpProjectImportApplicationApi;
import com.spaceagent.platform.project.api.*;
import com.spaceagent.platform.tooling.api.GithubMcpImportApplicationApi;
import org.springframework.stereotype.Service;

/** Coordinates Tooling-owned remote evidence with Project-owned source persistence. */
@Service
public class GithubMcpProjectImportCoordinator implements GithubMcpProjectImportApplicationApi {
    private final GithubMcpImportApplicationApi tooling;
    private final SourceRepositoryApplicationApi sources;

    public GithubMcpProjectImportCoordinator(
            GithubMcpImportApplicationApi tooling,
            SourceRepositoryApplicationApi sources) {
        this.tooling = tooling;
        this.sources = sources;
    }

    @Override
    public SourceRepositoryView importRepository(Command command) {
        sources.requireImportAccess(new RequireSourceImportAccessCommand(
                command.tenantId(), command.userId(), command.projectId()));
        GithubMcpImportApplicationApi.ImportView remote = tooling.resolve(
                new GithubMcpImportApplicationApi.ImportCommand(
                        command.tenantId(), command.userId(), command.projectId(),
                        command.connectionId(), command.providerRepositoryId(),
                        command.githubUrl(), command.idempotencyKey()));
        return sources.importGithubMcp(new ImportGithubMcpRepositoryCommand(
                command.tenantId(), command.userId(), command.projectId(),
                remote.connectionId(), remote.invocationId(), remote.providerRepositoryId(), remote.owner(),
                remote.name(), remote.cloneUrl(), remote.defaultBranch(),
                remote.privateRepository(), remote.archived()));
    }
}

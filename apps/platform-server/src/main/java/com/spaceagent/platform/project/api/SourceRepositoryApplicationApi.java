package com.spaceagent.platform.project.api;

import java.util.List;

public interface SourceRepositoryApplicationApi {

    SourceRepositoryView importGithubMcp(ImportGithubMcpRepositoryCommand command);

    void requireImportAccess(RequireSourceImportAccessCommand command);

    SourceRepositoryView importLocal(ImportLocalRepositoryCommand command);

    SourceRepositoryView get(GetSourceRepositoryQuery query);

    List<SourceRepositoryView> list(ListSourceRepositoriesQuery query);

    SourceRepositoryView archive(ArchiveSourceRepositoryCommand command);
}

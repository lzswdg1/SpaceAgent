package com.spaceagent.platform.tooling.api;import java.time.Instant;import java.util.List;
public interface GithubMcpApplicationApi{
 OAuthView beginOAuth(BeginOAuthCommand command);AccountView completeOAuth(CompleteOAuthCommand command);
 List<RepositoryView> repositories(String tenantId,String userId,String connectionId);
 List<RepositoryView> searchRepositories(SearchCommand command);
 RepositoryView discover(DiscoverCommand command);
 record BeginOAuthCommand(String tenantId,String userId,String connectionId,String redirectUri){}
 record CompleteOAuthCommand(String tenantId,String userId,String state,String code){}
 record DiscoverCommand(String tenantId,String userId,String connectionId,String githubUrl){}
 record SearchCommand(String tenantId,String userId,String connectionId,String query,int limit){}
 record OAuthView(String state,String authorizationUrl,Instant expiresAt){}
 record AccountView(String connectionId,String accountId,String login){}
 record RepositoryView(String providerRepositoryId,String owner,String name,String htmlUrl,String cloneUrl,String defaultBranch,boolean privateRepository,boolean archived){}
}

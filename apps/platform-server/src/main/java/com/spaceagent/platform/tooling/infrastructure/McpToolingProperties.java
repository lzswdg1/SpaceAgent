package com.spaceagent.platform.tooling.infrastructure;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.List;

@ConfigurationProperties(prefix = "platform.tooling.mcp")
public class McpToolingProperties {
    private String encryptionKey = "local-dev-mcp-connection-encryption-key-change-me";
    private List<String> previousEncryptionKeys = new ArrayList<>();
    private List<String> allowedHosts =
            new ArrayList<>(List.of("api.githubcopilot.com", "github.com"));
    private final Github github = new Github();
    private final OAuth oauth = new OAuth();
    private final Registry registry = new Registry();

    public String getEncryptionKey() {
        return encryptionKey;
    }

    public void setEncryptionKey(String encryptionKey) {
        this.encryptionKey = encryptionKey;
    }

    public List<String> getPreviousEncryptionKeys() {
        return previousEncryptionKeys;
    }

    public void setPreviousEncryptionKeys(List<String> previousEncryptionKeys) {
        this.previousEncryptionKeys = previousEncryptionKeys == null
                ? new ArrayList<>() : new ArrayList<>(previousEncryptionKeys);
    }

    public List<String> getAllowedHosts() {
        return allowedHosts;
    }

    public void setAllowedHosts(List<String> allowedHosts) {
        this.allowedHosts = allowedHosts == null
                ? new ArrayList<>() : new ArrayList<>(allowedHosts);
    }

    public Github getGithub() {
        return github;
    }

    public OAuth getOauth() {
        return oauth;
    }

    public Registry getRegistry() {
        return registry;
    }

    public static class Github {
        private String clientId = "";
        private String clientSecret = "";
        private List<String> scopes =
                new ArrayList<>(List.of("repo", "read:user", "read:org"));
        private List<String> allowedRedirectUris = new ArrayList<>();

        public String getClientId() { return clientId; }
        public void setClientId(String clientId) { this.clientId = clientId; }
        public String getClientSecret() { return clientSecret; }
        public void setClientSecret(String clientSecret) { this.clientSecret = clientSecret; }
        public List<String> getScopes() { return scopes; }
        public void setScopes(List<String> scopes) {
            this.scopes = scopes == null ? new ArrayList<>() : new ArrayList<>(scopes);
        }
        public List<String> getAllowedRedirectUris() { return allowedRedirectUris; }
        public void setAllowedRedirectUris(List<String> allowedRedirectUris) {
            this.allowedRedirectUris = allowedRedirectUris == null
                    ? new ArrayList<>() : new ArrayList<>(allowedRedirectUris);
        }
    }

    public static class OAuth {
        private List<Client> clients = new ArrayList<>();

        public List<Client> getClients() { return clients; }
        public void setClients(List<Client> clients) {
            this.clients = clients == null ? new ArrayList<>() : new ArrayList<>(clients);
        }
    }

    public static class Client {
        private String id = "";
        private String authorizationServer = "";
        private String clientId = "";
        private String clientSecret = "";
        private String authenticationMethod = "CLIENT_SECRET_POST";
        private List<String> scopes = new ArrayList<>();
        private List<String> allowedRedirectUris = new ArrayList<>();

        public String getId() { return id; }
        public void setId(String id) { this.id = id; }
        public String getAuthorizationServer() { return authorizationServer; }
        public void setAuthorizationServer(String authorizationServer) {
            this.authorizationServer = authorizationServer;
        }
        public String getClientId() { return clientId; }
        public void setClientId(String clientId) { this.clientId = clientId; }
        public String getClientSecret() { return clientSecret; }
        public void setClientSecret(String clientSecret) { this.clientSecret = clientSecret; }
        public String getAuthenticationMethod() { return authenticationMethod; }
        public void setAuthenticationMethod(String authenticationMethod) {
            this.authenticationMethod = authenticationMethod;
        }
        public List<String> getScopes() { return scopes; }
        public void setScopes(List<String> scopes) {
            this.scopes = scopes == null ? new ArrayList<>() : new ArrayList<>(scopes);
        }
        public List<String> getAllowedRedirectUris() { return allowedRedirectUris; }
        public void setAllowedRedirectUris(List<String> allowedRedirectUris) {
            this.allowedRedirectUris = allowedRedirectUris == null
                    ? new ArrayList<>() : new ArrayList<>(allowedRedirectUris);
        }
    }

    public static class Registry {
        private String baseUrl = "https://registry.modelcontextprotocol.io";
        private boolean workerEnabled = true;
        private String workerId = "";
        private int pollDelayMs = 1000;
        private int pageSize = 100;
        private int maximumPages = 25;
        private int maximumServers = 2500;
        private int maximumResponseBytes = 2_000_000;
        private int connectTimeoutSeconds = 5;
        private int requestTimeoutSeconds = 20;
        private int leaseSeconds = 120;
        private int maximumAttempts = 3;

        public String getBaseUrl() { return baseUrl; }
        public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }
        public boolean isWorkerEnabled() { return workerEnabled; }
        public void setWorkerEnabled(boolean workerEnabled) { this.workerEnabled = workerEnabled; }
        public String getWorkerId() { return workerId; }
        public void setWorkerId(String workerId) { this.workerId = workerId; }
        public int getPollDelayMs() { return pollDelayMs; }
        public void setPollDelayMs(int pollDelayMs) { this.pollDelayMs = pollDelayMs; }
        public int getPageSize() { return pageSize; }
        public void setPageSize(int pageSize) { this.pageSize = pageSize; }
        public int getMaximumPages() { return maximumPages; }
        public void setMaximumPages(int maximumPages) { this.maximumPages = maximumPages; }
        public int getMaximumServers() { return maximumServers; }
        public void setMaximumServers(int maximumServers) { this.maximumServers = maximumServers; }
        public int getMaximumResponseBytes() { return maximumResponseBytes; }
        public void setMaximumResponseBytes(int maximumResponseBytes) {
            this.maximumResponseBytes = maximumResponseBytes;
        }
        public int getConnectTimeoutSeconds() { return connectTimeoutSeconds; }
        public void setConnectTimeoutSeconds(int connectTimeoutSeconds) {
            this.connectTimeoutSeconds = connectTimeoutSeconds;
        }
        public int getRequestTimeoutSeconds() { return requestTimeoutSeconds; }
        public void setRequestTimeoutSeconds(int requestTimeoutSeconds) {
            this.requestTimeoutSeconds = requestTimeoutSeconds;
        }
        public int getLeaseSeconds() { return leaseSeconds; }
        public void setLeaseSeconds(int leaseSeconds) { this.leaseSeconds = leaseSeconds; }
        public int getMaximumAttempts() { return maximumAttempts; }
        public void setMaximumAttempts(int maximumAttempts) {
            this.maximumAttempts = maximumAttempts;
        }
    }
}

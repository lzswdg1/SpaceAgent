package com.spaceagent.platform.tooling.domain;
import java.time.Instant;
public record McpConnection(String id,String installationId,String tenantId,String managedBy,
        String endpointUrl,String encryptedAuthJson,McpAuthType authType,McpConnectionState state,
        String externalAccountId,String externalAccountName,long revision,
        Instant createdAt,Instant updatedAt,Instant revokedAt) {}

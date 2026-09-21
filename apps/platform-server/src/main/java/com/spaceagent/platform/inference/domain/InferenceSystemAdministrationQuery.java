package com.spaceagent.platform.inference.domain;

import java.time.Instant;
import java.util.List;

public interface InferenceSystemAdministrationQuery {
    OverviewRow overview();

    PageRows<ProviderRow> providerCredentials(int offset, int limit);

    PageRows<ProviderRow> providerCredentialsByOwner(String userId, int offset, int limit);

    PageRows<ResourceRow> modelPoolsByOwner(String userId, int offset, int limit);

    PageRows<ResourceRow> modelEffectsByOwner(String userId, int offset, int limit);

    DeletionEvidenceRow deletionEvidence(String userId);

    record OverviewRow(long providers, long activeProviders, long unhealthyProviders,
                       long untestedProviders, long disabledProviders,
                       long modelPools, long activeModelPools, long unknownModelCalls) {
    }

    record PageRows<T>(List<T> items, long total) {
        public PageRows { items = items == null ? List.of() : List.copyOf(items); }
    }

    record ProviderRow(String id, String organizationId, String ownerUserId, String name,
                       String providerType, String baseUrl, String authType,
                       boolean secretConfigured, String secretHint, String secretFingerprint,
                       String secretKeyVersion, boolean enabled, boolean defaultProvider,
                       String connectionStatus, Instant lastTestedAt, Integer lastTestLatencyMs,
                       String safeErrorCode, long modelCount, long modelPoolUsageCount) {
    }

    record ResourceRow(String id, String organizationId, String parentId, String displayName,
                       String state, String relation, Instant createdAt, Instant updatedAt,
                       String safeErrorCode, long primaryCount, long secondaryCount) {
    }

    record DeletionEvidenceRow(long ownedProviders, long ownedModelPools,
                               long unknownModelCalls) {
    }
}

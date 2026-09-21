package com.spaceagent.platform.inference.api;

import com.spaceagent.platform.shared.api.SystemAdministrationPage;
import com.spaceagent.platform.shared.api.UserResourceSummary;

import java.time.Instant;

public interface InferenceSystemAdministrationApi {
    InferenceOverview overview();

    SystemAdministrationPage<ProviderCredential> providerCredentials(int page, int pageSize);

    SystemAdministrationPage<ProviderCredential> providerCredentialsByOwner(
            String userId, int page, int pageSize);

    SystemAdministrationPage<UserResourceSummary> modelPoolsByOwner(
            String userId, int page, int pageSize);

    SystemAdministrationPage<UserResourceSummary> modelEffectsByOwner(
            String userId, int page, int pageSize);

    InferenceDeletionEvidence deletionEvidence(String userId);

    record InferenceOverview(long providers, long activeProviders, long unhealthyProviders,
                             long untestedProviders, long disabledProviders,
                             long modelPools, long activeModelPools, Long unknownModelCalls) {
        public InferenceOverview(long providers, long activeProviders, long unhealthyProviders,
                long untestedProviders, long disabledProviders, long modelPools, long activeModelPools) {
            this(providers, activeProviders, unhealthyProviders, untestedProviders, disabledProviders,
                    modelPools, activeModelPools, null);
        }
    }

    record ProviderCredential(
            String kind,
            String id,
            String organizationId,
            String ownerUserId,
            String name,
            String providerType,
            String baseUrl,
            String endpointHost,
            String authType,
            boolean secretConfigured,
            String secretHint,
            String secretFingerprint,
            String secretKeyVersion,
            boolean enabled,
            boolean defaultProvider,
            String connectionStatus,
            Instant lastTestedAt,
            Integer lastTestLatencyMs,
            String safeErrorCode,
            long modelCount,
            long modelPoolUsageCount) {
    }

    record InferenceDeletionEvidence(long ownedProviders, long ownedModelPools,
                                     long unknownModelCalls) {
    }
}

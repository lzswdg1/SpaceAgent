package com.spaceagent.platform.inference.application;

import com.spaceagent.platform.inference.api.InferenceSystemAdministrationApi;
import com.spaceagent.platform.inference.domain.InferenceSystemAdministrationQuery;
import com.spaceagent.platform.shared.api.SystemAdministrationPage;
import com.spaceagent.platform.shared.api.UserResourceSummary;
import com.spaceagent.shared.time.TimeProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.net.URI;

@Service
@ConditionalOnProperty(prefix = "platform", name = "persistence", havingValue = "postgres")
@Transactional(readOnly = true)
public class InferenceSystemAdministrationService implements InferenceSystemAdministrationApi {
    private final InferenceSystemAdministrationQuery query;
    private final TimeProvider timeProvider;

    public InferenceSystemAdministrationService(
            InferenceSystemAdministrationQuery query,
            TimeProvider timeProvider) {
        this.query = query;
        this.timeProvider = timeProvider;
    }

    @Override
    public InferenceOverview overview() {
        var row = query.overview();
        return new InferenceOverview(row.providers(), row.activeProviders(), row.unhealthyProviders(),
                row.untestedProviders(), row.disabledProviders(), row.modelPools(), row.activeModelPools(), row.unknownModelCalls());
    }

    @Override
    public SystemAdministrationPage<ProviderCredential> providerCredentials(int page, int pageSize) {
        return providerPage(page, pageSize, null);
    }

    @Override
    public SystemAdministrationPage<ProviderCredential> providerCredentialsByOwner(
            String userId, int page, int pageSize) {
        if (userId == null || userId.isBlank()) {
            throw new IllegalArgumentException("userId is required");
        }
        return providerPage(page, pageSize, userId.trim());
    }

    @Override
    public SystemAdministrationPage<UserResourceSummary> modelPoolsByOwner(
            String userId, int page, int pageSize) {
        return resourcePage("MODEL_POOL", userId, page, pageSize, true);
    }

    @Override
    public SystemAdministrationPage<UserResourceSummary> modelEffectsByOwner(
            String userId, int page, int pageSize) {
        return resourcePage("MODEL_EFFECT", userId, page, pageSize, false);
    }

    private SystemAdministrationPage<UserResourceSummary> resourcePage(
            String kind, String userId, int page, int pageSize, boolean pools) {
        if (userId == null || userId.isBlank()) throw new IllegalArgumentException("userId is required");
        int safePage = Math.max(0, page);
        int safeSize = Math.max(1, Math.min(100, pageSize));
        var rows = pools
                ? query.modelPoolsByOwner(userId.trim(), safePage * safeSize, safeSize)
                : query.modelEffectsByOwner(userId.trim(), safePage * safeSize, safeSize);
        return new SystemAdministrationPage<>(rows.items().stream().map(row -> new UserResourceSummary(
                kind, row.id(), row.organizationId(), row.parentId(), row.displayName(), row.state(),
                row.relation(), row.createdAt(), row.updatedAt(), row.safeErrorCode(),
                row.primaryCount(), row.secondaryCount())).toList(), safePage, safeSize, rows.total(),
                timeProvider.now());
    }

    private SystemAdministrationPage<ProviderCredential> providerPage(
            int page, int pageSize, String userId) {
        int safePage = Math.max(0, page);
        int safeSize = Math.max(1, Math.min(100, pageSize));
        var rows = userId == null
                ? query.providerCredentials(safePage * safeSize, safeSize)
                : query.providerCredentialsByOwner(userId, safePage * safeSize, safeSize);
        return new SystemAdministrationPage<>(rows.items().stream().map(row -> new ProviderCredential(
                "PROVIDER", row.id(), row.organizationId(), row.ownerUserId(), row.name(),
                row.providerType(), row.baseUrl(), endpointHost(row.baseUrl()), row.authType(),
                row.secretConfigured(), row.secretHint(), row.secretFingerprint(), row.secretKeyVersion(),
                row.enabled(), row.defaultProvider(), row.connectionStatus(), row.lastTestedAt(),
                row.lastTestLatencyMs(), row.safeErrorCode(), row.modelCount(),
                row.modelPoolUsageCount())).toList(), safePage, safeSize, rows.total(), timeProvider.now());
    }

    @Override public InferenceDeletionEvidence deletionEvidence(String userId) {
        var row = query.deletionEvidence(userId);
        return new InferenceDeletionEvidence(row.ownedProviders(), row.ownedModelPools(),
                row.unknownModelCalls());
    }

    private static String endpointHost(String baseUrl) {
        try {
            return URI.create(baseUrl).getHost();
        } catch (RuntimeException error) {
            return null;
        }
    }
}

package com.spaceagent.platform.integration.application;

import com.spaceagent.platform.automation.api.AutomationRepositoryEventApplicationApi;
import com.spaceagent.platform.automation.domain.AutomationTriggerSource;
import com.spaceagent.platform.tooling.api.McpConnectionQualificationApplicationApi;
import com.spaceagent.platform.tooling.api.McpMarketplaceApplicationApi;
import com.spaceagent.platform.tooling.domain.McpConnectionState;
import com.spaceagent.shared.exception.BusinessException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;

@Service
public class AutomationRepositoryEventCoordinator {
    private final AutomationRepositoryEventApplicationApi automation;
    private final McpConnectionQualificationApplicationApi qualifications;
    private final McpMarketplaceApplicationApi marketplace;

    public AutomationRepositoryEventCoordinator(
            AutomationRepositoryEventApplicationApi automation,
            McpConnectionQualificationApplicationApi qualifications,
            McpMarketplaceApplicationApi marketplace) {
        this.automation = automation;
        this.qualifications = qualifications;
        this.marketplace = marketplace;
    }

    public Result accept(Command command) {
        validate(command);
        var policy = automation.prepare(command.triggerVersionId(), command.subscriptionId());
        if (!policy.providerRepositoryId().equals(command.providerRepositoryId())
                || !policy.events().contains(command.event())) throw unsupported();
        try {
            var qualification = qualifications.qualification(
                    policy.tenantId(), policy.ownerId(), policy.connectionId());
            boolean connectionMatches = marketplace.connections(policy.tenantId(), policy.ownerId()).stream()
                    .anyMatch(connection -> connection.id().equals(policy.connectionId())
                            && connection.installationId().equals(policy.installationId())
                            && connection.tenantId().equals(policy.tenantId())
                            && connection.state() == McpConnectionState.ACTIVE
                            && connection.revision() == policy.connectionRevision());
            String snapshotHash = qualification.snapshotSha256() == null ? null
                    : qualification.snapshotSha256().startsWith("sha256:")
                            ? qualification.snapshotSha256()
                            : "sha256:" + qualification.snapshotSha256();
            if (!connectionMatches
                    || qualification.state() != McpConnectionState.ACTIVE
                    || qualification.connectionRevision() != policy.connectionRevision()
                    || !policy.capabilitySnapshotId().equals(qualification.snapshotId())
                    || !policy.snapshotSha256().equals(snapshotHash)) throw bindingInvalid();
        } catch (BusinessException error) {
            if ("AUTOMATION_REPOSITORY_EVENT_BINDING_INVALID".equals(error.getCode())) throw error;
            throw bindingInvalid();
        } catch (RuntimeException error) {
            throw bindingInvalid();
        }
        String sourceEventHash = sha256(String.join("\n", command.providerRepositoryId(),
                command.event().name(), command.providerEventId()));
        var result = automation.admit(new AutomationRepositoryEventApplicationApi.VerifiedRepositoryEvent(
                policy, command.event(), sourceEventHash, command.payloadSha256(), command.occurredAt()));
        return new Result(result.occurrenceId());
    }

    private static void validate(Command command) {
        if (command == null || command.providerEventId() == null
                || !command.providerEventId().matches("[A-Za-z0-9_.:-]{1,160}")
                || command.payloadSha256() == null
                || !command.payloadSha256().matches("sha256:[0-9a-f]{64}")
                || command.occurredAt() == null || command.event() == null) {
            throw unsupported();
        }
    }

    private static String sha256(String value) {
        try {
            return "sha256:" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception error) {
            throw new IllegalStateException("SHA-256 unavailable", error);
        }
    }

    private static BusinessException bindingInvalid() {
        return new BusinessException("Repository event binding is not currently qualified",
                HttpStatus.CONFLICT, "AUTOMATION_REPOSITORY_EVENT_BINDING_INVALID");
    }

    private static BusinessException unsupported() {
        return new BusinessException("Repository event is not accepted", HttpStatus.CONFLICT,
                "AUTOMATION_REPOSITORY_EVENT_UNSUPPORTED");
    }

    public record Command(
            String triggerVersionId,
            String subscriptionId,
            String providerRepositoryId,
            AutomationTriggerSource.RepositoryEvent event,
            String providerEventId,
            String payloadSha256,
            Instant occurredAt) {
    }

    public record Result(String occurrenceId) {
    }
}

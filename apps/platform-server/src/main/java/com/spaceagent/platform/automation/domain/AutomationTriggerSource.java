package com.spaceagent.platform.automation.domain;

import java.util.List;
import java.util.Objects;
import java.util.Set;

/** Immutable, secret-free source binding for an event-driven Automation Trigger version. */
public sealed interface AutomationTriggerSource permits
        AutomationTriggerSource.Webhook,
        AutomationTriggerSource.Repository,
        AutomationTriggerSource.TaskCompletion,
        AutomationTriggerSource.FollowUp {

    AutomationTriggerType type();

    String canonicalValue();

    record Webhook(
            List<String> signingKeyRefs,
            String signatureAlgorithm,
            int maxBodyBytes,
            int maxAgeSeconds) implements AutomationTriggerSource {
        public Webhook {
            signingKeyRefs = List.copyOf(signingKeyRefs == null ? List.of() : signingKeyRefs);
            if (signingKeyRefs.isEmpty() || signingKeyRefs.size() > 2
                    || signingKeyRefs.stream().distinct().count() != signingKeyRefs.size()
                    || signingKeyRefs.stream().anyMatch(value -> value == null
                            || !value.matches("webhook-key:[A-Za-z0-9_.:-]{1,140}"))) {
                throw invalid("Webhook signing key references are invalid");
            }
            if (!"HMAC_SHA256".equals(signatureAlgorithm)
                    || maxBodyBytes < 1 || maxBodyBytes > 1_000_000
                    || maxAgeSeconds < 30 || maxAgeSeconds > 900) {
                throw invalid("Webhook verification policy is invalid");
            }
        }

        @Override public AutomationTriggerType type() {
            return AutomationTriggerType.WEBHOOK;
        }

        @Override public String canonicalValue() {
            return String.join("\n", String.join(",", signingKeyRefs), signatureAlgorithm,
                    Integer.toString(maxBodyBytes), Integer.toString(maxAgeSeconds));
        }
    }

    record Repository(
            String installationId,
            String connectionId,
            long connectionRevision,
            String capabilitySnapshotId,
            String snapshotSha256,
            String providerRepositoryId,
            Set<RepositoryEvent> events) implements AutomationTriggerSource {
        public Repository {
            require(installationId, "installationId");
            require(connectionId, "connectionId");
            if (connectionRevision <= 0) throw invalid("connectionRevision must be positive");
            require(capabilitySnapshotId, "capabilitySnapshotId");
            hash(snapshotSha256, "snapshotSha256");
            if (providerRepositoryId == null
                    || !providerRepositoryId.matches("[A-Za-z0-9_.-]{1,100}/[A-Za-z0-9_.-]{1,100}")) {
                throw invalid("providerRepositoryId is invalid");
            }
            events = Set.copyOf(events == null ? Set.of() : events);
            if (events.isEmpty()) throw invalid("Repository events are required");
        }

        @Override public AutomationTriggerType type() {
            return AutomationTriggerType.REPOSITORY;
        }

        @Override public String canonicalValue() {
            String eventNames = events.stream().map(Enum::name).sorted()
                    .collect(java.util.stream.Collectors.joining(","));
            return String.join("\n", installationId, connectionId,
                    Long.toString(connectionRevision), capabilitySnapshotId,
                    snapshotSha256, providerRepositoryId, eventNames);
        }
    }

    record TaskCompletion(
            String projectId,
            String taskId,
            Set<TaskOutcome> outcomes) implements AutomationTriggerSource {
        public TaskCompletion {
            require(projectId, "projectId");
            require(taskId, "taskId");
            outcomes = Set.copyOf(outcomes == null ? Set.of() : outcomes);
            if (outcomes.isEmpty()) throw invalid("Task outcomes are required");
        }

        @Override public AutomationTriggerType type() {
            return AutomationTriggerType.TASK_COMPLETION;
        }

        @Override public String canonicalValue() {
            return String.join("\n", projectId, taskId,
                    outcomes.stream().map(Enum::name).sorted()
                            .collect(java.util.stream.Collectors.joining(",")));
        }
    }

    record FollowUp(
            String conversationId,
            String sourceTaskId,
            int delaySeconds) implements AutomationTriggerSource {
        public FollowUp {
            require(conversationId, "conversationId");
            require(sourceTaskId, "sourceTaskId");
            if (delaySeconds < 60 || delaySeconds > 2_592_000) {
                throw invalid("Follow-up delay is invalid");
            }
        }

        @Override public AutomationTriggerType type() {
            return AutomationTriggerType.FOLLOW_UP;
        }

        @Override public String canonicalValue() {
            return String.join("\n", conversationId, sourceTaskId,
                    Integer.toString(delaySeconds));
        }
    }

    enum RepositoryEvent {
        PUSH,
        PULL_REQUEST,
        ISSUE
    }

    enum TaskOutcome {
        SUCCEEDED,
        FAILED,
        CANCELLED
    }

    private static void require(String value, String field) {
        if (value == null || value.isBlank() || value.length() > 160) {
            throw invalid(field + " is invalid");
        }
    }

    private static void hash(String value, String field) {
        if (value == null || !value.matches("sha256:[0-9a-f]{64}")) {
            throw invalid(field + " is invalid");
        }
    }

    private static IllegalArgumentException invalid(String message) {
        return new IllegalArgumentException(message);
    }
}

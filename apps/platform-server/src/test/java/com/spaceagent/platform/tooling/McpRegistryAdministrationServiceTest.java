package com.spaceagent.platform.tooling;

import com.spaceagent.platform.tooling.application.McpRegistryAdministrationService;
import com.spaceagent.platform.tooling.domain.McpRegistryCompatibility;
import com.spaceagent.platform.tooling.domain.McpRegistryGateway;
import com.spaceagent.platform.tooling.domain.McpRegistryReviewState;
import com.spaceagent.platform.tooling.domain.McpRegistrySnapshot;
import com.spaceagent.platform.tooling.domain.McpRegistryStatus;
import com.spaceagent.platform.tooling.domain.McpRegistrySyncState;
import com.spaceagent.platform.tooling.infrastructure.McpToolingProperties;
import com.spaceagent.platform.tooling.infrastructure.memory.InMemoryMcpMarketplaceRepository;
import com.spaceagent.platform.tooling.infrastructure.memory.InMemoryMcpRegistryRepository;
import com.spaceagent.shared.id.UuidGenerator;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class McpRegistryAdministrationServiceTest {
    @Test
    void synchronizationOnlyCreatesReviewEvidenceAndApprovalPublishesSupportedRemote() {
        var registry = new InMemoryMcpRegistryRepository();
        var marketplace = new InMemoryMcpMarketplaceRepository();
        var gateway = new FixtureGateway();
        var service = new McpRegistryAdministrationService(
                registry, marketplace, gateway, new McpToolingProperties(),
                new UuidGenerator(), () -> Instant.parse("2026-09-05T00:00:00Z"));
        String actor = UUID.randomUUID().toString();

        var queued = service.enqueue(actor);
        assertThat(queued.state()).isEqualTo(McpRegistrySyncState.PENDING);
        assertThat(marketplace.findEntryByRegistryName("io.github.example/weather")).isEmpty();
        assertThat(service.runOnce("worker-1")).isTrue();
        assertThat(gateway.cursors).containsExactly(null, "next");

        var jobs = service.syncJobs(0, 10, null);
        assertThat(jobs.items()).singleElement()
                .satisfies(job -> {
                    assertThat(job.state()).isEqualTo(McpRegistrySyncState.SUCCEEDED);
                    assertThat(job.fetchedCount()).isEqualTo(2);
                    assertThat(job.candidateCount()).isEqualTo(2);
                });
        var candidates = service.candidates(0, 10, McpRegistryReviewState.PENDING_REVIEW, null);
        assertThat(candidates.total()).isEqualTo(2);
        var supported = candidates.items().stream()
                .filter(value -> value.compatibility() == McpRegistryCompatibility.SUPPORTED_REMOTE)
                .findFirst().orElseThrow();
        var unsupported = candidates.items().stream()
                .filter(value -> value.compatibility() == McpRegistryCompatibility.UNSUPPORTED_TRANSPORT)
                .findFirst().orElseThrow();

        var approved = service.approve(new com.spaceagent.platform.tooling.api
                .McpRegistryAdministrationApi.ReviewCommand(
                supported.id(), actor, "Reviewed source and remote endpoint"));
        assertThat(approved.reviewState()).isEqualTo(McpRegistryReviewState.APPROVED);
        assertThat(marketplace.findEntryByRegistryName("io.github.example/weather"))
                .get().extracting(entry -> entry.currentVersion()).isEqualTo("1.2.3");
        assertThatThrownBy(() -> service.approve(new com.spaceagent.platform.tooling.api
                .McpRegistryAdministrationApi.ReviewCommand(
                unsupported.id(), actor, "Cannot execute local package")))
                .isInstanceOf(com.spaceagent.shared.exception.BusinessException.class)
                .hasMessageContaining("supported remote");
        assertThat(service.reject(new com.spaceagent.platform.tooling.api
                .McpRegistryAdministrationApi.ReviewCommand(
                unsupported.id(), actor, "Package execution is outside the platform boundary"))
                .reviewState()).isEqualTo(McpRegistryReviewState.REJECTED);

        var second = service.enqueue(actor);
        assertThat(second.updatedSince()).isEqualTo(Instant.parse("2026-09-05T00:00:00Z"));
        service.runOnce("worker-1");
        assertThat(service.candidates(0, 10, null, null).total()).isEqualTo(2);
    }

    @Test
    void repeatedCursorFailsSafelyWithoutCreatingCandidatesOrAdvancingWatermark() {
        var registry = new InMemoryMcpRegistryRepository();
        var service = new McpRegistryAdministrationService(
                registry, new InMemoryMcpMarketplaceRepository(),
                (baseUrl, updatedSince, cursor, limit) ->
                        new McpRegistryGateway.RegistryPage(List.of(), "repeat"),
                new McpToolingProperties(), new UuidGenerator(),
                () -> Instant.parse("2026-09-05T00:00:00Z"));
        String actor = UUID.randomUUID().toString();

        service.enqueue(actor);
        assertThat(service.runOnce("worker-loop")).isTrue();

        assertThat(service.syncJobs(0, 10, null).items()).singleElement()
                .satisfies(job -> {
                    assertThat(job.state()).isEqualTo(McpRegistrySyncState.FAILED);
                    assertThat(job.safeErrorCode()).isEqualTo("MCP_REGISTRY_CURSOR_LOOP");
                });
        assertThat(service.candidates(0, 10, null, null).total()).isZero();
        assertThat(service.enqueue(actor).updatedSince()).isNull();
    }

    private static final class FixtureGateway implements McpRegistryGateway {
        private final List<String> cursors = new ArrayList<>();

        @Override
        public RegistryPage fetchPage(String baseUrl, Instant updatedSince, String cursor, int limit) {
            cursors.add(cursor);
            if (cursor == null) {
                return new RegistryPage(List.of(server(
                        "io.github.example/weather", "1.2.3",
                        McpRegistryCompatibility.SUPPORTED_REMOTE,
                        List.of(new McpRegistrySnapshot.RemoteTransport(
                                "https://mcp.example.com/rpc", "{}", "{}", true)))), "next");
            }
            return new RegistryPage(List.of(server(
                    "io.github.example/local-only", "2.0.0",
                    McpRegistryCompatibility.UNSUPPORTED_TRANSPORT, List.of())), null);
        }

        private static RegistryServer server(
                String name, String version, McpRegistryCompatibility compatibility,
                List<McpRegistrySnapshot.RemoteTransport> transports) {
            return new RegistryServer(
                    name, version, McpRegistryStatus.ACTIVE, null, null,
                    "Fixture server", "https://schema.example/server.json",
                    "https://github.com/example/server",
                    "{\"server\":{\"name\":\"" + name + "\",\"version\":\""
                            + version + "\",\"description\":\"Fixture server\"}}",
                    Instant.parse("2026-09-01T00:00:00Z"),
                    Instant.parse("2026-09-02T00:00:00Z"), transports, compatibility,
                    compatibility == McpRegistryCompatibility.SUPPORTED_REMOTE
                            ? null : "REMOTE_STREAMABLE_HTTPS_REQUIRED");
        }
    }
}

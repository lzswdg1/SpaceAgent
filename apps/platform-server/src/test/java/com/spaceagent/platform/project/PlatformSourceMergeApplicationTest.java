package com.spaceagent.platform.project;

import com.spaceagent.platform.project.api.SourceMergeApplicationApi;
import com.spaceagent.platform.project.application.ProjectAccessPolicy;
import com.spaceagent.platform.project.application.SourceMergeApplicationService;
import com.spaceagent.platform.project.domain.Project;
import com.spaceagent.platform.project.domain.SourceMergeGateway;
import com.spaceagent.platform.project.domain.SourceMergeState;
import com.spaceagent.platform.project.domain.SourceRepository;
import com.spaceagent.platform.project.domain.SourceRepositoryState;
import com.spaceagent.platform.project.domain.SourceRepositoryType;
import com.spaceagent.platform.project.domain.SourceRepositoryVisibility;
import com.spaceagent.platform.project.domain.Workspace;
import com.spaceagent.platform.project.domain.WorkspaceMode;
import com.spaceagent.platform.project.domain.WorkspaceState;
import com.spaceagent.platform.project.domain.WorkspaceSandboxGateway;
import com.spaceagent.platform.project.infrastructure.memory.InMemorySourceMergeRepository;
import com.spaceagent.platform.project.infrastructure.memory.InMemorySourceRepositoryRepository;
import com.spaceagent.platform.project.infrastructure.memory.InMemoryWorkspaceRepository;
import com.spaceagent.shared.exception.BusinessException;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PlatformSourceMergeApplicationTest {
    private static final String PROJECT = "00000000-0000-4000-8000-000000000002";
    private static final String TASK = "00000000-0000-4000-8000-000000000004";
    private static final String SOURCE = "00000000-0000-4000-8000-000000000001";
    private static final String WORKSPACE = "00000000-0000-4000-8000-000000000003";
    private static final String REVIEW = "00000000-0000-4000-8000-000000000006";
    private static final String ARTIFACT = "00000000-0000-4000-8000-000000000007";
    private static final String BASE = "a".repeat(40);
    private static final String PREPARED = "c".repeat(40);
    private static final String HASH = "sha256:" + "b".repeat(64);
    private static final String BUNDLE_HASH = "sha256:" + "1".repeat(64);
    private static final String BUNDLE = ".git/spaceagent-transfer/" + PREPARED + ".bundle";
    private static final String IDEMPOTENCY = "sha256:" + "d".repeat(64);
    private static final String INPUT = "sha256:" + "e".repeat(64);

    @Test void selectedFeatureBranchIsPinnedAsMergeTargetInsteadOfSourceDefault() {
        Fixture fixture=new Fixture("feature/selected");
        var prepared=fixture.service.prepare(fixture.command(INPUT));
        assertThat(prepared.targetRef()).isEqualTo("refs/heads/feature/selected");
        assertThat(fixture.service.prepare(fixture.command(INPUT)).targetRef()).isEqualTo(prepared.targetRef());
    }

    @Test
    void reviewedMergeIsIdempotentFencedAndRollbackable() {
        Fixture fixture = new Fixture();
        SourceMergeApplicationApi.PrepareCommand command = fixture.command(INPUT);

        var ready = fixture.service.prepare(command);
        assertThat(ready.state()).isEqualTo(SourceMergeState.READY);
        assertThat(ready.preparedCommit()).isEqualTo(PREPARED);
        assertThat(fixture.gateway.prepares).isEqualTo(1);
        assertThat(fixture.gateway.staged.bundleReference()).isEqualTo(BUNDLE);
        assertThat(fixture.gateway.staged.bundleSha256()).isEqualTo(BUNDLE_HASH);
        assertThat(fixture.gateway.staged.bundleSizeBytes()).isEqualTo(3);
        assertThat(fixture.gateway.staged.bundleBytes()).containsExactly(1,2,3);
        assertThat(fixture.service.prepare(command).id()).isEqualTo(ready.id());
        assertThat(fixture.gateway.prepares).isEqualTo(1);

        var applied = fixture.service.apply(new SourceMergeApplicationApi.ApplyCommand(
                "tenant", "user", PROJECT, ready.id(), "approval-1"));
        assertThat(applied.state()).isEqualTo(SourceMergeState.APPLIED_LOCAL);
        assertThat(applied.remoteUpdated()).isFalse();
        assertThat(fixture.service.apply(new SourceMergeApplicationApi.ApplyCommand(
                "tenant", "user", PROJECT, ready.id(), "approval-1")).state())
                .isEqualTo(SourceMergeState.APPLIED_LOCAL);
        assertThat(fixture.gateway.applies).isEqualTo(1);

        var rolledBack = fixture.service.rollback(new SourceMergeApplicationApi.RollbackCommand(
                "tenant", "user", PROJECT, ready.id(), "approval-2"));
        assertThat(rolledBack.state()).isEqualTo(SourceMergeState.ROLLED_BACK);
        assertThat(rolledBack.actualTargetCommit()).isEqualTo(BASE);
        assertThat(fixture.gateway.rollbacks).isEqualTo(1);
    }

    @Test
    void changedInputConflictsAndTargetDriftNeverForceUpdates() {
        Fixture fixture = new Fixture();
        var ready = fixture.service.prepare(fixture.command(INPUT));
        assertThatThrownBy(() -> fixture.service.prepare(
                fixture.command("sha256:" + "f".repeat(64))))
                .isInstanceOfSatisfying(BusinessException.class, error ->
                        assertThat(error.getCode()).isEqualTo("MERGE_IDEMPOTENCY_CONFLICT"));

        fixture.gateway.drift = true;
        var conflict = fixture.service.apply(new SourceMergeApplicationApi.ApplyCommand(
                "tenant", "user", PROJECT, ready.id(), null));
        assertThat(conflict.state()).isEqualTo(SourceMergeState.CONFLICT);
        assertThat(conflict.failureCode()).isEqualTo("MERGE_TARGET_DRIFT");
        assertThat(fixture.service.reconcile(new SourceMergeApplicationApi.ReconcileCommand(
                "tenant", "user", PROJECT, ready.id())).state())
                .isEqualTo(SourceMergeState.CONFLICT);
    }

    private static final class Fixture {
        private final Instant now = Instant.parse("2026-08-23T14:00:00Z");
        private final InMemoryWorkspaceRepository workspaces = new InMemoryWorkspaceRepository();
        private final InMemorySourceRepositoryRepository sources =
                new InMemorySourceRepositoryRepository();
        private final FakeMergeGateway gateway = new FakeMergeGateway();
        private final SourceMergeApplicationService service;

        private Fixture() { this("main"); }
        private Fixture(String baseRef) {
            Project project = Project.create(PROJECT, "tenant", "user", "project", null, now);
            ProjectAccessPolicy access = mock(ProjectAccessPolicy.class);
            when(access.requireProject("tenant", "user", PROJECT)).thenReturn(project);
            when(access.requireRole(any(), any(), any())).thenReturn(null);
            SourceRepository source = new SourceRepository(
                    SOURCE, PROJECT, "tenant", null, null, null, "42", "owner/repo",
                    "https://github.com/owner/repo.git", null, "main",
                    SourceRepositoryType.GITHUB, SourceRepositoryState.READY,
                    SourceRepositoryVisibility.PUBLIC, "user", now, now);
            sources.save(source);
            workspaces.save(new Workspace(
                    WORKSPACE, "tenant", PROJECT, TASK, SOURCE, null, "primary",
                    WorkspaceMode.MANAGED_GIT, "00000000-0000-4000-8000-000000000005",
                    baseRef, "spaceagent/reviewed", "managed:" + WORKSPACE, BASE, true,
                    WorkspaceState.READY, null, 1, "user", now, now));
            WorkspaceSandboxGateway sandbox = mock(WorkspaceSandboxGateway.class);
            when(sandbox.prepareCommit(any(), any(), any(), any(), any())).thenReturn(
                    new WorkspaceSandboxGateway.PreparedCommit(
                            PREPARED, BUNDLE, BUNDLE_HASH, 3, new byte[]{1,2,3}));
            service = new SourceMergeApplicationService(
                    new InMemorySourceMergeRepository(), workspaces, sources, access, gateway,
                    sandbox,
                    () -> "00000000-0000-4000-8000-000000000008", () -> now);
        }

        private SourceMergeApplicationApi.PrepareCommand command(String inputHash) {
            return new SourceMergeApplicationApi.PrepareCommand(
                    "tenant", "user", PROJECT, TASK, SOURCE, WORKSPACE, "run-1", REVIEW,
                    ARTIFACT, BASE, HASH, "implement", IDEMPOTENCY, inputHash);
        }
    }

    private static final class FakeMergeGateway implements SourceMergeGateway {
        private int prepares;
        private int applies;
        private int rollbacks;
        private boolean drift;
        private PreparedCommitTransfer staged;

        @Override
        public void stagePreparedCommit(
                SourceRepository source, String workspaceId, PreparedCommitTransfer transfer) {
            prepares++;
            staged = transfer;
        }

        @Override
        public RefUpdate apply(
                SourceRepository source, String targetRef,
                String expectedBaseCommit, String preparedCommit) {
            applies++;
            return drift
                    ? new RefUpdate(RefUpdateStatus.CONFLICT, "9".repeat(40))
                    : new RefUpdate(RefUpdateStatus.APPLIED, PREPARED);
        }

        @Override
        public RefUpdate rollback(
                SourceRepository source, String targetRef,
                String preparedCommit, String expectedBaseCommit) {
            rollbacks++;
            return new RefUpdate(RefUpdateStatus.APPLIED, BASE);
        }

        @Override
        public RefInspection inspect(SourceRepository source, String targetRef) {
            return new RefInspection(drift ? "9".repeat(40) : BASE);
        }
    }
}

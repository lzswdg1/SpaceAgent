package com.spaceagent.platform.project;

import com.spaceagent.platform.project.domain.SourceRepository;
import com.spaceagent.platform.project.domain.SourceRepositoryState;
import com.spaceagent.platform.project.domain.SourceRepositoryType;
import com.spaceagent.platform.project.domain.SourceRepositoryVisibility;
import com.spaceagent.platform.project.domain.Workspace;
import com.spaceagent.platform.project.domain.WorkspaceMode;
import com.spaceagent.platform.project.domain.WorkspaceState;
import com.spaceagent.platform.project.infrastructure.GitWorkspaceProvisioningGateway;
import com.spaceagent.platform.project.infrastructure.WorkspaceProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PlatformGitWorkspaceGatewayTest {
    @TempDir
    Path temp;

    @Test
    void provisionsUniqueManagedWorktreeUnderConfiguredRoot() throws Exception {
        Path origin = temp.resolve("origin");
        Files.createDirectories(origin);
        run("git", "init", "-b", "main", origin.toString());
        Files.writeString(origin.resolve("README.md"), "hello");
        run("git", "-C", origin.toString(), "-c", "user.name=Test",
                "-c", "user.email=test@example.com", "add", ".");
        run("git", "-C", origin.toString(), "-c", "user.name=Test",
                "-c", "user.email=test@example.com", "commit", "-m", "init");

        WorkspaceProperties properties = new WorkspaceProperties();
        properties.setManagedRoot(temp.resolve("managed").toString());
        var provisioning = new GitWorkspaceProvisioningGateway(properties);
        Instant now = Instant.now();
        var source = new SourceRepository(
                "00000000-0000-4000-8000-000000000001",
                "00000000-0000-4000-8000-000000000002",
                "tenant", null, null, null, "42", "r",
                origin.toUri().toString(), null, "main",
                SourceRepositoryType.GITHUB, SourceRepositoryState.READY,
                SourceRepositoryVisibility.PUBLIC, "u", now, now);
        var workspace = new Workspace(
                "00000000-0000-4000-8000-000000000003",
                "tenant", source.projectId(),
                "00000000-0000-4000-8000-000000000004",
                source.id(), null, "primary", WorkspaceMode.MANAGED_GIT,
                "00000000-0000-4000-8000-000000000005", "main",
                "spaceagent/test/one", null, null, true,
                WorkspaceState.PROVISIONING, null, 0, "u", now, now);

        Path worktrees = Files.createDirectories(temp.resolve("managed/workspaces"));
        var permissions = Files.getPosixFilePermissions(worktrees);
        try {
            Files.setPosixFilePermissions(worktrees, java.nio.file.attribute.PosixFilePermissions.fromString("r-xr-xr-x"));
            if (!Files.isWritable(worktrees)) {
                assertThatThrownBy(() -> provisioning.provision(workspace, source, null))
                        .isInstanceOfSatisfying(com.spaceagent.shared.exception.BusinessException.class,
                                error -> assertThat(error.getCode()).isEqualTo("WORKSPACE_DIRECTORY_NOT_WRITABLE"));
                assertThat(temp.resolve("managed/repositories").resolve(source.id() + ".git")).doesNotExist();
            }
        } finally {
            Files.setPosixFilePermissions(worktrees, permissions);
        }
        var provisioned = provisioning.provision(workspace, source, null);
        assertThat(provisioned.locator()).isEqualTo("managed:" + workspace.id())
                .doesNotContain(temp.toString());
        assertThat(provisioned.headCommit()).hasSize(40);
        Path target = temp.resolve("managed/workspaces").resolve(workspace.id());
        Path mirror = temp.resolve("managed/repositories").resolve(source.id() + ".git");
        assertThat(target.resolve("README.md")).exists();
        assertThat(target.resolve(".git")).isDirectory();
        assertThat(target.resolve(".git/objects/info/alternates")).doesNotExist();
        assertThat(mirror).exists();
        run("git", "--git-dir", mirror.toString(), "branch", "feature/user-code", "main");
        run("git", "--git-dir", mirror.toString(), "branch", "spaceagent/legacy/workspace", "main");
        run("git", "--git-dir", mirror.toString(), "branch", "spaceagent-intake", "main");
        assertThat(provisioning.branches(source.id())).containsExactly("feature/user-code", "main");
        // Listing must hide metadata, never delete the underlying execution refs.
        run("git", "--git-dir", mirror.toString(), "rev-parse", "--verify", "spaceagent/legacy/workspace");
        Workspace ready = workspace.ready(
                provisioned.locator(), provisioned.headCommit(), now);
        String intakeJobId = "00000000-0000-4000-8000-000000000006";
        var intake = provisioning.provision(intakeJobId, source, "main", null);
        assertThat(intake.workspaceRef()).isEqualTo("workspaces/" + intakeJobId);
        assertThat(intake.headCommit()).isEqualTo(provisioning
                .provision(intakeJobId, source, "main", null).headCommit());
        assertThat(temp.resolve("managed/workspaces").resolve(intakeJobId)
                .resolve("README.md")).exists();
        provisioning.cleanup(intakeJobId, source);
        assertThat(temp.resolve("managed/workspaces").resolve(intakeJobId)).doesNotExist();

        provisioning.cleanup(ready, source);
        assertThat(target).doesNotExist();
        provisioning.cleanupSource(source);
        provisioning.cleanupSource(source);
        assertThat(mirror).doesNotExist();
    }

    private static void run(String... command) throws Exception {
        Process process = new ProcessBuilder(List.of(command))
                .redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes());
        if (process.waitFor() != 0) throw new AssertionError(output);
    }
}

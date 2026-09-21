package com.spaceagent.platform.project;

import com.spaceagent.platform.project.domain.SourceMergeGateway;
import com.spaceagent.platform.project.domain.SourceMergeConflictException;
import com.spaceagent.platform.project.domain.SourceRepository;
import com.spaceagent.platform.project.domain.SourceRepositoryState;
import com.spaceagent.platform.project.domain.SourceRepositoryType;
import com.spaceagent.platform.project.domain.SourceRepositoryVisibility;
import com.spaceagent.platform.project.domain.Workspace;
import com.spaceagent.platform.project.domain.WorkspaceMode;
import com.spaceagent.platform.project.domain.WorkspaceState;
import com.spaceagent.platform.project.infrastructure.GitWorkspaceProvisioningGateway;
import com.spaceagent.platform.project.infrastructure.ManagedGitSourceMergeGateway;
import com.spaceagent.platform.project.infrastructure.WorkspaceProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.List;
import java.util.HexFormat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PlatformManagedGitSourceMergeGatewayTest {
    @TempDir Path temp;

    @Test
    void appliesReplaysRollsBackAndDetectsTargetDrift() throws Exception {
        Path origin = temp.resolve("origin");
        Files.createDirectories(origin);
        run("git", "init", "-b", "main", origin.toString());
        Files.writeString(origin.resolve("README.md"), "hello\n");
        run("git", "-C", origin.toString(), "-c", "user.name=Test", "-c",
                "user.email=test@example.com", "add", ".");
        run("git", "-C", origin.toString(), "-c", "user.name=Test", "-c",
                "user.email=test@example.com", "commit", "-m", "init");

        WorkspaceProperties properties = new WorkspaceProperties();
        properties.setManagedRoot(temp.resolve("managed").toString());
        Instant now = Instant.parse("2026-08-23T14:00:00Z");
        SourceRepository source = source(origin, now);
        Workspace provisioning = workspace(source, now);
        var provisioner = new GitWorkspaceProvisioningGateway(properties);
        var provisioned = provisioner.provision(provisioning, source, null);
        Workspace ready = provisioning.ready(
                provisioned.locator(), provisioned.headCommit(), now);
        Path worktree = temp.resolve("managed/workspaces").resolve(ready.id());
        Files.createDirectories(worktree.resolve("src"));
        Files.writeString(worktree.resolve("src/new.txt"), "done\n");
        run("git", "-C", worktree.toString(), "-c", "user.name=Test", "-c",
                "user.email=test@example.com", "add", "-A");
        run("git", "-C", worktree.toString(), "-c", "user.name=Test", "-c",
                "user.email=test@example.com", "commit", "-m", "prepared");
        String prepared = output("git", "-C", worktree.toString(), "rev-parse", "HEAD").trim();
        SourceMergeGateway.PreparedCommitTransfer preparedBundle = transfer(
                worktree, prepared, ready.headCommit());
        Path hostMarker = temp.resolve("host-upload-pack-marker");
        run("git", "-C", worktree.toString(), "config", "uploadpack.packObjectsHook",
                "touch " + hostMarker + "; git pack-objects --revs --stdout");
        var gateway = new ManagedGitSourceMergeGateway(properties);
        gateway.stagePreparedCommit(source, ready.id(), preparedBundle);
        assertThat(hostMarker).doesNotExist();
        assertThatThrownBy(() -> gateway.stagePreparedCommit(source, ready.id(),
                new SourceMergeGateway.PreparedCommitTransfer(
                        prepared, preparedBundle.bundleReference(),
                        "sha256:" + "0".repeat(64), preparedBundle.bundleSizeBytes(),
                        preparedBundle.bundleBytes())))
                .isInstanceOfSatisfying(SourceMergeConflictException.class,
                        error -> assertThat(error.code()).isEqualTo("MERGE_BUNDLE_HASH_MISMATCH"));
        assertThat(gateway.inspect(source, "refs/heads/main").actualTargetCommit())
                .isEqualTo(ready.headCommit());

        assertThat(gateway.apply(source, "refs/heads/main", ready.headCommit(),
                prepared).status()).isEqualTo(SourceMergeGateway.RefUpdateStatus.APPLIED);
        assertThat(gateway.apply(source, "refs/heads/main", ready.headCommit(),
                prepared).status())
                .isEqualTo(SourceMergeGateway.RefUpdateStatus.ALREADY_APPLIED);
        // Simulate a pre-fix mirror and prove a later preparation cannot reset the reviewed tip.
        Path retainedMirror=temp.resolve("managed/repositories").resolve(source.id()+".git");
        run("git","--git-dir",retainedMirror.toString(),"config","--replace-all","remote.origin.fetch","+refs/*:refs/*");
        run("git","--git-dir",retainedMirror.toString(),"config","remote.origin.mirror","true");
        String nextId="00000000-0000-4000-8000-000000000009";
        var next=provisioner.provision(nextId,source,"main",null);
        assertThat(next.headCommit()).isEqualTo(prepared);
        assertThat(temp.resolve("managed/workspaces").resolve(nextId).resolve("src/new.txt")).hasContent("done\n");
        assertThat(gateway.inspect(source,"refs/heads/main").actualTargetCommit()).isEqualTo(prepared);
        assertThat(output("git","--git-dir",retainedMirror.toString(),"rev-parse","refs/remotes/origin/main").trim()).isEqualTo(ready.headCommit());
        assertThat(gateway.rollback(source, "refs/heads/main", prepared,
                ready.headCommit()).status()).isEqualTo(SourceMergeGateway.RefUpdateStatus.APPLIED);

        run("git", "-C", worktree.toString(), "-c", "user.name=Test", "-c",
                "user.email=test@example.com", "commit", "--allow-empty", "-m", "drift");
        String drift = output("git", "-C", worktree.toString(), "rev-parse", "HEAD").trim();
        gateway.stagePreparedCommit(source, ready.id(), transfer(worktree, drift, prepared));
        assertThat(hostMarker).doesNotExist();
        Path mirror = temp.resolve("managed/repositories").resolve(source.id() + ".git");
        run("git", "--git-dir", mirror.toString(), "update-ref", "refs/heads/main",
                drift, ready.headCommit());
        assertThat(gateway.apply(source, "refs/heads/main", ready.headCommit(),
                prepared).status()).isEqualTo(SourceMergeGateway.RefUpdateStatus.CONFLICT);
    }

    @Test void fastForwardsUntouchedLocalHeadsAndDiscoversNewRemoteBranches() throws Exception {
        Path origin=temp.resolve("origin"); Files.createDirectories(origin);
        run("git","init","-b","main",origin.toString());
        run("git","-C",origin.toString(),"-c","user.name=Test","-c","user.email=test@example.com","commit","--allow-empty","-m","base");
        var properties=new WorkspaceProperties();properties.setManagedRoot(temp.resolve("managed").toString());
        var provisioner=new GitWorkspaceProvisioningGateway(properties);
        var source=source(origin,Instant.now());
        provisioner.provision("00000000-0000-4000-8000-000000000010",source,"main",null);
        run("git","-C",origin.toString(),"-c","user.name=Test","-c","user.email=test@example.com","commit","--allow-empty","-m","upstream");
        run("git","-C",origin.toString(),"branch","feature/new");
        String head=output("git","-C",origin.toString(),"rev-parse","HEAD").trim();
        assertThat(provisioner.provision("00000000-0000-4000-8000-000000000011",source,"main",null).headCommit()).isEqualTo(head);
        assertThat(provisioner.branches(source.id())).contains("main","feature/new");
    }

    private SourceRepository source(Path origin, Instant now) {
        return new SourceRepository(
                "00000000-0000-4000-8000-000000000001",
                "00000000-0000-4000-8000-000000000002", "tenant", null, null,
                null, "42", "owner/repo", origin.toUri().toString(), null, "main",
                SourceRepositoryType.GITHUB, SourceRepositoryState.READY,
                SourceRepositoryVisibility.PUBLIC, "user", now, now);
    }

    private Workspace workspace(SourceRepository source, Instant now) {
        return new Workspace(
                "00000000-0000-4000-8000-000000000003", "tenant", source.projectId(),
                "00000000-0000-4000-8000-000000000004", source.id(), null, "primary",
                WorkspaceMode.MANAGED_GIT, "00000000-0000-4000-8000-000000000005",
                "main", "spaceagent/reviewed", null, null, true,
                WorkspaceState.PROVISIONING, null, 0, "user", now, now);
    }

    private static SourceMergeGateway.PreparedCommitTransfer transfer(
            Path workspace, String commit, String base) throws Exception {
        Path directory=Files.createDirectories(workspace.resolve(".git/spaceagent-transfer"));
        Path bundle=directory.resolve(commit+".bundle");
        Files.deleteIfExists(bundle);
        run("git","-C",workspace.toString(),"bundle","create",bundle.toString(),"HEAD","^"+base);
        byte[] bytes=Files.readAllBytes(bundle);
        MessageDigest digest=MessageDigest.getInstance("SHA-256");
        digest.update(bytes);
        return new SourceMergeGateway.PreparedCommitTransfer(
                commit,".git/spaceagent-transfer/"+commit+".bundle",
                "sha256:"+HexFormat.of().formatHex(digest.digest()),bytes.length,bytes);
    }

    private static void run(String... command) throws Exception {
        Process process = new ProcessBuilder(List.of(command)).redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        if (process.waitFor() != 0) throw new AssertionError(output);
    }

    private static String output(String... command) throws Exception {
        Process process = new ProcessBuilder(List.of(command)).redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        if (process.waitFor() != 0) throw new AssertionError(output);
        return output;
    }
}

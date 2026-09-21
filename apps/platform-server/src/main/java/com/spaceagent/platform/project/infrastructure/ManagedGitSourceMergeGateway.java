package com.spaceagent.platform.project.infrastructure;

import com.spaceagent.platform.project.domain.SourceMergeConflictException;
import com.spaceagent.platform.project.domain.SourceMergeGateway;
import com.spaceagent.platform.project.domain.SourceRepository;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** Git commit and local-ref CAS adapter. It never contacts or updates a remote repository. */
@Component
public class ManagedGitSourceMergeGateway implements SourceMergeGateway {
    private static final int MAX_GIT_OUTPUT_BYTES = 2_000_000;
    private static final long MAX_BUNDLE_BYTES =
            com.spaceagent.platform.project.domain.WorkspaceSandboxGateway
                    .MAX_PREPARED_BUNDLE_BYTES;
    private final Path root;
    private final Path stagingRoot;
    private final int timeoutSeconds;

    public ManagedGitSourceMergeGateway(WorkspaceProperties properties) {
        this.root = Path.of(properties.getManagedRoot()).toAbsolutePath().normalize();
        this.stagingRoot = root.resolve("source-merge-staging").normalize();
        this.timeoutSeconds = properties.getGitTimeoutSeconds();
    }

    @Override
    public RefUpdate apply(
            SourceRepository source,
            String targetRef,
            String expectedBaseCommit,
            String preparedCommit) {
        return update(source, targetRef, expectedBaseCommit, preparedCommit);
    }

    @Override
    public void stagePreparedCommit(
            SourceRepository source,
            String workspaceId,
            PreparedCommitTransfer transfer) {
        requireUuid(workspaceId);
        if (transfer == null) {
            throw conflict("MERGE_BUNDLE_EVIDENCE_INVALID", "Prepared Git bundle evidence is missing");
        }
        String commit = transfer.commit();
        if (!(".git/spaceagent-transfer/" + commit + ".bundle")
                .equals(transfer.bundleReference())) {
            throw conflict("MERGE_BUNDLE_REFERENCE_INVALID", "Prepared Git bundle reference is invalid");
        }

        Path staged = null;
        try {
            staged = stageVerifiedBundle(workspaceId, transfer);
            Path mirror = mirror(source);
            String heads = requireSuccess(root, List.of(
                    "git", "--git-dir", mirror.toString(), "bundle", "list-heads",
                    staged.toString(), "HEAD"), "MERGE_BUNDLE_HEAD_INVALID").out().trim();
            if (!heads.equals(commit + " HEAD")) {
                throw conflict("MERGE_BUNDLE_HEAD_INVALID", "Prepared Git bundle HEAD is invalid");
            }
            requireSuccess(root, List.of(
                    "git", "--git-dir", mirror.toString(), "bundle", "verify",
                    staged.toString()), "MERGE_BUNDLE_VERIFY_FAILED");
            // Import only the verified backend-owned bundle. The mutable Workspace repository is
            // never supplied to a host Git process as a repository or remote.
            requireSuccess(root, List.of(
                    "git", "--git-dir", mirror.toString(), "fetch", "--no-tags",
                    "--no-write-fetch-head", "--no-recurse-submodules", "--",
                    staged.toString(), "HEAD"), "MERGE_OBJECT_TRANSFER_FAILED");
            String imported = requireSuccess(root, List.of(
                    "git", "--git-dir", mirror.toString(), "rev-parse", "--verify",
                    commit + "^{commit}"), "MERGE_OBJECT_TRANSFER_FAILED").out().trim();
            if (!commit.equals(imported)) {
                throw conflict("MERGE_OBJECT_TRANSFER_FAILED", "Prepared Git commit was not imported");
            }
        } catch (SourceMergeConflictException error) {
            throw error;
        } catch (IOException error) {
            throw conflict("MERGE_BUNDLE_COPY_FAILED", "Prepared Git bundle could not be staged");
        } finally {
            if (staged != null) {
                try {
                    Files.deleteIfExists(staged);
                } catch (IOException ignored) {
                    // The bounded backend-owned staging file contains only reviewed Git objects.
                }
            }
        }
    }

    private Path stageVerifiedBundle(
            String workspaceId,
            PreparedCommitTransfer transfer) throws IOException {
        byte[] bundle = transfer.bundleBytes();
        if (bundle.length != transfer.bundleSizeBytes()
                || bundle.length < 1 || bundle.length > MAX_BUNDLE_BYTES) {
            throw conflict("MERGE_BUNDLE_SIZE_MISMATCH", "Prepared Git bundle size is invalid");
        }
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (Exception error) {
            throw new IllegalStateException("SHA-256 is unavailable", error);
        }
        String actualHash = "sha256:" + HexFormat.of().formatHex(digest.digest(bundle));
        if (!actualHash.equals(transfer.bundleSha256())) {
            throw conflict("MERGE_BUNDLE_HASH_MISMATCH", "Prepared Git bundle evidence changed");
        }

        if (Files.exists(stagingRoot, LinkOption.NOFOLLOW_LINKS)
                && Files.isSymbolicLink(stagingRoot)) {
            throw conflict("MERGE_BUNDLE_STAGING_INVALID", "Git bundle staging path is unsafe");
        }
        Files.createDirectories(stagingRoot);
        if (!Files.isDirectory(stagingRoot, LinkOption.NOFOLLOW_LINKS)
                || Files.isSymbolicLink(stagingRoot)
                || !stagingRoot.toRealPath().startsWith(root.toRealPath())) {
            throw conflict("MERGE_BUNDLE_STAGING_INVALID", "Git bundle staging path is unsafe");
        }
        Path staged = stagingRoot.resolve(
                workspaceId + "-" + transfer.commit() + "-"
                        + UUID.randomUUID() + ".bundle").normalize();
        requireUnder(staged, stagingRoot);
        try (FileChannel output = FileChannel.open(
                staged, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
            ByteBuffer value = ByteBuffer.wrap(bundle);
            while (value.hasRemaining()) output.write(value);
            output.force(true);
        } catch (RuntimeException | IOException error) {
            Files.deleteIfExists(staged);
            throw error;
        }
        BasicFileAttributes stagedAttributes = Files.readAttributes(
                staged, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (!stagedAttributes.isRegularFile() || stagedAttributes.size() != bundle.length) {
            Files.deleteIfExists(staged);
            throw conflict("MERGE_BUNDLE_STAGING_INVALID", "Staged Git bundle is invalid");
        }
        return staged;
    }

    @Override
    public RefUpdate rollback(
            SourceRepository source,
            String targetRef,
            String preparedCommit,
            String expectedBaseCommit) {
        return update(source, targetRef, preparedCommit, expectedBaseCommit);
    }

    @Override
    public RefInspection inspect(SourceRepository source, String targetRef) {
        Path mirror = mirror(source);
        String actual = requireSuccess(root, List.of(
                "git", "--git-dir", mirror.toString(), "rev-parse", "--verify", targetRef),
                "MERGE_TARGET_REF_UNAVAILABLE").out().trim();
        return new RefInspection(actual);
    }

    private RefUpdate update(
            SourceRepository source,
            String targetRef,
            String expectedCurrent,
            String desired) {
        String actual = inspect(source, targetRef).actualTargetCommit();
        if (actual.equals(desired)) {
            return new RefUpdate(RefUpdateStatus.ALREADY_APPLIED, actual);
        }
        if (!actual.equals(expectedCurrent)) {
            return new RefUpdate(RefUpdateStatus.CONFLICT, actual);
        }
        Path mirror = mirror(source);
        Exec update = exec(root, List.of(
                "git", "--git-dir", mirror.toString(), "update-ref",
                targetRef, desired, expectedCurrent));
        if (update.code() != 0) {
            String after = inspect(source, targetRef).actualTargetCommit();
            return after.equals(desired)
                    ? new RefUpdate(RefUpdateStatus.ALREADY_APPLIED, after)
                    : new RefUpdate(RefUpdateStatus.CONFLICT, after);
        }
        return new RefUpdate(RefUpdateStatus.APPLIED, desired);
    }

    private Path mirror(SourceRepository source) {
        Path directory = root.resolve("repositories").resolve(source.id() + ".git").normalize();
        requireUnder(directory, root.resolve("repositories"));
        if (!Files.isDirectory(directory)) {
            throw conflict("MERGE_MIRROR_UNAVAILABLE", "Managed source mirror is unavailable");
        }
        return directory;
    }

    private static void requireUnder(Path value, Path parent) {
        if (!value.startsWith(parent.toAbsolutePath().normalize())) {
            throw conflict("MERGE_PATH_INVALID", "Managed Git path escaped its root");
        }
    }

    private static void requireUuid(String value) {
        try {
            UUID.fromString(value);
        } catch (RuntimeException error) {
            throw conflict("MERGE_WORKSPACE_INVALID", "Managed Workspace identifier is invalid");
        }
    }

    private Exec requireSuccess(Path cwd, List<String> command, String code) {
        Exec result = exec(cwd, command);
        if (result.code() != 0) {
            throw conflict(code, "Managed Git operation failed");
        }
        return result;
    }

    private Exec exec(Path cwd, List<String> command) {
        try {
            ProcessBuilder builder = new ProcessBuilder(new ArrayList<>(command));
            builder.directory(cwd.toFile());
            builder.redirectErrorStream(false);
            Map<String, String> environment = builder.environment();
            environment.clear();
            environment.put("PATH", System.getenv().getOrDefault("PATH", "/usr/bin:/bin"));
            environment.put("HOME", root.resolve(".spaceagent-home").toString());
            environment.put("GIT_TERMINAL_PROMPT", "0");
            environment.put("GIT_CONFIG_NOSYSTEM", "1");
            environment.put("GIT_CONFIG_GLOBAL", "/dev/null");
            environment.put("GIT_CONFIG_COUNT", "1");
            environment.put("GIT_CONFIG_KEY_0", "core.hooksPath");
            environment.put("GIT_CONFIG_VALUE_0", root.resolve("disabled-hooks").toString());
            Process process = builder.start();
            ByteArrayOutputStream stdout = new ByteArrayOutputStream();
            ByteArrayOutputStream stderr = new ByteArrayOutputStream();
            AtomicBoolean overflow = new AtomicBoolean();
            Thread out = Thread.startVirtualThread(() -> copy(
                    process.getInputStream(), stdout, process, overflow));
            Thread err = Thread.startVirtualThread(() -> copy(
                    process.getErrorStream(), stderr, process, overflow));
            if (!process.waitFor(Math.min(600, Math.max(30, timeoutSeconds)), TimeUnit.SECONDS)) {
                process.destroyForcibly();
                throw new IllegalStateException("Managed Git operation timed out");
            }
            out.join();
            err.join();
            if (overflow.get()) throw new IllegalStateException("Managed Git output exceeds limit");
            return new Exec(process.exitValue(), stdout.toString(StandardCharsets.UTF_8),
                    stderr.toString(StandardCharsets.UTF_8));
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Managed Git operation interrupted", interrupted);
        } catch (IOException error) {
            throw new IllegalStateException("Managed Git operation unavailable", error);
        }
    }

    private static void copy(
            InputStream input,
            ByteArrayOutputStream output,
            Process process,
            AtomicBoolean overflow) {
        try (input) {
            byte[] buffer = new byte[8_192];
            int read;
            while ((read = input.read(buffer)) >= 0) {
                if (output.size() + read > MAX_GIT_OUTPUT_BYTES) {
                    overflow.set(true);
                    process.destroyForcibly();
                    return;
                }
                output.write(buffer, 0, read);
            }
        } catch (IOException ignored) {
            overflow.set(true);
            process.destroyForcibly();
        }
    }

    private static SourceMergeConflictException conflict(String code, String message) {
        return new SourceMergeConflictException(code, message);
    }

    private record Exec(int code, String out, String err) {}
}

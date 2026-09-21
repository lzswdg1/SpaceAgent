package com.spaceagent.platform.project.infrastructure;

import com.spaceagent.platform.project.domain.SourceRepository;
import com.spaceagent.platform.project.domain.ProjectIntakeWorkspaceGateway;
import com.spaceagent.platform.project.domain.Workspace;
import com.spaceagent.platform.project.domain.WorkspaceProvisioningGateway;
import com.spaceagent.shared.exception.BusinessException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

@Component
public class GitWorkspaceProvisioningGateway
        implements WorkspaceProvisioningGateway, ProjectIntakeWorkspaceGateway {
    private static final int MAX_GIT_OUTPUT_BYTES = 200_000;
    private final Path root;
    private final Duration timeout;

    public GitWorkspaceProvisioningGateway(WorkspaceProperties properties) {
        root = Path.of(properties.getManagedRoot()).toAbsolutePath().normalize();
        timeout = Duration.ofSeconds(properties.getGitTimeoutSeconds());
    }

    @Override
    public ProvisionedWorkspace provision(
            Workspace workspace,
            SourceRepository source,
            String authorizationHeader) {
        if (source.remoteUrl() == null && source.type() == com.spaceagent.platform.project.domain.SourceRepositoryType.GENERIC) {
            initializeEmptySource(source);
        } else if (source.remoteUrl() == null) {
            throw new IllegalArgumentException("Managed Workspace requires remote URL");
        }
        Path mirrors = under("repositories").resolve(source.id() + ".git");
        Path worktrees = under("workspaces");
        Path target = worktrees.resolve(workspace.id()).normalize();
        requireUnder(target, worktrees);
        try {
            Files.createDirectories(mirrors.getParent());
            Files.createDirectories(worktrees);
            requireWritableDirectory(mirrors.getParent());
            requireWritableDirectory(worktrees);
            Map<String, String> environment = gitEnvironment(authorizationHeader);
            if (!Files.exists(mirrors)) {
                run(environment, List.of("git", "clone", "--mirror",
                        source.remoteUrl(), mirrors.toString()), root);
            }
            if (source.remoteUrl() != null) synchronizeRemoteHeads(mirrors, environment);
            String base = workspace.baseRef().startsWith("refs/")
                    ? workspace.baseRef() : "refs/heads/" + workspace.baseRef();
            materialize(mirrors, target, base, workspace.branchName());
            String head = run(environment,
                    List.of("git", "-C", target.toString(), "rev-parse", "HEAD"), root).trim();
            return new ProvisionedWorkspace("managed:" + workspace.id(), head);
        } catch (IOException error) {
            throw new IllegalStateException("Git Workspace provisioning failed", error);
        }
    }

    @Override
    public void initializeEmptySource(SourceRepository source) {
        requireUuid(source.id());
        if (source.type()!=com.spaceagent.platform.project.domain.SourceRepositoryType.GENERIC || source.remoteUrl()!=null)
            throw new IllegalArgumentException("Only managed empty sources can be initialized");
        Path mirror=under("repositories").resolve(source.id()+".git");
        try {
            Files.createDirectories(mirror.getParent());
            requireWritableDirectory(mirror.getParent());
            Map<String,String> environment=gitEnvironment(null);
            if (Files.isDirectory(mirror)) {
                try { run(environment,List.of("git","--git-dir",mirror.toString(),"rev-parse","--verify","refs/heads/main"),root); return; }
                catch (IllegalStateException ignored) { /* An interrupted initialization can finish using the same ID. */ }
            }
            run(environment,List.of("git","init","--bare","--initial-branch=main",mirror.toString()),root);
            String tree=run(environment,List.of("git","--git-dir",mirror.toString(),"hash-object","-w","-t","tree","/dev/null"),root).trim();
            String commit=run(environment,List.of("git","-c","user.name=SpaceAgent","-c","user.email=spaceagent@localhost",
                    "--git-dir",mirror.toString(),"commit-tree",tree,"-m","Initialize managed root"),root).trim();
            run(environment,List.of("git","--git-dir",mirror.toString(),"update-ref","refs/heads/main",commit,""),root);
        } catch (IOException error) {
            throw new BusinessException("Managed root storage initialization failed",HttpStatus.SERVICE_UNAVAILABLE,"PROJECT_ROOT_STORAGE_FAILED");
        }
    }

    @Override
    public IntakeWorkspace provision(
            String intakeJobId,
            SourceRepository source,
            String baseRef,
            String authorizationHeader) {
        requireUuid(intakeJobId);
        if (source.remoteUrl() == null) {
            throw new IllegalArgumentException("Project intake requires a managed remote source");
        }
        Path mirrors = under("repositories").resolve(source.id() + ".git");
        Path worktrees = under("workspaces");
        Path target = worktrees.resolve(intakeJobId).normalize();
        requireUnder(target, worktrees);
        try {
            if (Files.exists(target)) {
                String head = run(gitEnvironment(null),
                        List.of("git", "-C", target.toString(), "rev-parse", "HEAD"), root).trim();
                return new IntakeWorkspace("workspaces/" + intakeJobId, head);
            }
            Files.createDirectories(mirrors.getParent());
            Files.createDirectories(worktrees);
            requireWritableDirectory(mirrors.getParent());
            requireWritableDirectory(worktrees);
            Map<String, String> environment = gitEnvironment(authorizationHeader);
            if (!Files.exists(mirrors)) {
                run(environment, List.of("git", "clone", "--mirror",
                        source.remoteUrl(), mirrors.toString()), root);
            }
            synchronizeRemoteHeads(mirrors, environment);
            String selectedBase = baseRef == null || baseRef.isBlank()
                    ? source.defaultBranch() : baseRef.trim();
            String base = selectedBase.startsWith("refs/")
                    ? selectedBase : "refs/heads/" + selectedBase;
            materialize(mirrors, target, base, "spaceagent-intake");
            String head = run(environment,
                    List.of("git", "-C", target.toString(), "rev-parse", "HEAD"), root).trim();
            return new IntakeWorkspace("workspaces/" + intakeJobId, head);
        } catch (IOException error) {
            throw new IllegalStateException("Project intake Workspace provisioning failed", error);
        }
    }

    @Override
    public List<String> branches(String sourceId) {
        requireUuid(sourceId);
        Path mirror = under("repositories").resolve(sourceId + ".git");
        if (!Files.isDirectory(mirror)) return List.of();
        try {
            return run(gitEnvironment(null), List.of("git", "--git-dir", mirror.toString(),
                    "for-each-ref", "--count=500", "--format=%(refname:short)", "refs/heads"), root)
                    .lines().filter(com.spaceagent.platform.project.domain.RepositoryBranchVisibility::visible).toList();
        } catch (IOException error) {
            throw new BusinessException("Repository branches could not be read", HttpStatus.SERVICE_UNAVAILABLE,
                    "WORKSPACE_BRANCH_LIST_UNAVAILABLE");
        }
    }

    @Override
    public void cleanup(Workspace workspace, SourceRepository source) {
        if (source.remoteUrl() == null && source.type() != com.spaceagent.platform.project.domain.SourceRepositoryType.GENERIC) return;
        Path mirror = under("repositories").resolve(source.id() + ".git");
        Path target = under("workspaces").resolve(workspace.id()).normalize();
        requireUnder(target, under("workspaces"));
        if (!Files.exists(target)) return;
        if (Files.exists(mirror)) {
            try {
                run(gitEnvironment(null), List.of("git", "--git-dir", mirror.toString(),
                        "worktree", "remove", "--force", target.toString()), root);
                return;
            } catch (IOException | RuntimeException ignored) {
                // Fall through to bounded filesystem cleanup.
            }
        }
        deleteTree(target);
    }

    @Override
    public void cleanup(String intakeJobId, SourceRepository source) {
        requireUuid(intakeJobId);
        Path mirror = under("repositories").resolve(source.id() + ".git");
        Path target = under("workspaces").resolve(intakeJobId).normalize();
        requireUnder(target, under("workspaces"));
        if (!Files.exists(target)) return;
        if (Files.exists(mirror)) {
            try {
                run(gitEnvironment(null), List.of("git", "--git-dir", mirror.toString(),
                        "worktree", "remove", "--force", target.toString()), root);
                return;
            } catch (IOException | RuntimeException ignored) {
                // Fall through to bounded filesystem cleanup.
            }
        }
        deleteTree(target);
    }

    @Override
    public void cleanupSource(SourceRepository source) {
        if (source.remoteUrl() == null && source.type() != com.spaceagent.platform.project.domain.SourceRepositoryType.GENERIC) return;
        Path mirror = under("repositories").resolve(source.id() + ".git").normalize();
        requireUnder(mirror, under("repositories"));
        deleteTree(mirror);
    }

    private static void deleteTree(Path root) {
        if (!Files.exists(root)) return;
        try {
            Files.walkFileTree(root, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attributes)
                        throws IOException {
                    Files.deleteIfExists(file);
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult postVisitDirectory(Path directory, IOException error)
                        throws IOException {
                    if (error != null) throw error;
                    Files.deleteIfExists(directory);
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException error) {
            throw new IllegalStateException("Managed Git cleanup failed", error);
        }
    }

    private void synchronizeRemoteHeads(Path mirror, Map<String, String> environment) throws IOException {
        // Upgrade old --mirror clones without modifying any local head or losing reviewed commits.
        run(environment, List.of("git", "--git-dir", mirror.toString(), "config", "--replace-all",
                "remote.origin.fetch", "+refs/heads/*:refs/remotes/origin/*"), root);
        run(environment, List.of("git", "--git-dir", mirror.toString(), "config", "remote.origin.mirror", "false"), root);
        run(environment, List.of("git", "--git-dir", mirror.toString(), "fetch", "--no-tags", "--prune",
                "origin", "+refs/heads/*:refs/remotes/origin/*"), root);
        String heads = run(environment, List.of("git", "--git-dir", mirror.toString(), "for-each-ref",
                "--format=%(refname:strip=3) %(objectname)", "refs/remotes/origin"), root);
        for (String line : heads.lines().toList()) {
            String[] fields = line.split(" ", 2);
            if (fields.length != 2 || fields[0].equals("HEAD")
                    || !com.spaceagent.platform.project.domain.RepositoryBranchVisibility.visible(fields[0])) continue;
            String target = "refs/heads/" + fields[0], remote = fields[1];
            String local;
            try {
                local = run(environment, List.of("git", "--git-dir", mirror.toString(),
                        "rev-parse", "--verify", target), root).trim();
            } catch (IllegalStateException missing) {
                run(environment, List.of("git", "--git-dir", mirror.toString(), "update-ref", target, remote, ""), root);
                continue;
            }
            if (local.equals(remote)) continue;
            try {
                run(environment, List.of("git", "--git-dir", mirror.toString(), "merge-base", "--is-ancestor", local, remote), root);
            } catch (IllegalStateException diverged) {
                continue; // Never overwrite a local branch that upstream does not contain.
            }
            run(environment, List.of("git", "--git-dir", mirror.toString(), "update-ref", target, remote, local), root);
        }
    }

    private void materialize(Path mirror, Path target, String base, String branch) throws IOException {
        // A Sandbox mounts only this directory. A worktree .git file would point outside
        // that mount; a no-hardlinks local clone keeps metadata and objects self-contained.
        Map<String,String> local = gitEnvironment(null);
        String commit = run(local, List.of("git", "--git-dir", mirror.toString(), "rev-parse", "--verify", base + "^{commit}"), root).trim();
        run(local, List.of("git", "clone", "--no-hardlinks", "--no-checkout", "--", mirror.toString(), target.toString()), root);
        // Execution identity belongs to Workspace metadata, not a repository branch.
        // Detached HEAD avoids exposing reserved execution refs via ordinary git status.
        run(local, List.of("git", "-C", target.toString(), "checkout", "--detach", commit), root);
        run(local, List.of("git", "-C", target.toString(), "remote", "remove", "origin"), root);
    }

    private static void requireWritableDirectory(Path directory) {
        if (!Files.isDirectory(directory) || !Files.isWritable(directory)) {
            throw new BusinessException("The backend user cannot write to the managed Workspace directory; repair the volume ownership",
                    HttpStatus.SERVICE_UNAVAILABLE, "WORKSPACE_DIRECTORY_NOT_WRITABLE");
        }
    }

    private Path under(String child) {
        Path path = root.resolve(child).normalize();
        requireUnder(path, root);
        return path;
    }

    private static void requireUnder(Path path, Path base) {
        if (!path.startsWith(base.toAbsolutePath().normalize())) {
            throw new IllegalArgumentException("Workspace path escaped managed root");
        }
    }

    private static void requireUuid(String value) {
        try {
            java.util.UUID.fromString(value);
        } catch (RuntimeException error) {
            throw new IllegalArgumentException("Project intake identifier must be a UUID");
        }
    }

    private Map<String, String> gitEnvironment(String authorizationHeader) {
        Map<String, String> environment = new HashMap<>();
        environment.put("PATH", System.getenv().getOrDefault("PATH", "/usr/bin:/bin"));
        environment.put("HOME", root.resolve("home").toString());
        environment.put("GIT_TERMINAL_PROMPT", "0");
        if (authorizationHeader != null) {
            if (!safeAuthorizationHeader(authorizationHeader)) {
                throw new IllegalArgumentException("Invalid Git authorization header");
            }
            environment.put("GIT_CONFIG_COUNT", "1");
            environment.put("GIT_CONFIG_KEY_0", "http.extraHeader");
            environment.put("GIT_CONFIG_VALUE_0", "Authorization: " + authorizationHeader);
        }
        return environment;
    }

    private static boolean safeAuthorizationHeader(String value) {
        if (value.length() > 4_103 || value.contains("\r") || value.contains("\n")
                || !(value.startsWith("Bearer ") || value.startsWith("Basic "))) return false;
        String secret = value.substring(value.indexOf(' ') + 1);
        return secret.matches("[\\x21-\\x7E]{1,4096}");
    }

    private String run(Map<String, String> environment, List<String> command, Path cwd)
            throws IOException {
        ProcessBuilder builder = new ProcessBuilder(command);
        builder.directory(cwd.toFile());
        builder.redirectErrorStream(true);
        builder.environment().clear();
        builder.environment().putAll(environment);
        Process process = builder.start();
        ByteArrayOutputStream output = new ByteArrayOutputStream(16_384);
        AtomicBoolean overflow = new AtomicBoolean();
        Thread reader = Thread.startVirtualThread(
                () -> copyBounded(process.getInputStream(), output, process, overflow));
        try {
            if (!process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
                process.destroyForcibly();
                process.waitFor(5, TimeUnit.SECONDS);
                throw new IllegalStateException("Git command timed out");
            }
            reader.join(5_000);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
            throw new IllegalStateException("Git command interrupted", error);
        }
        if (overflow.get()) throw new IllegalStateException("Git command output exceeds limit");
        String text = output.toString(StandardCharsets.UTF_8);
        if (process.exitValue() != 0) throw new IllegalStateException("Git command failed: " + text);
        return text;
    }

    private static void copyBounded(
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
}

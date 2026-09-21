# Sandbox isolation boundaries

Date: 2026-08-25

Milestone: M34-PR1

## Ownership

Java Runtime owns the Coding action, Governance authorization, ToolExecutionLedger claim,
Checkpoint, UNKNOWN decision and Artifact. Project owns Workspace authorization, files and
Git evidence. `workers/sandbox-worker` owns one ephemeral compute attempt only and imports no
business persistence client.

The Coding call chain is:

```text
Java Coding Runtime
 -> exact Governance authorization
 -> durable ToolExecutionLedger claim
 -> internal Tooling SandboxComputeApplicationApi (no second ledger)
 -> authenticated private sandbox-worker
 -> one disposable Docker/OCI container
 -> explicit result or transport ambiguity
 -> Java terminal ledger / UNKNOWN + Checkpoint + Artifact
```

M51-PR2 also uses this boundary for bounded read-only Git recovery capture. `git rev-parse`,
status, tracked diff and per-untracked-file diff run in disposable containers mounted to the exact
Workspace. There is no host Git fallback; Runtime persists the immutable recovery snapshot only
after its Run/Workspace revision fence succeeds.

## Implemented Docker/OCI boundary

The worker uses the mature Docker SDK for Python 7.2.0. It does not implement the Docker
Engine protocol or a namespace/container runtime.

Each accepted request creates a fresh container with:

- a locally present operator image resolved to an immutable `sha256:` image ID;
- request image/runtime/pull selection forbidden;
- the image entrypoint overridden by one allowlisted executable, with argv passed as an
  array and no shell concatenation;
- configured non-root UID/GID;
- read-only root filesystem;
- every Linux capability dropped and `no-new-privileges:true`;
- private cgroup/IPC namespaces, no privileged mode, devices, ports or restart;
- `network_mode=none` and networking disabled;
- bounded memory+swap, nano-CPU quota, PIDs, shm and tmpfs;
- bounded Docker local log rotation and bounded returned stdout/stderr;
- bounded concurrent execution slots (default four, maximum 32);
- timeout kill and unconditional force-remove;
- exact SpaceAgent labels so startup reaps only expired sandbox containers.

The worker API has no published Compose port. `/execute` requires an internal bearer token
checked with constant-time comparison. Errors do not return stack traces, Docker socket paths,
host paths or Secrets. `/health` exposes only bounded engine/runtime/isolation evidence.

## Workspace isolation

Coding sends only `workspaces/<UUID>`. The worker rejects every other shape.

- Compose/named volume: Docker Engine API 1.45 / Engine 26+ `volume-subpath` mounts only that
  existing Workspace subdirectory at `/workspace`.
- Host-managed worker: an exact resolved directory below the configured root is bind-mounted
  with private propagation.
- No parent Workspace root, database, source mirror, host home, Docker socket or worker path
  is mounted into the child.

The Workspace mount is intentionally read/write because build tools and tests may create
outputs. Java reads the resulting Git status/changed files after the container terminates.

## Failure semantics

- Validation or an explicit container exit/rejection is a terminal FAILED/TIMED_OUT result.
- Worker/HTTP/daemon loss without a trustworthy terminal response causes Java to mark the
  Coding Tool ledger UNKNOWN; it is never blindly retried.
- A matching terminal Tool ledger row replays without another worker/container call.
- Failed container removal after a known exit is handled by label-scoped orphan reaping;
  loss while kill/outcome is ambiguous returns no false terminal result.

## Remaining acceptance

The Java in-process adapter still supports deterministic `echo/fail` for focused tests.
The Python host-process executor has been removed. Project host `RUN_COMMAND` rejects and
Coding Runtime never falls back from OCI execution to a host process.

Docker support is implemented and accepted against Docker Desktop 29.2.1 / API 1.53 using
`runc`. Live evidence proved UID/GID 999, read-only rootfs, no network/DNS, current-Workspace
write, other-Workspace absence, timeout kill, the declared cgroup/security/mount settings and
zero labeled leftovers. Docker SDK/fake-client tests independently assert request rejection,
output bounds and orphan reaping.

The daemon does not have gVisor `runsc` installed; only `runc` is available. A real Linux
Engine 26+ deployment with configured `runsc` must still repeat the escape/resource/cleanup
suite before `PLATFORM_PUBLIC_UNTRUSTED_CODE_ENABLED=true` may stop failing startup. This is
an acceptance boundary, not a claim that ordinary Docker/runc alone makes arbitrary
multi-tenant code safe.

Primary references:

- <https://docs.docker.com/engine/containers/run/>
- <https://docs.docker.com/engine/storage/volumes/>
- <https://docker-py.readthedocs.io/en/stable/containers.html>
- <https://gvisor.dev/docs/user_guide/quick_start/docker/>

## M65 pinned runsc acceptance environment

`ops/acceptance/runsc-environment.json` pins the acceptance target to Linux 6.1+, Docker Engine 26+/API 1.45+
and gVisor `runsc release-20260817.0` reporting OCI spec 1.2.1 on x86_64 or aarch64. The daemon must register the
runtime as `runsc`, live-restore must be enabled, and the Sandbox image must be an immutable `sha256:` ID.

`scripts/verify-runsc-environment.sh --static` validates repository structure. `--probe` is read-only and fails on
non-Linux, wrong versions, missing runtime/image pin, or an enabled public-untrusted switch. U01 deliberately makes
`scripts/run-runsc-isolation-acceptance.sh --execute` return BLOCKED; U02 owns real container execution and evidence.
No script installs gVisor, edits Docker daemon configuration, enables a product flag or touches production.

Current M65 evidence (2026-09-09): repository static checks pass, but the U02 read-only probe stops with
`Linux host is required` on the current macOS host. A second read-only audit found Linux Docker Engine 29.2.1/API
1.53 behind Docker Desktop, but only `runc`/`io.containerd.runc.v2` are registered, live-restore is disabled and
there is no host `runsc`. No runsc acceptance container has executed. Public-untrusted mode remains unsupported and
disabled until the exact manifest environment passes the complete U02 matrix.

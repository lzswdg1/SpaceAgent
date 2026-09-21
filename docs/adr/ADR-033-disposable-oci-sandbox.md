# ADR-033: Coding Commands Run in Disposable OCI Sandboxes

> M38-PR2 completion: the Python host-process executor has been removed. Docker/OCI is the
> worker's only execution engine; Java in-process `echo/fail` remains test-only.

- Status: Accepted / implemented in M34-PR1 / public-untrusted runsc acceptance pending
- Date: 2026-08-25

## Context

The M7 Python worker provides process, cwd, rlimit, environment and timeout controls but is
not a container boundary. M21 Coding commands independently execute through Java
`ProcessBuilder` on the platform host. Ledger and approval correctness do not prevent malicious
repository code from reading host files, reaching networks or exhausting the host.

## Decision

- The sandbox worker uses Docker SDK for Python 7.2.0 and creates one disposable OCI container
  per execution. Docker Engine/OCI owns namespaces, cgroups, mounts, security options and cleanup;
  SpaceAgent does not implement a container runtime.
- The worker accepts no model-selected image or runtime. Operators preload one immutable image;
  production may select gVisor `runsc`. The worker never pulls an image while serving a request.
- The execution container is non-root, networkless, read-only, capability-free and
  no-new-privileges, with bounded PIDs/CPU/memory/tmpfs/shm/logs/time. Only the exact current
  Workspace host directory or Docker volume subpath is mounted read/write at `/workspace`.
- The worker is a private privileged infrastructure component. `/execute` requires an internal
  bearer token; execution containers never receive the Docker socket, platform Secret or worker
  filesystem.
- Coding Runtime keeps Governance, ToolExecutionLedger, Checkpoint and Artifact authority. It
  calls a new internal Tooling sandbox-compute API only after its durable Coding claim. The raw
  compute API owns no second ledger. Project remains the owner of Workspace files/Git evidence.
- Host Coding commands are rejected after cutover. The old process executor and Java in-process
  `echo/fail` remain explicit development/test compatibility only.
- Public-untrusted configuration remains rejected until a real Linux Engine 26+ and `runsc`
  deployment passes the escape/resource/cleanup acceptance suite. Configuration text alone is
  not security attestation.

## Consequences

M34 introduces a separately deployable, Docker-API-owning worker and a larger operator-managed
toolchain image, but removes arbitrary repository code from the platform JVM host process. It
adds no PostgreSQL state. A missing/unavailable worker makes Coding command outcome UNKNOWN after
claim; an explicit container rejection/failure is terminal FAILED; matching terminal ledger rows
replay without another container.

References:

- <https://docs.docker.com/engine/containers/run/>
- <https://docs.docker.com/engine/storage/volumes/>
- <https://docker-py.readthedocs.io/en/stable/containers.html>
- <https://gvisor.dev/docs/user_guide/quick_start/docker/>

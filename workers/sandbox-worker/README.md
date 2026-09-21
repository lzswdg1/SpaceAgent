# SpaceAgent Sandbox Worker

Private Docker/OCI execution control plane for Coding commands. The worker never owns
Project, Task, Conversation, Memory, AgentRun, RunStep, Checkpoint or ToolExecutionLedger
state. Java keeps Governance, Ledger, Checkpoint and Artifact authority.

## Container isolation

Docker mode uses Docker SDK for Python 7.2.0 and creates one container per request:

- the request cannot choose or pull an image;
- the worker resolves a locally present immutable `sha256:` image ID;
- the image entrypoint is overridden by one allowlisted executable and argv stays an array;
- `network_mode=none`, read-only rootfs, `cap_drop=ALL`, no-new-privileges and non-root user;
- private cgroup/IPC namespaces, no devices/ports/privileged mode/restart policy;
- bounded PIDs, memory+swap, CPU quota, shm, tmpfs, Docker logs, returned output and timeout;
- bounded concurrent container slots (`SANDBOX_MAX_CONCURRENT_EXECUTIONS`, default four);
- only `workspaces/<UUID>` is mounted at `/workspace`, using Docker Engine 26+
  `volume-subpath` or an exact host bind directory;
- timeout kills the container and every path force-removes it; startup reaps only expired
  containers with the exact SpaceAgent execution label;
- `/execute` requires a constant-time checked internal bearer token.

The worker owns Docker API access and must stay on a private network. Child execution
containers never receive `/var/run/docker.sock`, the worker filesystem or platform Secrets.

The worker extracts W3C Trace Context with the official OpenTelemetry Python SDK and emits a
redacted `execute_tool` span around Docker execution. Set `PLATFORM_OBSERVABILITY_OTLP_ENABLED=true`
and `PLATFORM_OBSERVABILITY_OTLP_ENDPOINT` to opt into OTLP/HTTP. The default is no exporter; trace
failure never changes a Sandbox result. No Workspace path/content, command arguments/output,
business ID or credential is recorded as a span attribute.

## Workspace helper

Project File, Document, Git and Coding effects use the allowlisted
`spaceagent-workspace-tool` executable inside the disposable child container. The helper:

- accepts only a fixed operation enum and a Workspace-relative path;
- rejects absolute paths, traversal and symbolic links;
- supports bounded text/Markdown/HTML/DOCX/PDF reads with `beautifulsoup4`, `python-docx`
  and `pypdf`;
- performs atomic text/Markdown/HTML/DOCX writes and bounded deletes;
- produces a bounded Git snapshot and prepares a local commit only when the expected base SHA
  and patch hash still match;
- never opens a network connection, shell, Provider credential or platform database.

Bounded write content arrives as `inputBase64` on the private execution contract and is exposed
only to the child process environment for the lifetime of that container. Java retains
Governance, Tool ledger, Artifact and SourceMerge authority; the helper response is compute
evidence, not business state.

## Run in Docker mode

The repository Dockerfile is both the worker and operator-owned toolchain image. When the
worker itself runs in Docker, it resolves its own immutable image ID and the name of the
Workspace volume mounted at `/data/workspaces`.

```bash
docker compose --profile sandbox build sandbox-worker
SANDBOX_MODE=http docker compose --profile sandbox up -d sandbox-worker platform-server
```

Optional gVisor deployment:

```bash
SANDBOX_OCI_RUNTIME=runsc \
SANDBOX_MODE=http \
docker compose --profile sandbox up -d sandbox-worker platform-server
```

The Docker daemon must already expose the configured runtime. Docker Desktop 29.2.1/API 1.53
`runc` acceptance passed the non-root/read-only/network/mount/timeout/cleanup checks. That
daemon does not expose `runsc`; public-untrusted release enablement therefore remains blocked
until the same suite passes on a real Linux gVisor deployment.

## Test

```bash
python3 -m venv .venv
.venv/bin/pip install -e .
.venv/bin/python -m unittest discover -s tests -t . -v
```

The fake-Docker tests assert the complete container create request without a daemon. A real
Linux Engine 26+ acceptance suite remains required before the public-untrusted release gate
may change.

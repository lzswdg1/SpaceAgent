"""Disposable Docker/OCI sandbox execution.

The worker owns Docker API access, but the child container receives neither the Docker
socket nor any platform credential.  Docker SDK for Python is the runtime adapter; tests
inject a fake client so isolation options are verified without a daemon.
"""

from __future__ import annotations

import json
import os
import re
import threading
import time
from dataclasses import dataclass
from pathlib import Path, PurePosixPath
from typing import Any, Callable

from .contracts import (
    ExecutionMetadata,
    ResourcePolicy,
    SandboxExecutionRequest,
    SandboxExecutionResponse,
)
from .isolation import sanitize_environment
from .resource_observation import ResourceSampler


STATUS_SUCCEEDED = "SUCCEEDED"
STATUS_FAILED = "FAILED"
STATUS_TIMED_OUT = "TIMED_OUT"
STATUS_OUTPUT_LIMIT_EXCEEDED = "OUTPUT_LIMIT_EXCEEDED"
STATUS_REJECTED = "REJECTED"

SANDBOX_LABEL = "com.spaceagent.sandbox"
SANDBOX_LABEL_VALUE = "execution-v1"
WORKSPACE_PATTERN = re.compile(
    r"^(workspaces|document-workspaces)/[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[1-5][0-9a-fA-F]{3}-"
    r"[89abAB][0-9a-fA-F]{3}-[0-9a-fA-F]{12}$"
)
SOURCE_PATTERN = re.compile(
    r"^sources/[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[1-5][0-9a-fA-F]{3}-"
    r"[89abAB][0-9a-fA-F]{3}-[0-9a-fA-F]{12}$"
)
EXECUTION_ID_PATTERN = re.compile(r"^[A-Za-z0-9_.:-]{1,120}$")
ALLOWED_COMMANDS = frozenset({"echo", "./mvnw", "mvn", "npm", "pnpm", "go", "git"})
ALLOWED_COMMANDS = ALLOWED_COMMANDS | frozenset({"spaceagent-workspace-tool"})


class DockerSandboxConfigurationError(RuntimeError):
    """Raised when the operator Docker sandbox configuration is unsafe/incomplete."""


class DockerSandboxRequestError(ValueError):
    """Raised before a child container is created."""


class DockerSandboxEngineError(RuntimeError):
    """Indicates that no trustworthy terminal child outcome can be returned."""


@dataclass(frozen=True)
class DockerSandboxSettings:
    image: str | None = None
    workspace_root: Path | None = None
    workspace_volume: str | None = None
    workspace_mountpoint: str = "/data/workspaces"
    runtime: str | None = None
    container_user: str = "65532:65532"
    nano_cpus: int = 1_000_000_000
    pids_limit: int = 256
    tmpfs_bytes: int = 64 * 1024 * 1024
    max_timeout_seconds: int = 600
    max_memory_bytes: int = 2 * 1024 * 1024 * 1024
    max_output_bytes: int = 1_000_000
    orphan_ttl_seconds: int = 900
    max_concurrent_executions: int = 4

    def __post_init__(self):
        for name, low, high in (("nano_cpus", 100_000_000, 4_000_000_000),
                ("max_memory_bytes", 64 * 1024 * 1024, 2 * 1024 * 1024 * 1024),
                ("pids_limit", 16, 1024), ("tmpfs_bytes", 8 * 1024 * 1024, 128 * 1024 * 1024),
                ("max_concurrent_executions", 1, 32)):
            value = getattr(self, name)
            if not isinstance(value, int) or isinstance(value, bool) or not low <= value <= high:
                raise DockerSandboxConfigurationError("Invalid operator sandbox resource ceiling: " + name)


class DockerSandboxExecutor:
    """Runs each accepted request in one disposable, capability-free OCI container."""

    def __init__(
        self,
        settings: DockerSandboxSettings,
        client: Any | None = None,
        mount_factory: Callable[..., Any] | None = None,
        log_config_factory: Callable[..., Any] | None = None,
        now: Callable[[], float] = time.time,
    ):
        if client is None:
            try:
                import docker  # type: ignore
                from docker.types import LogConfig, Mount  # type: ignore
            except ImportError as error:
                raise DockerSandboxConfigurationError(
                    "Docker SDK for Python is required for container sandbox mode"
                ) from error
            client = docker.from_env(version="auto")
            mount_factory = Mount
            log_config_factory = LogConfig
        if mount_factory is None or log_config_factory is None:
            raise DockerSandboxConfigurationError("Docker type factories are required")
        self.settings = settings
        self.client = client
        self.mount_factory = mount_factory
        self.log_config_factory = log_config_factory
        self.now = now
        self._slots = threading.BoundedSemaphore(
            max(1, min(settings.max_concurrent_executions, 32))
        )
        self._self_container: Any | None = None
        self.image = self._resolve_image()
        self.workspace_volume = self._resolve_workspace_volume()
        self._verify_engine()

    def capabilities(self) -> dict[str, Any]:
        return {
            "status": "ok",
            "engine": "docker",
            "containerRuntime": self.settings.runtime or "default",
            "image": self.image,
            "networkMode": "none",
            "readOnlyRootfs": True,
            "dropAllCapabilities": True,
            "noNewPrivileges": True,
            "workspaceIsolation": "volume-subpath" if self.workspace_volume else "bind",
            "maxConcurrentExecutions": max(
                1, min(self.settings.max_concurrent_executions, 32)
            ),
            "maxMemoryBytes": self.settings.max_memory_bytes,
            "nanoCpus": self.settings.nano_cpus,
            "pidsLimit": self.settings.pids_limit,
            "tmpfsBytes": self.settings.tmpfs_bytes,
        }

    def reap_orphans(self) -> int:
        removed = 0
        cutoff = self.now() - max(60, self.settings.orphan_ttl_seconds)
        containers = self.client.containers.list(
            all=True, filters={"label": f"{SANDBOX_LABEL}={SANDBOX_LABEL_VALUE}"}
        )
        for container in containers:
            created = _created_epoch(container.attrs.get("Created"))
            if created is None or created > cutoff:
                continue
            try:
                container.remove(force=True, v=True)
                removed += 1
            except Exception:
                # A concurrent worker/daemon cleanup wins safely.
                continue
        return removed

    def execute(self, request: SandboxExecutionRequest) -> SandboxExecutionResponse:
        if not self._slots.acquire(blocking=False):
            now_ms = int(self.now() * 1000)
            return SandboxExecutionResponse(
                executionId=request.executionId,
                agentRunId=request.agentRunId,
                toolCallId=request.toolCallId,
                exitStatus=-1,
                status=STATUS_REJECTED,
                stdout="",
                stderr="",
                artifactRefs=[],
                metadata=ExecutionMetadata(
                    startedAtEpochMs=now_ms,
                    completedAtEpochMs=now_ms,
                    wallTimeMs=0,
                    timedOut=False,
                    outputBytes=0,
                ),
                error="SANDBOX_CAPACITY",
            )
        try:
            return self._execute_claimed(request)
        finally:
            self._slots.release()

    def _execute_claimed(self, request: SandboxExecutionRequest) -> SandboxExecutionResponse:
        started_ms = int(self.now() * 1000)
        container: Any | None = None
        timed_out = False
        stdout = b""
        stderr = b""
        exit_status = -1
        status = STATUS_REJECTED
        error: str | None = "SANDBOX_REQUEST_REJECTED"
        engine_failure = False
        engine_cause: Exception | None = None
        sampler = None
        resources = None
        try:
            policy = self._validate_request(request)
            mount = self._workspace_mount(
                request.workspaceRef, policy.readOnlyWorkspace
            )
            source_mount = self._source_mount(request.sourceRef)
            kwargs = self._container_options(request, policy, mount, source_mount)
            container = self.client.containers.create(**kwargs)
            container.start()
            base = Path(self.settings.workspace_mountpoint) if self.workspace_volume else self.settings.workspace_root
            workspace = base / request.workspaceRef if base is not None and request.workspaceRef else None
            sampler = ResourceSampler(container, workspace)
            sampler.start()
            try:
                wait_result = container.wait(timeout=request.timeoutSeconds + 2)
                exit_status = int(wait_result.get("StatusCode", -1))
            except Exception as wait_error:
                if not _is_timeout(wait_error):
                    raise
                timed_out = True
                try:
                    container.kill()
                except Exception as kill_error:
                    engine_failure = True
                    engine_cause = kill_error
                exit_status = -1
            try:
                container.reload()
            except Exception:
                pass
            stdout = _bytes(container.logs(stdout=True, stderr=False))
            stderr = _bytes(container.logs(stdout=False, stderr=True))
            oom_killed = bool(
                getattr(container, "attrs", {}).get("State", {}).get("OOMKilled", False)
            )
            if timed_out:
                status = STATUS_TIMED_OUT
                error = "SANDBOX_TIMEOUT"
            elif oom_killed:
                status = STATUS_FAILED
                error = "SANDBOX_MEMORY_LIMIT"
            elif exit_status == 0:
                status = STATUS_SUCCEEDED
                error = None
            else:
                status = STATUS_FAILED
                error = f"SANDBOX_EXIT_{exit_status}"
        except DockerSandboxRequestError:
            status = STATUS_REJECTED
            error = "SANDBOX_REQUEST_REJECTED"
        except Exception as failure:
            engine_failure = True
            engine_cause = failure
        finally:
            if sampler is not None:
                resources = sampler.finish()
            if container is not None:
                try:
                    container.remove(force=True, v=True)
                except Exception:
                    # Transport loss after dispatch is handled by Java as UNKNOWN.  A later
                    # worker startup reaps label-scoped leftovers.
                    pass

        if engine_failure:
            raise DockerSandboxEngineError(
                "Docker sandbox produced no trustworthy terminal outcome"
            ) from engine_cause

        limit = self._output_limit(request.resourcePolicy)
        stdout, stderr, limited = _bound_output(stdout, stderr, limit)
        if limited:
            status = STATUS_OUTPUT_LIMIT_EXCEEDED
            error = "SANDBOX_OUTPUT_LIMIT"
        completed_ms = int(self.now() * 1000)
        return SandboxExecutionResponse(
            executionId=request.executionId,
            agentRunId=request.agentRunId,
            toolCallId=request.toolCallId,
            exitStatus=exit_status,
            status=status,
            stdout=stdout.decode("utf-8", "replace"),
            stderr=stderr.decode("utf-8", "replace"),
            artifactRefs=[],
            metadata=ExecutionMetadata(
                startedAtEpochMs=started_ms,
                completedAtEpochMs=completed_ms,
                wallTimeMs=max(0, completed_ms - started_ms),
                timedOut=timed_out,
                outputBytes=len(stdout) + len(stderr),
                resourceMetrics=resources,
            ),
            error=error,
        )

    def _verify_engine(self) -> None:
        version = self.client.version()
        api = str(version.get("ApiVersion", ""))
        try:
            major, minor = (int(value) for value in api.split(".", 1))
        except Exception as error:
            raise DockerSandboxConfigurationError("Docker API version is unavailable") from error
        if (major, minor) < (1, 45):
            raise DockerSandboxConfigurationError(
                "Docker API 1.45 / Engine 26+ is required for sandbox volume-subpath"
            )
        if self.settings.runtime:
            runtimes = version.get("Runtimes") or self.client.info().get("Runtimes") or {}
            if self.settings.runtime not in runtimes:
                raise DockerSandboxConfigurationError("Configured OCI runtime is unavailable")

    def _resolve_image(self) -> str:
        image = self.settings.image
        if image is None:
            container = self._own_container()
            image = str(container.attrs.get("Image") or getattr(container.image, "id", ""))
        if not image or not re.fullmatch(r"sha256:[0-9a-f]{64}", image):
            raise DockerSandboxConfigurationError(
                "Sandbox execution image must resolve to an immutable sha256 image ID"
            )
        try:
            resolved = self.client.images.get(image)
        except Exception as error:
            raise DockerSandboxConfigurationError(
                "Sandbox execution image is not present locally"
            ) from error
        resolved_id = str(getattr(resolved, "id", ""))
        if resolved_id != image:
            raise DockerSandboxConfigurationError("Sandbox image identity changed")
        return image

    def _resolve_workspace_volume(self) -> str | None:
        if self.settings.workspace_volume:
            return _volume_name(self.settings.workspace_volume)
        try:
            container = self._own_container()
        except DockerSandboxConfigurationError:
            return None
        for mount in container.attrs.get("Mounts", []):
            if (
                mount.get("Destination") == self.settings.workspace_mountpoint
                and mount.get("Type") == "volume"
            ):
                return _volume_name(str(mount.get("Name", "")))
        return None

    def _own_container(self) -> Any:
        if self._self_container is not None:
            return self._self_container
        container_id = os.environ.get("HOSTNAME", "").strip()
        if not container_id:
            raise DockerSandboxConfigurationError("Worker container identity is unavailable")
        try:
            self._self_container = self.client.containers.get(container_id)
        except Exception as error:
            raise DockerSandboxConfigurationError(
                "Worker container could not be inspected"
            ) from error
        return self._self_container

    def _validate_request(self, request: SandboxExecutionRequest) -> ResourcePolicy:
        if not EXECUTION_ID_PATTERN.fullmatch(request.executionId or ""):
            raise DockerSandboxRequestError("invalid execution id")
        if request.command not in ALLOWED_COMMANDS:
            raise DockerSandboxRequestError("command is not allowlisted")
        if len(request.arguments) > 100 or any(
            not isinstance(value, str) or len(value) > 4_096 or "\x00" in value
            for value in request.arguments
        ):
            raise DockerSandboxRequestError("command arguments exceed limits")
        if request.inputBase64 is not None and (
            len(request.inputBase64) > 700_000
            or re.fullmatch(r"[A-Za-z0-9+/]*={0,2}", request.inputBase64) is None
        ):
            raise DockerSandboxRequestError("sandbox input exceeds limits")
        if request.timeoutSeconds < 1 or request.timeoutSeconds > self.settings.max_timeout_seconds:
            raise DockerSandboxRequestError("timeout exceeds limit")
        policy = request.resourcePolicy or ResourcePolicy()
        if policy.allowNetwork:
            raise DockerSandboxRequestError("sandbox egress is not implemented")
        if policy.allowedPaths not in ([], ["."]):
            raise DockerSandboxRequestError("only the mounted Workspace is allowed")
        if request.workspaceRef and not WORKSPACE_PATTERN.fullmatch(request.workspaceRef):
            raise DockerSandboxRequestError("workspace reference is invalid")
        if request.sourceRef and not SOURCE_PATTERN.fullmatch(request.sourceRef):
            raise DockerSandboxRequestError("source reference is invalid")
        if bool(request.sourceRef) != (request.tool == "managed-snapshot-materialize"):
            raise DockerSandboxRequestError("source reference is not allowed for this tool")
        sanitize_environment(request.environment)
        return policy

    def _workspace_mount(
        self, workspace_ref: str | None, read_only: bool = False
    ) -> Any | None:
        if not workspace_ref:
            return None
        if self.workspace_volume:
            return self.mount_factory(
                target="/workspace",
                source=self.workspace_volume,
                type="volume",
                read_only=read_only,
                no_copy=True,
                subpath=workspace_ref,
            )
        if self.settings.workspace_root is None:
            raise DockerSandboxRequestError("workspace mount source is unavailable")
        root = self.settings.workspace_root.resolve()
        relative = PurePosixPath(workspace_ref)
        source = (root / Path(*relative.parts)).resolve()
        if root not in source.parents or not source.is_dir():
            raise DockerSandboxRequestError("workspace is unavailable")
        return self.mount_factory(
            target="/workspace",
            source=str(source),
            type="bind",
            read_only=read_only,
            propagation="rprivate",
        )

    def _container_options(
        self,
        request: SandboxExecutionRequest,
        policy: ResourcePolicy,
        mount: Any | None,
        source_mount: Any | None,
    ) -> dict[str, Any]:
        memory = max(64 * 1024 * 1024, min(policy.maxMemoryBytes, self.settings.max_memory_bytes))
        output = self._output_limit(policy)
        environment = sanitize_environment(request.environment)
        environment.update({"HOME": "/home/sandbox", "CI": "true"})
        if request.inputBase64 is not None:
            environment["SPACEAGENT_INPUT_BASE64"] = request.inputBase64
        labels = {
            SANDBOX_LABEL: SANDBOX_LABEL_VALUE,
            "com.spaceagent.execution": request.executionId,
        }
        options: dict[str, Any] = {
            "image": self.image,
            "command": request.arguments,
            "entrypoint": [request.command],
            "detach": True,
            "network_mode": "none",
            "network_disabled": True,
            "read_only": True,
            "cap_drop": ["ALL"],
            "security_opt": ["no-new-privileges:true"],
            "privileged": False,
            "user": self.settings.container_user,
            "cgroupns": "private",
            "ipc_mode": "private",
            "pids_limit": max(16, min(self.settings.pids_limit, 1_024)),
            "mem_limit": memory,
            "memswap_limit": memory,
            "nano_cpus": max(100_000_000, min(self.settings.nano_cpus, 4_000_000_000)),
            "shm_size": 16 * 1024 * 1024,
            "tmpfs": {
                "/tmp": f"rw,nosuid,nodev,size={self.settings.tmpfs_bytes}",
                "/home/sandbox": f"rw,nosuid,nodev,size={self.settings.tmpfs_bytes}",
            },
            "environment": environment,
            "working_dir": "/workspace" if mount is not None else "/tmp",
            "mounts": [value for value in (mount, source_mount) if value is not None],
            "labels": labels,
            "restart_policy": {"Name": "no"},
            "init": True,
            "stdin_open": False,
            "tty": False,
            "healthcheck": {"test": ["NONE"]},
            "log_config": self.log_config_factory(
                type="local",
                config={
                    "max-size": str(max(65_536, output)),
                    "max-file": "1",
                    "compress": "false",
                },
            ),
        }
        if self.settings.runtime:
            options["runtime"] = self.settings.runtime
        return options

    def _source_mount(self, source_ref: str | None) -> Any | None:
        if not source_ref:
            return None
        if self.workspace_volume:
            return self.mount_factory(target="/source", source=self.workspace_volume,
                type="volume", read_only=True, no_copy=True, subpath=source_ref)
        if self.settings.workspace_root is None:
            raise DockerSandboxRequestError("source mount is unavailable")
        root = self.settings.workspace_root.resolve()
        relative = PurePosixPath(source_ref)
        source = (root / Path(*relative.parts)).resolve()
        if root not in source.parents or not source.is_dir():
            raise DockerSandboxRequestError("source snapshot is unavailable")
        return self.mount_factory(target="/source", source=str(source), type="bind",
            read_only=True, propagation="rprivate")

    def _output_limit(self, policy: ResourcePolicy) -> int:
        return max(1_024, min(policy.maxOutputBytes, self.settings.max_output_bytes))


def _bound_output(stdout: bytes, stderr: bytes, limit: int) -> tuple[bytes, bytes, bool]:
    if len(stdout) + len(stderr) <= limit:
        return stdout, stderr, False
    stdout = stdout[:limit]
    stderr = stderr[: max(0, limit - len(stdout))]
    return stdout, stderr, True


def _bytes(value: Any) -> bytes:
    if value is None:
        return b""
    if isinstance(value, bytes):
        return value
    return str(value).encode("utf-8", "replace")


def _is_timeout(error: Exception) -> bool:
    current: BaseException | None = error
    seen: set[int] = set()
    while current is not None and id(current) not in seen:
        seen.add(id(current))
        if isinstance(current, TimeoutError) or current.__class__.__name__ in {
            "ReadTimeout",
            "ReadTimeoutError",
            "Timeout",
        }:
            return True
        current = current.__cause__ or current.__context__
    return False


def _volume_name(value: str) -> str:
    if not re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9_.-]{0,127}", value):
        raise DockerSandboxConfigurationError("Workspace volume name is invalid")
    return value


def _created_epoch(value: Any) -> float | None:
    if isinstance(value, (int, float)):
        return float(value)
    if not isinstance(value, str) or not value:
        return None
    try:
        from datetime import datetime

        return datetime.fromisoformat(value.replace("Z", "+00:00")).timestamp()
    except ValueError:
        return None

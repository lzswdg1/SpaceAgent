"""Command-line entry point for the sandbox worker."""

from __future__ import annotations

import argparse
import os
from pathlib import Path

from .docker_executor import DockerSandboxExecutor, DockerSandboxSettings
from .http_app import make_server
from .telemetry import configure_telemetry


def main() -> None:
    parser = argparse.ArgumentParser(description="SpaceAgent sandbox worker")
    parser.add_argument("--host", default="127.0.0.1")
    parser.add_argument("--port", type=int, default=9200)
    parser.add_argument(
        "--workspace-root",
        default="/tmp/spaceagent-sandbox",
        help="absolute root directory that workspace references must resolve under",
    )
    parser.add_argument(
        "--workspace-volume", default=os.environ.get("SANDBOX_WORKSPACE_VOLUME")
    )
    parser.add_argument(
        "--execution-image", default=os.environ.get("SANDBOX_EXECUTION_IMAGE")
    )
    parser.add_argument("--oci-runtime", default=os.environ.get("SANDBOX_OCI_RUNTIME"))
    parser.add_argument(
        "--container-user",
        default=os.environ.get("SANDBOX_CONTAINER_USER", "65532:65532"),
    )
    parser.add_argument(
        "--internal-token", default=os.environ.get("SANDBOX_INTERNAL_TOKEN", "")
    )
    parser.add_argument(
        "--max-concurrent-executions",
        type=int,
        default=int(os.environ.get("SANDBOX_MAX_CONCURRENT_EXECUTIONS", "4")),
    )
    parser.add_argument("--max-memory-bytes", type=int, default=os.environ.get("SANDBOX_MAX_MEMORY_BYTES", str(2 * 1024 * 1024 * 1024)))
    parser.add_argument("--nano-cpus", type=int, default=os.environ.get("SANDBOX_NANO_CPUS", "1000000000"))
    parser.add_argument("--pids-limit", type=int, default=os.environ.get("SANDBOX_PIDS_LIMIT", "256"))
    parser.add_argument("--tmpfs-bytes", type=int, default=os.environ.get("SANDBOX_TMPFS_BYTES", str(64 * 1024 * 1024)))
    args = parser.parse_args()

    if len(args.internal_token) < 32:
        parser.error("--internal-token must contain at least 32 characters")
    executor = DockerSandboxExecutor(
        DockerSandboxSettings(
            image=args.execution_image,
            workspace_root=Path(args.workspace_root),
            workspace_volume=args.workspace_volume,
            runtime=args.oci_runtime,
            container_user=args.container_user,
            max_concurrent_executions=args.max_concurrent_executions,
            max_memory_bytes=args.max_memory_bytes,
            nano_cpus=args.nano_cpus,
            pids_limit=args.pids_limit,
            tmpfs_bytes=args.tmpfs_bytes,
        )
    )
    executor.reap_orphans()
    configure_telemetry()
    server = make_server(
        executor, args.host, args.port, internal_token=args.internal_token
    )
    print(f"sandbox-worker listening on {args.host}:{args.port} (docker)")
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        pass
    finally:
        server.server_close()


if __name__ == "__main__":
    main()

import unittest

from sandbox_worker.contracts import ResourcePolicy, SandboxExecutionRequest
from sandbox_worker.docker_executor import (
    SANDBOX_LABEL,
    SANDBOX_LABEL_VALUE,
    STATUS_OUTPUT_LIMIT_EXCEEDED,
    STATUS_REJECTED,
    STATUS_SUCCEEDED,
    STATUS_TIMED_OUT,
    DockerSandboxEngineError,
    DockerSandboxExecutor,
    DockerSandboxSettings,
)


IMAGE = "sha256:" + "a" * 64
WORKSPACE = "workspaces/00000000-0000-4000-8000-000000000003"


def request(**overrides):
    values = dict(
        executionId="exec-1",
        agentRunId="run-1",
        toolCallId="call-1",
        workspaceRef=WORKSPACE,
        taskRef="task-1",
        tool="coding-run-command",
        command="mvn",
        arguments=["test"],
        timeoutSeconds=30,
        resourcePolicy=ResourcePolicy(
            maxOutputBytes=65_536,
            maxCpuSeconds=5,
            maxMemoryBytes=268_435_456,
            allowNetwork=False,
            allowedPaths=["."],
        ),
        environment="{}",
    )
    values.update(overrides)
    return SandboxExecutionRequest(**values)


class FakeImage:
    id = IMAGE


class FakeImages:
    def get(self, value):
        if value != IMAGE:
            raise KeyError(value)
        return FakeImage()


class FakeContainer:
    def __init__(self, wait_result=None, wait_error=None, stdout=b"ok\n", stderr=b""):
        self.wait_result = wait_result or {"StatusCode": 0}
        self.wait_error = wait_error
        self.stdout = stdout
        self.stderr = stderr
        self.attrs = {"State": {"OOMKilled": False}, "Created": 0}
        self.started = False
        self.killed = False
        self.removed = False

    def start(self):
        self.started = True

    def wait(self, timeout):
        if self.wait_error:
            raise self.wait_error
        return self.wait_result

    def reload(self):
        return None

    def logs(self, stdout, stderr):
        return self.stdout if stdout else self.stderr

    def kill(self):
        self.killed = True

    def remove(self, force, v):
        self.removed = force and v


class FakeContainers:
    def __init__(self, created=None, orphans=None):
        self.created = created or FakeContainer()
        self.orphans = orphans or []
        self.options = None
        self.create_calls = 0

    def create(self, **kwargs):
        self.options = kwargs
        self.create_calls += 1
        return self.created

    def list(self, **kwargs):
        self.list_options = kwargs
        return self.orphans


class FakeClient:
    def __init__(self, container=None, orphans=None):
        self.images = FakeImages()
        self.containers = FakeContainers(container, orphans)

    def version(self):
        return {"ApiVersion": "1.47", "Runtimes": {"runsc": {}}}

    def info(self):
        return {"Runtimes": {"runsc": {}}}


def mount_factory(**kwargs):
    return kwargs


def log_config_factory(**kwargs):
    return kwargs


def executor(client=None, **overrides):
    settings = dict(
        image=IMAGE,
        workspace_volume="spaceagent-platform-workspaces",
        runtime="runsc",
        container_user="999:999",
    )
    settings.update(overrides)
    return DockerSandboxExecutor(
        DockerSandboxSettings(**settings),
        client=client or FakeClient(),
        mount_factory=mount_factory,
        log_config_factory=log_config_factory,
        now=lambda: 1_000.0,
    )


class DockerSandboxExecutorTests(unittest.TestCase):
    def test_operator_resource_caps_reach_child_not_just_controller(self):
        client = FakeClient()
        worker = executor(client, max_memory_bytes=128 * 1024 * 1024, nano_cpus=750_000_000,
                          pids_limit=64, tmpfs_bytes=32 * 1024 * 1024)
        worker.execute(request())
        options = client.containers.options
        self.assertEqual(128 * 1024 * 1024, options["mem_limit"])
        self.assertEqual(options["mem_limit"], options["memswap_limit"])
        self.assertEqual(750_000_000, options["nano_cpus"])
        self.assertEqual(64, options["pids_limit"])
        self.assertIn("33554432", options["tmpfs"]["/tmp"])
        self.assertEqual(128 * 1024 * 1024, worker.capabilities()["maxMemoryBytes"])

    def test_invalid_operator_ceiling_does_not_silently_expand_to_default(self):
        from sandbox_worker.docker_executor import DockerSandboxConfigurationError
        for values in ({"nano_cpus": 0}, {"max_memory_bytes": 1}, {"max_concurrent_executions": 0},
                       {"pids_limit": 4096}, {"tmpfs_bytes": True}):
            with self.assertRaises(DockerSandboxConfigurationError):
                DockerSandboxSettings(**values)

    def test_real_docker_sdk_supports_volume_subpath_when_installed(self):
        try:
            from docker.types import Mount
        except ImportError:
            self.skipTest("Docker SDK is installed by the worker package/image")
        mount = Mount(
            target="/workspace",
            source="spaceagent-platform-workspaces",
            type="volume",
            read_only=False,
            no_copy=True,
            subpath=WORKSPACE,
        )
        self.assertEqual(WORKSPACE, mount["VolumeOptions"]["Subpath"])

    def test_success_uses_hardened_container_options_and_exact_volume_subpath(self):
        client = FakeClient()
        result = executor(client).execute(request())

        self.assertEqual(STATUS_SUCCEEDED, result.status)
        self.assertEqual("ok\n", result.stdout)
        self.assertTrue(client.containers.created.removed)
        options = client.containers.options
        self.assertEqual(IMAGE, options["image"])
        self.assertEqual(["test"], options["command"])
        self.assertEqual(["mvn"], options["entrypoint"])
        self.assertEqual("none", options["network_mode"])
        self.assertTrue(options["network_disabled"])
        self.assertTrue(options["read_only"])
        self.assertEqual(["ALL"], options["cap_drop"])
        self.assertEqual(["no-new-privileges:true"], options["security_opt"])
        self.assertFalse(options["privileged"])
        self.assertEqual("999:999", options["user"])
        self.assertEqual("private", options["cgroupns"])
        self.assertEqual("private", options["ipc_mode"])
        self.assertEqual("runsc", options["runtime"])
        self.assertEqual(WORKSPACE, options["mounts"][0]["subpath"])
        self.assertFalse(options["mounts"][0]["read_only"])
        self.assertEqual("/workspace", options["mounts"][0]["target"])
        self.assertNotIn("volumes", options)
        self.assertNotIn("devices", options)
        self.assertEqual(SANDBOX_LABEL_VALUE, options["labels"][SANDBOX_LABEL])

    def test_timeout_kills_and_removes_container(self):
        container = FakeContainer(wait_error=TimeoutError())
        client = FakeClient(container)
        result = executor(client).execute(request(timeoutSeconds=1))
        self.assertEqual(STATUS_TIMED_OUT, result.status)
        self.assertTrue(result.metadata.timedOut)
        self.assertTrue(container.killed)
        self.assertTrue(container.removed)

    def test_output_is_bounded_after_daemon_log_rotation(self):
        container = FakeContainer(stdout=b"x" * 10_000)
        client = FakeClient(container)
        policy = ResourcePolicy(maxOutputBytes=1_024, allowedPaths=["."])
        result = executor(client).execute(request(resourcePolicy=policy))
        self.assertEqual(STATUS_OUTPUT_LIMIT_EXCEEDED, result.status)
        self.assertEqual(1_024, result.metadata.outputBytes)
        self.assertEqual("SANDBOX_OUTPUT_LIMIT", result.error)

    def test_engine_failure_after_dispatch_returns_no_false_terminal_result(self):
        container = FakeContainer(wait_error=RuntimeError("daemon disconnected"))
        client = FakeClient(container)
        with self.assertRaises(DockerSandboxEngineError):
            executor(client).execute(request())
        self.assertTrue(container.removed)

    def test_network_and_invalid_workspace_fail_before_container_creation(self):
        client = FakeClient()
        network = ResourcePolicy(allowNetwork=True, allowedPaths=["."])
        result = executor(client).execute(request(resourcePolicy=network))
        self.assertEqual(STATUS_REJECTED, result.status)
        result = executor(client).execute(request(workspaceRef="workspaces/../foreign"))
        self.assertEqual(STATUS_REJECTED, result.status)
        self.assertEqual(0, client.containers.create_calls)

    def test_read_only_policy_mounts_workspace_read_only(self):
        client = FakeClient()
        policy = ResourcePolicy(
            maxOutputBytes=65_536,
            maxCpuSeconds=5,
            maxMemoryBytes=268_435_456,
            allowNetwork=False,
            allowedPaths=["."],
            readOnlyWorkspace=True,
        )
        result = executor(client).execute(request(resourcePolicy=policy))
        self.assertEqual(STATUS_SUCCEEDED, result.status)
        self.assertTrue(client.containers.options["mounts"][0]["read_only"])

    def test_managed_snapshot_adds_exact_read_only_source_mount(self):
        client = FakeClient()
        source = "sources/00000000-0000-4000-8000-000000000004"
        result = executor(client).execute(request(tool="managed-snapshot-materialize",
            command="spaceagent-workspace-tool", arguments=["snapshot-materialize"], sourceRef=source))
        self.assertEqual(STATUS_SUCCEEDED, result.status)
        self.assertEqual("/workspace", client.containers.options["mounts"][0]["target"])
        self.assertEqual("/source", client.containers.options["mounts"][1]["target"])
        self.assertEqual(source, client.containers.options["mounts"][1]["subpath"])
        self.assertTrue(client.containers.options["mounts"][1]["read_only"])

    def test_bounded_input_is_injected_only_into_child_environment(self):
        client = FakeClient()
        worker = executor(client)
        worker.execute(request(inputBase64="Zml4dHVyZQ=="))
        self.assertEqual(
            "Zml4dHVyZQ==",
            client.containers.options["environment"]["SPACEAGENT_INPUT_BASE64"],
        )

    def test_concurrency_capacity_rejects_before_container_creation(self):
        client = FakeClient()
        worker = executor(client, max_concurrent_executions=1)
        self.assertTrue(worker._slots.acquire(blocking=False))
        try:
            result = worker.execute(request())
        finally:
            worker._slots.release()
        self.assertEqual(STATUS_REJECTED, result.status)
        self.assertEqual("SANDBOX_CAPACITY", result.error)
        self.assertEqual(0, client.containers.create_calls)

    def test_reaper_removes_only_expired_label_selected_containers(self):
        old = FakeContainer()
        old.attrs["Created"] = 1
        current = FakeContainer()
        current.attrs["Created"] = 999
        client = FakeClient(orphans=[old, current])
        worker = executor(client, orphan_ttl_seconds=60)
        self.assertEqual(1, worker.reap_orphans())
        self.assertTrue(old.removed)
        self.assertFalse(current.removed)
        self.assertEqual(
            {"label": f"{SANDBOX_LABEL}={SANDBOX_LABEL_VALUE}"},
            client.containers.list_options["filters"],
        )


if __name__ == "__main__":
    unittest.main()

"""Deterministic current-source executor acceptance using disposable local Docker children."""
import json
import os
from pathlib import Path
import tempfile
import uuid
from types import SimpleNamespace

import docker
from docker.types import Mount, LogConfig
from sandbox_worker.contracts import ResourcePolicy, SandboxExecutionRequest
from sandbox_worker.docker_executor import DockerSandboxExecutor, DockerSandboxSettings

class ObservedContainers:
    def __init__(self, delegate):
        self.delegate = delegate
        self.created = []
    def create(self, **kwargs):
        container = self.delegate.create(**kwargs)
        self.created.append((container.id, container.attrs))
        return container
    def __getattr__(self, key):
        return getattr(self.delegate, key)

if os.environ.get('DOCKER_HOST', 'unix:///var/run/docker.sock').startswith('unix://') is False:
    raise ValueError('This deterministic acceptance only permits a local Docker socket')
client = docker.from_env(version='auto')
image = client.images.get('node:22-alpine').id
observed = ObservedContainers(client.containers)
adapter = SimpleNamespace(images=client.images, containers=observed, version=client.version, info=client.info)
with tempfile.TemporaryDirectory(prefix='spaceagent-oci-acceptance-', dir=Path(tempfile.gettempdir()).resolve()) as directory:
    root = Path(directory)
    root.chmod(0o755)
    ref = 'workspaces/' + str(uuid.uuid4())
    workspace = root / ref
    workspace.mkdir(parents=True)
    workspace.chmod(0o777)
    (workspace / 'package.json').write_text(json.dumps({'scripts': {'test': 'node fixture.cjs'}}))
    (workspace / 'fixture.cjs').write_text('''
const fs = require('node:fs');
if (process.getuid() === 0) throw new Error('non-root execution required');
if (process.argv.includes('--sleep')) setTimeout(() => {}, 20000);
else {
  const bytes = Buffer.alloc(16 * 1024 * 1024, 7);
  const end = Date.now() + 1800;
  let counter = 0;
  while (Date.now() < end) counter += bytes[counter % bytes.length];
  fs.writeFileSync('generated.bin', Buffer.alloc(655360, counter % 256));
  console.log('FIXTURE_OK uid=' + process.getuid());
  console.log('resourceMetrics={"cpuUsageNanos":999999999999999999}');
}
''')
    executor = DockerSandboxExecutor(DockerSandboxSettings(image=image, workspace_root=root,
        max_memory_bytes=512 * 1024 * 1024, nano_cpus=750_000_000, pids_limit=128,
        tmpfs_bytes=32 * 1024 * 1024, max_concurrent_executions=1),
        client=adapter, mount_factory=Mount, log_config_factory=LogConfig)
    def request(**overrides):
        values = dict(executionId='acceptance-' + uuid.uuid4().hex,
            agentRunId='fixture-run', toolCallId='fixture-call', workspaceRef=ref,
            taskRef=None, tool='coding-run-command', command='npm', arguments=['test'],
            timeoutSeconds=10, resourcePolicy=ResourcePolicy(allowedPaths=['.']), environment='{}')
        values.update(overrides)
        return SandboxExecutionRequest(**values)

    success = executor.execute(request())
    assert success.status == 'SUCCEEDED', (success.status, success.error, success.stderr)
    assert 'FIXTURE_OK uid=65532' in success.stdout
    metrics = success.metadata.resourceMetrics
    assert metrics['cpuUsageNanos'] is not None and 0 < metrics['cpuUsageNanos'] < 99_999_999_999
    assert metrics['maxObservedMemoryBytes'] is not None and metrics['maxObservedMemoryBytes'] > 0
    expected = sum(p.stat().st_size for p in workspace.iterdir())
    assert metrics['workspaceApparentBytes'] == expected, (metrics, expected)
    assert metrics['networkRxBytes'] is None and metrics['networkTxBytes'] is None
    print('OCI_RESOURCE_REAL ' + json.dumps(metrics, sort_keys=True))
    print('PASS non-root, CPU/memory from Docker stats, exact apparent bytes, stdout cannot forge counters')

    read_only = executor.execute(request(resourcePolicy=ResourcePolicy(allowedPaths=['.'], readOnlyWorkspace=True)))
    assert read_only.status == 'FAILED', read_only
    assert 'EROFS' in read_only.stderr
    print('PASS actual read-only workspace refuses the fixture write')

    timeout = executor.execute(request(arguments=['test', '--', '--sleep'], timeoutSeconds=1))
    assert timeout.status == 'TIMED_OUT' and timeout.metadata.timedOut
    print('PASS actual timeout kills child and reports TIMED_OUT')

    before = len(observed.created)
    denied = executor.execute(request(resourcePolicy=ResourcePolicy(allowNetwork=True)))
    assert denied.status == 'REJECTED' and len(observed.created) == before
    print('PASS unsupported egress is denied before dispatch')
    for container_id, attrs in observed.created:
        config = attrs['HostConfig']
        assert config['NanoCpus'] == 750_000_000 and config['Memory'] <= 512 * 1024 * 1024
        assert config['PidsLimit'] == 128
        assert config['NetworkMode'] == 'none' and config['ReadonlyRootfs']
        assert config['CapDrop'] == ['ALL'] and not config['Privileged']
        assert 'no-new-privileges:true' in config['SecurityOpt']
        assert attrs['Config']['User'] == '65532:65532'
        mounts = attrs['Mounts']
        assert len(mounts) == 1 and mounts[0]['Destination'] == '/workspace'
        try:
            observed.delegate.get(container_id)
            raise AssertionError('child container retained')
        except docker.errors.NotFound:
            pass
    print('PASS daemon-confirmed isolation options; all three disposable children removed')
client.close()

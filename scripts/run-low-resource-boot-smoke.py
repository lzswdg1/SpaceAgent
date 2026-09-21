"""Benign isolated capped-JVM/PG startup smoke; no keys, paid calls, current stack or image builds."""
import json
import os
from pathlib import Path
import re
import secrets
import subprocess
import tempfile
import time
import urllib.request
import uuid
import docker
from docker.types import Mount, LogConfig

ROOT = Path(__file__).resolve().parents[1]
if not os.environ.get('DOCKER_HOST', 'unix:///var/run/docker.sock').startswith('unix://'):
    raise ValueError('Only a local Unix Docker socket is permitted')
plan = json.loads(subprocess.check_output(['docker', 'compose', '--env-file', '.env.release.example', '--env-file', '.env.low-resource.example',
    '-f', 'docker-compose.yml', '-f', 'docker-compose.images.yml', '-f', 'docker-compose.release.yml', '-f', 'docker-compose.low-resource.yml',
    '--profile', 'web', '--profile', 'admin', '--profile', 'sandbox', 'config', '--format', 'json'], cwd=ROOT, env={**os.environ, 'COMPOSE_PROFILES': ''}))
jar = ROOT / 'apps/platform-server/target/platform-server-0.0.1-SNAPSHOT-exec.jar'
if not jar.is_file(): raise ValueError('Current packaged business Jar required')
api_spec = plan['services']['platform-server']; pg_spec = plan['services']['postgres']
client = docker.from_env(version='auto')
jre = client.images.get('eclipse-temurin:21-jre').id
pg_image = client.images.get('pgvector/pgvector:pg17').id
tag = 'spaceagent-low-boot-' + uuid.uuid4().hex[:10]
network = pg = api = None
opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))
try:
    with tempfile.TemporaryDirectory(prefix=tag, dir=Path(tempfile.gettempdir()).resolve()) as directory:
        workspace = Path(directory); workspace.chmod(0o777)
        # Local bridge permits the host-only published test port. No providers/URL jobs are created.
        network = client.networks.create(tag)
        password = secrets.token_hex(24)
        pg = client.containers.create(pg_image, name=tag + '-pg', detach=True, network=network.name,
            command=pg_spec['command'], environment={'POSTGRES_DB': 'spaceagent_platform', 'POSTGRES_USER': 'fixture', 'POSTGRES_PASSWORD': password},
            mem_limit=pg_spec['mem_limit'], nano_cpus=int(float(pg_spec['cpus']) * 1e9))
        pg.start()
        for _ in range(60):
            if pg.exec_run(['pg_isready', '-h', '127.0.0.1', '-U', 'fixture']).exit_code == 0: break
            time.sleep(1)
        else: raise AssertionError('Temporary PG readiness timeout')
        environment = dict(api_spec['environment'])
        environment.update(SPRING_DATASOURCE_URL='jdbc:postgresql://' + pg.name + ':5432/spaceagent_platform',
            SPRING_DATASOURCE_USERNAME='fixture', SPRING_DATASOURCE_PASSWORD=password,
            PLATFORM_WORKSPACE_MANAGED_ROOT='/data/workspaces', PLATFORM_KNOWLEDGE_INDEX_CONTENT_ROOT='/data/workspaces/knowledge',
            PLATFORM_SANDBOX_MODE='in-process', PLATFORM_PROJECT_CODING_WORKER_ENABLED='false', PLATFORM_PROJECT_INTAKE_WORKER_ENABLED='false',
            PLATFORM_RUNTIME_COORDINATION_ENABLED='false', PLATFORM_KNOWLEDGE_LEGACY_MODE='disabled',
            PLATFORM_KNOWLEDGE_INDEX_WORKER_ENABLED='false', PLATFORM_KNOWLEDGE_INDEX_INTAKE_ENABLED='false')
        # The HTTP adapter is retained; no providers/tasks/model keys or outbound requests are created.
        for key in ['PLATFORM_JWT_SECRET', 'PLATFORM_INTERNAL_TOKEN', 'PLATFORM_INFERENCE_MODEL_PROVIDER_ENCRYPTION_KEY',
                    'PLATFORM_TOOLING_MCP_ENCRYPTION_KEY', 'PLATFORM_IDENTITY_ACTIVITY_HASH_KEY', 'PLATFORM_SYSTEM_ADMIN_JWT_SECRET']:
            environment[key] = secrets.token_hex(32)
        environment['PLATFORM_SANDBOX_INTERNAL_TOKEN'] = environment['PLATFORM_INTERNAL_TOKEN']
        environment['PLATFORM_KNOWLEDGE_EMBEDDING_API_KEY'] = ''
        api = client.containers.create(jre, name=tag + '-api', detach=True, network=network.name,
            entrypoint=api_spec['entrypoint'], command=[], working_dir='/app', environment=environment,
            user='65532:65532', read_only=True, cap_drop=['ALL'], security_opt=['no-new-privileges:true'],
            tmpfs={'/tmp': 'rw,nosuid,nodev,size=67108864'},
            mounts=[Mount('/app/app.jar', str(jar), type='bind', read_only=True), Mount('/data/workspaces', str(workspace), type='bind')],
            mem_limit=api_spec['mem_limit'], nano_cpus=int(float(api_spec['cpus']) * 1e9), pids_limit=api_spec['pids_limit'],
            ports={'9000/tcp': ('127.0.0.1', None)}, log_config=LogConfig(type='local', config={'max-size': '1000000', 'max-file': '1', 'compress': 'false'}))
        api.start()
        for _ in range(20):
            api.reload(); bindings = api.attrs['NetworkSettings']['Ports'].get('9000/tcp') or []
            if bindings: break
            time.sleep(.25)
        else: raise AssertionError('Host-only test port binding unavailable')
        port = bindings[0]['HostPort']
        origin = 'http://127.0.0.1:' + port
        for _ in range(120):
            api.reload()
            if not api.attrs['State']['Running']: raise AssertionError('Capped JVM exited before readiness')
            try:
                with opener.open(origin + '/actuator/health/readiness', timeout=2) as r:
                    if json.load(r).get('status') == 'UP': break
            except Exception: pass
            time.sleep(1)
        else: raise AssertionError('Capped JVM readiness timeout')
        token = None
        def request(path, body=None):
            headers = {'Content-Type': 'application/json'}
            if token: headers['Authorization'] = 'Bearer ' + token
            req = urllib.request.Request(origin + path, data=None if body is None else json.dumps(body).encode(), headers=headers)
            with opener.open(req, timeout=10) as response: return json.load(response)['data']
        auth = request('/api/v1/auth/register', {'username': uuid.uuid4().hex + '@example.test', 'password': secrets.token_hex(18) + 'Aa1!', 'displayName': 'Isolated capped smoke'})
        token = auth['token']
        base = request('/api/v1/knowledge/bases', {'scope': 'PERSONAL', 'name': 'Capped smoke'})['base']['id']
        assert re.fullmatch(r'[0-9a-f-]{36}', base)
        api.reload(); stats = api.stats(stream=False, one_shot=True)
        assert api.attrs['HostConfig']['Memory'] == 1152 * 1024 ** 2
        assert api.attrs['HostConfig']['NanoCpus'] == 1000000000
        print(json.dumps({'status': 'PASS', 'businessProcessCapMiB': 1152, 'heapMiB': 640, 'businessCpu': 1,
            'pgCapMiB': 512, 'observedCgroupMemoryBytes': stats.get('memory_stats', {}).get('usage'),
            'checks': ['capped-current-Jar-ready', 'identity-registration', 'pgvector-selected-Knowledge-base-create'],
            'scope': 'business+PG only; no model/OCI/Admin/TLS full-stack or4GiB-host capacity claim'}))
except Exception as error:
    print('BOOT_SMOKE_FAIL: ' + type(error).__name__)
    raise SystemExit(1) from None
finally:
    # Exact owned ephemeral fixtures only; never match current-stack labels/names or read log content.
    for container in (api, pg):
        if container is not None: container.remove(force=True, v=True)
    if network is not None: network.remove()
    client.close()
    print('FIXTURE_CLEANUP_DONE')

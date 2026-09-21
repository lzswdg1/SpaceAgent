import { spawnSync, spawn } from 'node:child_process';
import { randomBytes } from 'node:crypto';
import { createWriteStream } from 'node:fs';
import { tmpdir } from 'node:os';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const name = `spaceagent-audit-milvus-${randomBytes(5).toString('hex')}`;
const password = randomBytes(30).toString('hex');
const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const logPath = path.join(tmpdir(), `${name}.log`);
const parserUri = process.env.SPACEAGENT_TIKA_TEST_URI || 'http://127.0.0.1:19998';
const parserUrl = new URL(parserUri);
if (parserUrl.protocol !== 'http:' || !['localhost', '127.0.0.1', '[::1]'].includes(parserUrl.hostname)) {
  throw new Error('This deterministic acceptance only permits a local HTTP parser');
}
const docker = (args, env = process.env) => {
  const r = spawnSync('docker', args, { encoding: 'utf8', env });
  if (r.status !== 0) throw new Error(`Docker command ${args[0]} failed: ${r.stderr}`);
  return r.stdout.trim();
};
const dockerHost = process.env.DOCKER_HOST || docker(['context', 'inspect', '--format', '{{(index .Endpoints "docker").Host}}']);
if (!dockerHost.startsWith('unix://')) throw new Error('Only a local Unix Docker socket is permitted');
let started = false;
try {
  const tika = await fetch(new URL('/version', parserUrl), { signal: AbortSignal.timeout(8000) });
  if (!tika.ok) throw new Error(`Tika version returned ${tika.status}`);
  console.log(`LOCAL_PARSER ${await tika.text()}`);
  docker(['run', '--detach', '--rm', '--pull=never', '--name', name,
    '--memory=3g', '--cpus=2', '-p', '127.0.0.1::19530', '-p', '127.0.0.1::9091',
    '--mount', `type=bind,source=${root}/docker/rag/embedEtcd.yaml,target=/milvus/configs/embedEtcd.yaml,readonly`,
    '-e', 'ETCD_USE_EMBED=true', '-e', 'ETCD_DATA_DIR=/var/lib/milvus/etcd',
    '-e', 'ETCD_CONFIG_PATH=/milvus/configs/embedEtcd.yaml', '-e', 'COMMON_STORAGETYPE=local',
    '-e', 'DEPLOY_MODE=STANDALONE', '-e', 'COMMON_SECURITY_AUTHORIZATIONENABLED=true',
    '-e', 'COMMON_SECURITY_DEFAULTROOTPASSWORD',
    'milvusdb/milvus@sha256:a8ac051e59eb084d41bd317ec51aac28553d664e91c43a806ccd6a1538abc1df',
    'milvus', 'run', 'standalone'], { ...process.env, COMMON_SECURITY_DEFAULTROOTPASSWORD: password });
  started = true;
  const port = docker(['port', name, '19530/tcp']).split(':').at(-1);
  const healthPort = docker(['port', name, '9091/tcp']).split(':').at(-1);
  let healthy = false;
  for (let i = 0; i < 90; i++) {
    try {
      const response = await fetch(`http://127.0.0.1:${healthPort}/healthz`, { signal: AbortSignal.timeout(1500) });
      if (response.ok) { healthy = true; break; }
    } catch {}
    if (i % 10 === 0) console.log(`LOCAL_MILVUS awaiting health (${i * 2}s)`);
    await new Promise(r => setTimeout(r, 2000));
  }
  if (!healthy) throw new Error('Isolated Milvus did not become healthy');
  console.log(`LOCAL_MILVUS healthy; isolated collections; no persistent volume (${name})`);
  const selectors = [
    'MilvusVectorIndexIntegrationTest',
    'TikaDocumentParsingGatewayTest#realIsolatedTikaExtractsPdfAndDocxWithoutInventingDocxPageNumbers',
    'KnowledgeIndexPublicationPostgresTest#realMilvusWriteVerifySearchAndPostgresActivationUseTheSameGeneration',
    'PlatformKnowledgeIndexIntakePostgresTest#binaryAdmissionRunsIsolatedParserAndPublishesRealPageMetadata',
  ].join(',');
  const log = createWriteStream(logPath);
  const argumentsForMaven = process.argv.includes('--full') ? ['-o', '-q', '-fae', 'test']
    : ['-o', '-q', '-pl', 'apps/platform-server', '-am', '-Dsurefire.failIfNoSpecifiedTests=false', `-Dtest=${selectors}`, 'test'];
  const maven = spawn('./mvnw', argumentsForMaven, {
    cwd: root, env: { ...process.env,
      DOCKER_HOST: dockerHost,
      TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE: '/var/run/docker.sock',
      TESTCONTAINERS_HOST_OVERRIDE: '127.0.0.1',
      SPACEAGENT_TIKA_TEST_URI: parserUri,
      SPACEAGENT_MILVUS_TEST_URI: `http://127.0.0.1:${port}`,
      SPACEAGENT_MILVUS_TEST_TOKEN: `root:${password}`,
    }, stdio: ['ignore', 'pipe', 'pipe'],
  });
  maven.stdout.pipe(log, { end: false });
  maven.stderr.pipe(log, { end: false });
  const code = await new Promise((resolve, reject) => {
    maven.once('error', reject);
    maven.once('close', resolve);
  });
  await new Promise(resolve => log.end(resolve));
  console.log(`DEPENDENCY_ACCEPTANCE exit=${code} log=${logPath}`);
  process.exitCode = code || 0;
} catch (error) {
  console.error(String(error).replaceAll(password, '[REDACTED]'));
  process.exitCode = 1;
} finally {
  if (started) {
    docker(['stop', '--time=10', name]);
    console.log(`FIXTURE_REMOVED ${name}; business containers and volumes unchanged`);
  }
}

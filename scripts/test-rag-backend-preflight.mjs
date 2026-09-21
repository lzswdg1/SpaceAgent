import { spawnSync } from 'node:child_process'
import path from 'node:path'
import { fileURLToPath } from 'node:url'
import assert from 'node:assert/strict'
const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..')
for (const [backend, memory, cpus, expected] of [
  ['pgvector', 4 * 1024 ** 3, 2, 0], ['pgvector', 4 * 1024 ** 3, 1, 1],
  ['milvus', 4 * 1024 ** 3, 2, 1], ['milvus', 8 * 1024 ** 3, 2, 0], ['unknown', 8 * 1024 ** 3, 2, 1],
]) {
  const result = spawnSync('bash', ['scripts/check-rag-environment.sh'], { cwd: root, encoding: 'utf8', env: {
    ...process.env, PATH: `${root}/scripts/fixtures/rag-preflight:${process.env.PATH}`,
    PLATFORM_KNOWLEDGE_VECTOR_STORE_MODE: backend, FIXTURE_RAM: String(memory), FIXTURE_CPUS: String(cpus),
    MILVUS_IMAGE: 'milvusdb/milvus:v2.6.22@sha256:' + 'a'.repeat(64), MILVUS_BOOTSTRAP_PASSWORD: 'fixture-only-password',
  } })
  assert.equal(result.status, expected, result.stderr)
}
console.log('PASS 5 backend-specific local preflight cases; no daemon, model or network calls')

#!/usr/bin/env python3
"""Explicit live Knowledge acceptance. Secrets travel only in memory/environment and HTTP bodies.

Never run against a business DB. This runner owns its fresh PostgreSQL, Java process, workspace,
and optional exact Milvus collection. No automatic paid retries, DeepSeek, or GitHub.
"""
import argparse
import io
import hashlib
import json
import os
from pathlib import Path
import re
import secrets
import signal
import subprocess
import sys
import tempfile
import time
import urllib.error
import urllib.parse
import urllib.request
import uuid
import zipfile
from xml.sax.saxutils import escape

ROOT = Path(__file__).resolve().parents[1]
SKILL = ROOT / '.agents/skills/spaceagent-real-e2e/scripts'


class Failure(Exception):
    pass


def command(args, env=None):
    result = subprocess.run(args, env=env, capture_output=True, text=True)
    if result.returncode:
        raise Failure('LOCAL_COMMAND_FAILED:' + Path(args[0]).name)
    return result.stdout.strip()


def launch(backend='milvus', document_type='text', sse_only=False):
    credentials = ROOT / 'testapikey'
    command(['bash', str(SKILL / 'inspect_credentials.sh'), str(credentials)])
    files = [p for p in credentials.iterdir() if p.is_file()]
    for p in files:
        if (p.stat().st_mode & 0o777) != 0o600:
            raise Failure('CREDENTIAL_MODE_INVALID')
        command(['git', '-C', str(ROOT), 'check-ignore', str(p)])
    parent = ROOT / '.run/real-e2e'
    parent.mkdir(parents=True, exist_ok=True)
    run = Path(tempfile.mkdtemp(prefix='bailian-rag-', dir=parent))
    run.chmod(0o700)
    secret_file = run / 'secrets.env'
    try:
        command(['bash', str(SKILL / 'materialize_secret_env.sh'), str(credentials), str(secret_file)])
        # The existing materializer quotes for bash; never parse it with eval in another language or print it.
        return subprocess.run(['bash', '-c', 'set -a; source "$1"; unset DEEPSEEK_API_KEY; exec python3 "$2" --execute "$3" --backend "$4" --document-type "$5"',
                               'bailian-live-test', str(secret_file), str(Path(__file__).resolve()), str(run), backend, document_type],
                              env=dict(os.environ, BAILIAN_RAG_SSE_ONLY='true' if sse_only else 'false')).returncode
    finally:
        secret_file.unlink(missing_ok=True)


class Acceptance:
    def __init__(self, run, backend='milvus', document_type='text', sse_only=False):
        self.run = run
        self.backend = backend
        self.document_type = document_type
        self.sse_only = sse_only
        self.key = os.environ.get('QWEN_API_KEY', '')
        self.provider_url = os.environ.get('QWEN_OPENAI_BASE_URL', '').rstrip('/')
        parsed = urllib.parse.urlsplit(self.provider_url)
        if not self.key or parsed.scheme != 'https' or parsed.username or parsed.password or parsed.query or parsed.fragment:
            raise Failure('CREDENTIAL_ENDPOINT_INVALID')
        if not parsed.hostname or not (parsed.hostname == 'dashscope.aliyuncs.com' or parsed.hostname.endswith('.maas.aliyuncs.com')
                                        or parsed.hostname in ('dashscope-intl.aliyuncs.com', 'dashscope-us.aliyuncs.com')):
            raise Failure('BAILIAN_ENDPOINT_REQUIRES_REVIEW')
        self.host = parsed.hostname
        self.tag = uuid.uuid4().hex[:12]
        self.db_name = 'spaceagent-live-rag-' + self.tag
        self.prefix = 'liverag' + self.tag
        self.collection = None
        self.db_started = False
        self.process = None
        self.log = run / 'platform.log'
        self.sensitive = [self.key]
        self.evidence = {'scope': 'LIVE_BAILIAN_KNOWLEDGE', 'status': 'RUNNING', 'checks': [],
                         'backend': backend, 'documentType': document_type, 'sourceCommit': command(['git', '-C', str(ROOT), 'rev-parse', 'HEAD']),
                         'ordinaryChat': 'NOT_RUN' if sse_only else 'IN_SCOPE',
                         'embeddingModel': 'text-embedding-v4', 'dimensions': 1024, 'chatModel': 'qwen-plus',
                         'reranker': 'NOT_TESTED', 'paidRetries': 0, 'productionAcceptance': False}
        self.token = None
        self.opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))
        self.java_env = None
        self.url = None

    def check(self, condition, name):
        if not condition:
            raise Failure(name)
        self.evidence['checks'].append(name)
        print('PASS: ' + name, flush=True)

    def request(self, method, path, body=None, expected=200, token=True, headers=None, raw=False):
        url = self.url + path
        payload = json.dumps(body, ensure_ascii=False).encode() if isinstance(body, (dict, list)) else body
        h = dict(headers or {})
        if isinstance(body, (dict, list)):
            h['Content-Type'] = 'application/json'
        if token and self.token:
            h['Authorization'] = 'Bearer ' + self.token
        req = urllib.request.Request(url, data=payload, method=method, headers=h)
        try:
            with self.opener.open(req, timeout=90) as response:
                data = response.read(2_000_001)
                status = response.status
        except urllib.error.HTTPError as error:
            status = error.code
            data = error.read(200000)
        except Exception:
            # No second POST after uncertain delivery, including a local HTTP timeout.
            raise Failure('REQUEST_OUTCOME_UNCONFIRMED') from None
        if len(data) > 2_000_000 or self.key.encode() in data:
            raise Failure('RESPONSE_BOUND_OR_SECRET_REDACTION_FAILED')
        if status != expected:
            try:
                code = json.loads(data).get('code', 'UNKNOWN')
            except Exception:
                code = 'UNKNOWN'
            code = code if isinstance(code, str) and re.fullmatch(r'[A-Z0-9_]{1,100}', code) else 'REDACTED'
            raise Failure(f'HTTP_{status}:{code}')
        if raw:
            return data
        return json.loads(data).get('data')

    def sql(self, query):
        # Called only with fixed SQL or generated UUIDs, on this runner's disposable DB.
        return command(['docker', 'exec', self.db_name, 'psql', '-U', 'fixture', '-d', 'spaceagent_platform', '-Atc', query])

    def start(self):
        jar = ROOT / 'apps/platform-server/target/platform-server-0.0.1-SNAPSHOT-exec.jar'
        if not jar.is_file():
            raise Failure('CURRENT_PACKAGED_JAR_REQUIRED')
        if self.backend == 'milvus':
            container = json.loads(command(['docker', 'inspect', 'spaceagent-rag-proof-rag-milvus-1']))[0]
            if container['State'].get('Health', {}).get('Status') != 'healthy':
                raise Failure('DEDICATED_MILVUS_NOT_HEALTHY')
            password = next((s.split('=', 1)[1] for s in container['Config']['Env'] if s.startswith('COMMON_SECURITY_DEFAULTROOTPASSWORD=')), '')
            if not password:
                raise Failure('DEDICATED_MILVUS_AUTH_UNAVAILABLE')
            self.milvus_token = 'root:' + password
            self.sensitive.append(password)
        if self.document_type == 'docx':
            with self.opener.open('http://127.0.0.1:19998/version', timeout=5) as response:
                self.check(b'3.3.0' in response.read(1024), 'LOCAL_ISOLATED_TIKA_READY')
        db_password = secrets.token_hex(24)
        self.sensitive.append(db_password)
        clean_env = {k: v for k, v in os.environ.items() if k in ('PATH', 'HOME', 'DOCKER_HOST', 'DOCKER_CONTEXT', 'JAVA_HOME', 'TMPDIR')}
        db_env = dict(clean_env, POSTGRES_PASSWORD=db_password, POSTGRES_USER='fixture', POSTGRES_DB='spaceagent_platform')
        command(['docker', 'run', '-d', '--pull=never', '--name', self.db_name, '--memory', '512m', '--cpus', '1',
                 '-p', '127.0.0.1::5432', '-e', 'POSTGRES_PASSWORD', '-e', 'POSTGRES_USER', '-e', 'POSTGRES_DB', 'pgvector/pgvector:pg17'], db_env)
        self.db_started = True
        port = command(['docker', 'port', self.db_name, '5432']).split(':')[-1]
        for _ in range(60):
            if subprocess.run(['docker', 'exec', self.db_name, 'pg_isready', '-h', '127.0.0.1', '-U', 'fixture'], capture_output=True).returncode == 0:
                break
            time.sleep(1)
        (self.run / 'workspaces').mkdir()
        env = dict(clean_env)
        for name in ('PLATFORM_JWT_SECRET', 'PLATFORM_INTERNAL_TOKEN', 'PLATFORM_INFERENCE_MODEL_PROVIDER_ENCRYPTION_KEY',
                     'PLATFORM_TOOLING_MCP_ENCRYPTION_KEY', 'PLATFORM_IDENTITY_ACTIVITY_HASH_KEY', 'PLATFORM_SYSTEM_ADMIN_JWT_SECRET'):
            env[name] = secrets.token_hex(32)
            self.sensitive.append(env[name])
        env.update(PLATFORM_SERVER_PORT='0', SPRING_DATASOURCE_URL=f'jdbc:postgresql://127.0.0.1:{port}/spaceagent_platform',
                   SPRING_DATASOURCE_USERNAME='fixture', SPRING_DATASOURCE_PASSWORD=db_password,
                   PLATFORM_PERSISTENCE='postgres', PLATFORM_RELEASE_MODE='trusted-beta', SPACEAGENT_RELEASE_VERSION='live-rag-' + self.tag,
                   PLATFORM_EXPECTED_SCHEMA_VERSION='1098', PLATFORM_TRUSTED_CODE_ONLY='true', PLATFORM_PUBLIC_UNTRUSTED_CODE_ENABLED='false',
                   PLATFORM_ALLOW_INSECURE_LOCAL='false', PLATFORM_SYSTEM_ADMIN_ALLOW_INSECURE_LOCAL='false',
                   PLATFORM_INFERENCE_EXECUTION_MODE='http', PLATFORM_INFERENCE_ALLOWED_PROVIDER_HOSTS=self.host,
                   PLATFORM_KNOWLEDGE_LEGACY_MODE='disabled', PLATFORM_KNOWLEDGE_INDEX_INTAKE_ENABLED='true', PLATFORM_KNOWLEDGE_INDEX_WORKER_ENABLED='true',
                   PLATFORM_KNOWLEDGE_VECTOR_STORE_MODE=self.backend, PLATFORM_KNOWLEDGE_INDEX_CONTENT_ROOT=str(self.run / 'objects'))
        if self.backend == 'milvus':
            env.update(PLATFORM_KNOWLEDGE_MILVUS_URI='http://127.0.0.1:19530', PLATFORM_KNOWLEDGE_MILVUS_TOKEN=self.milvus_token,
                       PLATFORM_KNOWLEDGE_MILVUS_ALLOW_INSECURE_LOCAL='true', PLATFORM_KNOWLEDGE_MILVUS_COLLECTION_PREFIX=self.prefix)
        if self.document_type == 'docx':
            env.update(PLATFORM_KNOWLEDGE_PARSER_URI='http://127.0.0.1:19998', PLATFORM_KNOWLEDGE_PARSER_ALLOW_PRIVATE_HTTP='true')
        self.java_env = env
        self.jar = jar
        self.boot()

    def boot(self):
        with self.log.open('ab') as log:
            self.process = subprocess.Popen(['java', '-Xms128m', '-Xmx768m', '-jar', str(self.jar),
                '--server.address=127.0.0.1', '--platform.workspace.managed-root=' + str(self.run / 'workspaces'),
                '--platform.inference.health-probes-enabled=false', '--platform.tooling.mcp.registry.worker-enabled=false',
                '--platform.runtime.coordination.enabled=false', '--platform.knowledge.url-refresh.enabled=false',
                '--platform.identity.cleanup.worker-enabled=false', '--platform.identity.user-cleanup.worker-enabled=false'],
                cwd=self.run, env=self.java_env, stdout=log, stderr=log)
        self.log.chmod(0o600)
        for _ in range(90):
            if self.process.poll() is not None:
                raise Failure('ISOLATED_PLATFORM_STARTUP_FAILED')
            matches = re.findall(rb'Tomcat started on port ([0-9]+)', self.log.read_bytes())
            if matches:
                self.url = 'http://127.0.0.1:' + matches[-1].decode()
                try:
                    with self.opener.open(self.url + '/actuator/health/readiness', timeout=2) as r:
                        if json.load(r).get('status') == 'UP':
                            self.check(True, 'TRUSTED_BETA_READY')
                            return
                except Exception:
                    pass
            time.sleep(1)
        raise Failure('ISOLATED_PLATFORM_READINESS_TIMEOUT')

    def stop_java(self):
        if self.process and self.process.poll() is None:
            self.process.terminate()
            try:
                self.process.wait(timeout=35)
            except subprocess.TimeoutExpired:
                self.process.kill()
                self.process.wait()

    def test(self):
        self.start()
        password = secrets.token_hex(18) + 'Aa1!'
        self.sensitive.append(password)
        username = 'bailian-rag-' + self.tag + '@example.test'
        registration = self.request('POST', '/api/v1/auth/register', {'username': username, 'password': password, 'displayName': 'Isolated RAG acceptance'}, token=False)
        self.token = registration['token']
        self.sensitive.append(self.token)
        self.request('PUT', '/api/v1/inference-budget/policy', {'enabled': True, 'monthlyRequestLimit': 8, 'monthlyTokenLimit': 20000, 'monthlyCostLimitMicros': 0})
        provider = self.request('POST', '/api/v1/model-providers', {'name': 'Bailian isolated test', 'type': 'openai-compatible',
            'baseUrl': self.provider_url, 'apiKey': self.key, 'authType': 'bearer', 'enabled': True, 'isDefault': True,
            'models': [{'modelId': 'qwen-plus', 'displayName': 'qwen-plus', 'maxContextTokens': 32768, 'isDefault': True},
                       {'modelId': 'text-embedding-v4', 'displayName': 'embedding', 'maxContextTokens': 8192, 'isDefault': False}]}, expected=201)
        pid = provider['id']
        self.check(provider.get('apiKey') == 'configured', 'PROVIDER_SECRET_REDACTED')
        tested = self.request('POST', f'/api/v1/model-providers/{pid}/test')
        self.check(tested.get('success') and tested.get('status') == 'ACTIVE', 'REAL_BAILIAN_CONNECTION')
        models = self.request('GET', f'/api/v1/model-providers/{pid}/models')
        chat_model = next(m['id'] for m in models if m['modelId'] == 'qwen-plus')
        pool = self.request('POST', '/api/v1/model-pools', {'name': 'Isolated Qwen pool', 'visibility': 'PRIVATE', 'routingStrategy': 'PRIORITY', 'fallbackEnabled': False}, expected=201)
        self.request('POST', f'/api/v1/model-pools/{pool["id"]}/members', {'providerId': pid, 'providerModelId': chat_model, 'priority': 0, 'weight': 1}, expected=201)
        self.check(self.request('POST', f'/api/v1/model-pools/{pool["id"]}/activate')['status'] == 'ACTIVE', 'MODEL_POOL_ACTIVE')
        base = self.request('POST', '/api/v1/knowledge/bases', {'scope': 'PERSONAL', 'name': 'Isolated live RAG'}, expected=201)['base']['id']
        space = self.request('POST', f'/api/v1/knowledge/bases/{base}/embedding-spaces', {'providerId': pid, 'modelId': 'text-embedding-v4',
            'modelRevision': 'live-alias-default-1024', 'dimensions': 1024, 'preprocessingHash': hashlib.sha256(b'acceptance-utf8').hexdigest()}, expected=201)['space']['id']
        if self.backend == 'milvus':
            self.collection = self.prefix + '_hybrid_v2_' + hashlib.sha256(space.encode()).hexdigest()
        marker = 'PINE-' + secrets.token_hex(6).upper()
        document = f'内部测试知识：海松蓝项目的负责人是林澈。海松蓝项目的验收口令是 {marker}。维护窗口为星期三凌晨两点。\n此内容完全是隔离测试数据。'
        payload = docx(document) if self.document_type == 'docx' else document.encode()
        media = 'application/vnd.openxmlformats-officedocument.wordprocessingml.document' if self.document_type == 'docx' else 'text/plain'
        self.evidence['sourceSha256'] = hashlib.sha256(payload).hexdigest()
        receipt = self.request('POST', f'/api/v1/knowledge/bases/{base}/indexed-documents?' + urllib.parse.urlencode({'name': 'acceptance.' + self.document_type, 'spaceId': space}),
                               payload, 202, headers={'Content-Type': media, 'Idempotency-Key': 'upload-once'})
        for _ in range(90):
            job = self.request('GET', '/api/v1/knowledge/index-jobs/' + receipt['jobId'])
            if job['state'] == 'COMPLETED':
                break
            if job['state'] in ('FAILED', 'CANCELLED', 'RECONCILIATION_REQUIRED'):
                self.evidence['indexState'] = job['state']
                self.evidence['indexError'] = job.get('safeErrorCode')
                raise Failure('LIVE_INDEX_' + job['state'])
            time.sleep(2)
        self.check(job['state'] == 'COMPLETED', 'REAL_EMBEDDING_' + self.backend.upper() + '_PUBLICATION')
        if self.document_type == 'docx':
            generation = receipt['generationId']
            if not re.fullmatch(r'[0-9a-f-]{36}', generation):
                raise Failure('INVALID_GENERATION_ID')
            reference = self.sql("SELECT parse_metadata_reference FROM platform_knowledge_generation_sources WHERE generation_id='" + generation + "'")
            if not re.fullmatch(r'knowledge-index/[0-9a-f-]{36}/[0-9a-f]{64}', reference):
                raise Failure('PARSE_EVIDENCE_REFERENCE_INVALID')
            parsed = json.loads((self.run / 'objects' / reference).read_bytes())
            self.check(parsed.get('parser') == 'TIKA_3.3.0_XHTML_V1', 'DOCX_ACTUALLY_PARSED_WITH_STORED_TIKA_EVIDENCE')
            self.evidence['parser'] = parsed['parser']
        if self.backend == 'pgvector':
            stored = json.loads(self.sql("SELECT json_build_object('mode',(SELECT mode FROM platform_knowledge_vector_backend), 'rows',count(*),'dimensions',max(public.vector_dims(embedding)),'type',max(pg_typeof(embedding)::text)) FROM platform_knowledge_pgvector_entries"))
            self.check(stored['mode'] == 'pgvector' and stored['rows'] > 0 and stored['dimensions'] == 1024 and stored['type'] in ('vector', 'public.vector'), 'NATIVE_PGVECTOR_ROWS_AND_DIMENSIONS')
            self.evidence['storedIndex'] = stored
        question = '海松蓝项目的验收口令是什么？'
        query = {'baseIds': [base], 'query': question, 'topK': 3, 'maxContextTokens': 2048}
        retrieval = self.request('POST', '/api/v1/knowledge/collections/retrieve', query, headers={'Idempotency-Key': 'query-once'})
        self.check(not retrieval['degraded'] and bool(retrieval['hits']), 'REAL_DENSE_LEXICAL_RETRIEVAL_NOT_FALLBACK')
        hit = next(h for h in retrieval['hits'] if marker in h['content'])
        self.check(hit['citation']['documentId'] == receipt['documentId'] and hashlib.sha256(hit['content'].encode()).hexdigest() == hit['citation']['contentHash'], 'CITATION_AND_CONTENT_HASH')
        self.evidence['retrievalHits'] = len(retrieval['hits'])
        before = int(self.sql('SELECT count(*) FROM platform_embedding_calls'))
        self.request('POST', '/api/v1/knowledge/collections/retrieve', query, headers={'Idempotency-Key': 'query-once'})
        self.check(int(self.sql('SELECT count(*) FROM platform_embedding_calls')) == before, 'RETRIEVAL_RETRY_REUSES_PAID_EMBEDDING')
        agent = self.request('POST', '/api/v1/agents', {'name': 'Isolated RAG agent', 'systemPrompt': '仅依据提供的知识回答问题，缺少证据就说不知道。回答不超过一句话。',
            'modelPoolId': pool['id'], 'temperature': 0.1, 'maxTokens': 128, 'maxTurns': 1, 'permissionMode': 'private',
            'memoryEnabled': False, 'ragEnabled': True, 'networkEnabled': False, 'enabledToolIds': [], 'knowledgeCollectionIds': [base]}, expected=201)['id']
        if not self.sse_only:
            conversation = self.request('POST', '/api/v1/chat/conversations', {'agentId': agent, 'name': 'Isolated RAG test'})['conversationId']
            reply = self.request('POST', '/api/v1/chat/messages', {'agentId': agent, 'conversationId': conversation, 'message': question})
            self.evidence['chatInputTokens'] = reply.get('inputTokenCount')
            self.evidence['chatOutputTokens'] = reply.get('outputTokenCount')
            self.evidence['answerSha256'] = hashlib.sha256(str(reply.get('assistantMessage', '')).encode()).hexdigest()
            self.check(marker in str(reply.get('assistantMessage', '')), 'CHAT_GROUNDED_IN_UNDISCLOSED_DOCUMENT_MARKER')
            self.check(has_document_citation(reply, receipt['documentId']), 'CHAT_RAG_USAGE_AND_DOCUMENT_CITATION')
            self.check(reply.get('inputTokenCount', 0) > 0 and reply.get('outputTokenCount', 0) > 0, 'REAL_CHAT_TOKEN_USAGE')
        stream_conversation = self.request('POST', '/api/v1/chat/conversations', {'agentId': agent, 'name': 'Independent stream RAG test'})['conversationId']
        stream = self.request('POST', '/api/v1/chat/messages/stream', {'agentId': agent, 'conversationId': stream_conversation, 'message': question},
                              headers={'Accept': 'text/event-stream'}, raw=True)
        events = sse_events(stream)
        self.check(not any(event == 'error' for event, data in events) and any(event == 'done' for event, data in events), 'REAL_RAG_SSE_COMPLETES')
        self.check(marker in ''.join(data.get('content', '') for event, data in events if event == 'delta'), 'INDEPENDENT_SSE_GROUNDED_WITHOUT_PRIOR_ANSWER_HISTORY')
        done = next(data for event, data in events if event == 'done')
        self.check(has_document_citation(done, receipt['documentId']), 'SSE_RAG_USAGE_AND_DOCUMENT_CITATION')
        self.check(done.get('ragUsed') and done.get('inputTokenCount', 0) > 0 and done.get('outputTokenCount', 0) > 0, 'SSE_REAL_RAG_AND_PROVIDER_USAGE')
        self.evidence['sseInputTokens'] = done['inputTokenCount']
        self.evidence['sseOutputTokens'] = done['outputTokenCount']
        self.evidence['sseSha256'] = hashlib.sha256(stream).hexdigest()
        stats = json.loads(self.sql("SELECT json_build_object('calls',count(*),'successful',count(*) FILTER(WHERE state='SUCCEEDED'),'unknown',count(*) FILTER(WHERE state='UNKNOWN'),'tokens',sum(input_tokens),'dimensions',max(dimensions)) FROM platform_embedding_calls"))
        self.evidence['embedding'] = stats
        self.check(stats['calls'] <= (3 if self.sse_only else 6) and stats['calls'] == stats['successful'] and stats['tokens'] > 0 and stats['dimensions'] == 1024, 'EMBEDDING_LEDGER_USAGE_AND_CALL_BOUND')
        self.stop_java()
        self.java_env['SPRING_FLYWAY_ENABLED'] = 'false'
        self.boot()
        self.token = self.request('POST', '/api/v1/auth/login', {'username': username, 'password': password}, token=False)['token']
        self.sensitive.append(self.token)
        persisted = self.request('GET', f'/api/v1/knowledge/bases/{base}/indexed-documents/{receipt["documentId"]}')
        self.check(persisted['activeGenerationId'] == receipt['generationId'], 'RESTART_PRESERVES_ACTIVE_GENERATION')
        self.request('DELETE', f'/api/v1/knowledge/bases/{base}/indexed-documents/{receipt["documentId"]}?expectedRevision=1', expected=202)
        cleared = self.request('POST', '/api/v1/knowledge/collections/retrieve', query, headers={'Idempotency-Key': 'query-after-delete'})
        self.check(not cleared['hits'] and int(self.sql('SELECT count(*) FROM platform_embedding_calls')) == stats['calls'], 'DELETION_HIDES_CONTENT_WITHOUT_NEW_MODEL_CALL')
        self.evidence['status'] = 'PASS'

    def cleanup(self):
        self.stop_java()
        clean = True
        if self.collection and hasattr(self, 'milvus_token'):
            try:
                req = urllib.request.Request('http://127.0.0.1:19530/v2/vectordb/collections/drop', method='POST',
                    data=json.dumps({'collectionName': self.collection, 'dbName': 'default'}).encode(),
                    headers={'Authorization': 'Bearer ' + self.milvus_token, 'Content-Type': 'application/json'})
                with self.opener.open(req, timeout=15) as response:
                    result = json.load(response)
                clean = result.get('code') == 0
            except Exception:
                clean = False
        if self.db_started:
            try:
                command(['docker', 'stop', '--timeout', '10', self.db_name])
                command(['docker', 'rm', '-v', self.db_name])
            except Failure:
                clean = False
        if self.log.exists():
            data = self.log.read_bytes()
            leaked = any(value and value.encode() in data for value in self.sensitive)
            self.log.unlink()  # keep neither model responses nor provider logs after acceptance
            self.evidence['secretRedactionPassed'] = not leaked
            if leaked:
                self.evidence['status'] = 'FAIL'
                self.evidence['error'] = 'SECRET_LOG_REDACTION_FAILED'
        # All files under this unique run except the final redacted evidence are generated fixture artifacts.
        import shutil
        for item in self.run.iterdir():
            if item.is_dir():
                shutil.rmtree(item)
            else:
                item.unlink()
        self.evidence['isolatedCleanupPassed'] = clean
        if not clean:
            self.evidence['status'] = 'FAIL'
            self.evidence['cleanupError'] = 'REQUIRES_EXACT_FIXTURE_CLEANUP'
        out = self.run / 'evidence.json'
        out.write_text(json.dumps(self.evidence, ensure_ascii=False, indent=2))
        out.chmod(0o600)
        print('RESULT: ' + self.evidence['status'], flush=True)
        print('Evidence: ' + str(out), flush=True)


def has_document_citation(payload, document_id):
    return bool(payload.get('ragUsed') and payload.get('retrievedChunkCount', 0) > 0 and
                any(isinstance(value, str) and value.startswith(document_id + '#')
                    for value in payload.get('knowledgeCitations', [])))


def docx(text):
    data = io.BytesIO()
    files = {'[Content_Types].xml': '<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"><Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/><Default Extension="xml" ContentType="application/xml"/><Override PartName="/word/document.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"/></Types>',
             '_rels/.rels': '<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="word/document.xml"/></Relationships>',
             'word/document.xml': '<w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main"><w:body><w:p><w:r><w:t xml:space="preserve">' + escape(text) + '</w:t></w:r></w:p></w:body></w:document>'}
    with zipfile.ZipFile(data, 'w', zipfile.ZIP_DEFLATED) as archive:
        for name, value in files.items():
            archive.writestr(name, value.encode())
    return data.getvalue()


def sse_events(raw):
    events = []
    for frame in raw.decode().replace('\r\n', '\n').split('\n\n'):
        lines = frame.splitlines()
        event = next((line[6:].strip() for line in lines if line.startswith('event:')), '')
        data = '\n'.join(line[5:].lstrip() for line in lines if line.startswith('data:'))
        if event and data:
            events.append((event, json.loads(data)))
    return events


def execute(run, backend='milvus', document_type='text', sse_only=False):
    if run.parent != (ROOT / '.run/real-e2e').resolve() or not re.fullmatch(r'bailian-rag-[A-Za-z0-9_-]+', run.name):
        raise Failure('INVALID_ISOLATED_RUN_DIRECTORY')
    (run / 'secrets.env').unlink(missing_ok=True)
    test = Acceptance(run, backend, document_type, sse_only)
    try:
        test.test()
    except Failure as error:
        test.evidence['status'] = 'BLOCKED' if str(error).startswith(('HTTP_401', 'HTTP_402', 'HTTP_429', 'DEDICATED_')) else 'FAIL'
        test.evidence['error'] = str(error)
        print('STOP: ' + str(error), flush=True)
    except BaseException as error:
        test.evidence['status'] = 'FAIL'
        test.evidence['error'] = 'UNEXPECTED_' + type(error).__name__
        print('STOP: ' + test.evidence['error'], flush=True)
    finally:
        test.cleanup()
    return 0 if test.evidence['status'] == 'PASS' else 1


if __name__ == '__main__':
    os.umask(0o077)
    parser = argparse.ArgumentParser(description='Explicit paid Bailian RAG acceptance in an owned disposable environment')
    parser.add_argument('--backend', choices=['milvus', 'pgvector'], default='milvus')
    parser.add_argument('--document-type', choices=['text', 'docx'], default='text')
    parser.add_argument('--execute', type=Path)
    parser.add_argument('--sse-only', action='store_true', default=os.environ.get('BAILIAN_RAG_SSE_ONLY') == 'true', help='Omit ordinary Chat to bound follow-up acceptance cost')
    args = parser.parse_args()
    if args.execute:
        sys.exit(execute(args.execute.resolve(), args.backend, args.document_type, args.sse_only))
    try:
        sys.exit(launch(args.backend, args.document_type, args.sse_only))
    except Failure as error:
        print('STOP: ' + str(error), flush=True)
        sys.exit(1)

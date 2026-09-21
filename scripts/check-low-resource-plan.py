"""Read a merged Compose JSON from stdin; print only a redacted resource summary, never env values."""
import argparse
import json
import re
import sys
from urllib.parse import urlsplit

MIB = 1024 * 1024
PERSISTENT = {'postgres', 'platform-server', 'platform-admin-server', 'sandbox-worker', 'web', 'admin-web'}
INITIALIZERS = {'database-init', 'admin-database-init'}
HOST_RESERVE = 768 * MIB

class PlanError(ValueError):
    pass

def require(condition, message):
    if not condition:
        raise PlanError(message)

def memory(value):
    if isinstance(value, int): return value
    match = re.fullmatch(r'(\d+)([kmg]?)', str(value).lower())
    require(match is not None, 'Unreadable memory ceiling')
    return int(match[1]) * {'': 1, 'k': 1024, 'm': MIB, 'g': 1024 ** 3}[match[2]]

def validate(plan, runtime=False):
    services = plan.get('services', {})
    require(set(services) == PERSISTENT | INITIALIZERS, 'Low-resource plan must select only core clients/API/Admin/PostgreSQL/OCI and initializers')
    ceilings = {'postgres': 512, 'platform-server': 1152, 'platform-admin-server': 512,
                'sandbox-worker': 160, 'web': 64, 'admin-web': 64, 'database-init': 64, 'admin-database-init': 64}
    for name, service in services.items():
        require(0 < memory(service.get('mem_limit')) <= ceilings[name] * MIB, 'Exceeded or missing process memory ceiling: ' + name)
        require(0 < float(service.get('cpus', 0)) <= {'postgres': .5, 'platform-server': 1, 'platform-admin-server': .3, 'sandbox-worker': .25, 'web': .1, 'admin-web': .1, 'database-init': .25, 'admin-database-init': .25}[name], 'Exceeded or missing CPU ceiling: ' + name)
        require(service.get('logging', {}).get('driver') == 'local' and service.get('logging', {}).get('options', {}).get('max-size') == '10m', 'Bounded log rotation required: ' + name)
        require(not service.get('privileged', False), 'Low-resource mode does not grant privileged containers')
        for port in service.get('ports', []):
            require(isinstance(port, dict) and port.get('host_ip') in ('127.0.0.1', '::1'), 'Keep API/database/Admin/client ingress behind the protected local proxy')
    api = services['platform-server']; admin = services['platform-admin-server']; worker = services['sandbox-worker']
    env = api['environment']; ae = admin['environment']; we = worker['environment']
    require(api.get('entrypoint') == ['java', '-Xms128m', '-Xmx640m', '-XX:ActiveProcessorCount=2', '-jar', 'app.jar'], 'Business JVM entrypoint budget missing/overridden')
    require(admin.get('entrypoint') == ['java', '-Xms64m', '-Xmx256m', '-XX:ActiveProcessorCount=1', '-jar', 'app.jar'], 'Admin JVM entrypoint budget missing/overridden')
    for service in (api, admin):
        for key in ('JAVA_TOOL_OPTIONS', 'JDK_JAVA_OPTIONS', '_JAVA_OPTIONS'):
            require(not re.search(r'-Xm[sx]|MaxRAM|InitialRAM|ActiveProcessorCount', service['environment'].get(key, '')), 'Do not override low-resource JVM memory/CPU options')
    require(env.get('PLATFORM_KNOWLEDGE_VECTOR_STORE_MODE') == 'pgvector', 'Low-resource mode requires pgvector; existing backend migration is not implicit')
    require(env.get('PLATFORM_SANDBOX_MODE') == 'http', 'Project file/coding execution must retain OCI, not a host fallback')
    require(int(we.get('SANDBOX_MAX_CONCURRENT_EXECUTIONS', 0)) == 1, 'Exactly one child execution slot required')
    require(int(we.get('SANDBOX_MAX_MEMORY_BYTES', 0)) == 512 * MIB, 'Separate child memory cap missing/overridden')
    require(int(we.get('SANDBOX_NANO_CPUS', 0)) == 750000000, 'Separate child CPU cap missing/overridden')
    require(int(we.get('SANDBOX_TMPFS_BYTES', 0)) == 32 * MIB, 'Child tmpfs cap missing/overridden')
    require(int(we.get('SANDBOX_PIDS_LIMIT', 0)) == 128, 'Child PID cap missing/overridden')
    require(not worker.get('ports'), 'Never publish the Docker-owning worker')
    require(re.fullmatch(r'[1-9][0-9]*(?::[1-9][0-9]*)?', we.get('SANDBOX_CONTAINER_USER', '')) is not None, 'Non-root child execution identity required')
    require(0 < int(env.get('SPRING_DATASOURCE_HIKARI_MAXIMUM_POOL_SIZE', 0)) <= 6 and 0 < int(ae.get('SPRING_DATASOURCE_HIKARI_MAXIMUM_POOL_SIZE', 0)) <= 2, 'Bounded database pools required')
    require(0 < int(env.get('PLATFORM_ADMISSION_MAX_SSE_CONNECTIONS', 0)) <= 8 and 0 < int(env.get('PLATFORM_ADMISSION_MAX_SSE_CONNECTIONS_PER_USER', 0)) <= 2, 'SSE admission budget missing/overridden')
    for key, expected in {'PLATFORM_MULTI_AGENT_ORCHESTRATOR_MODE': 'none', 'PLATFORM_CHAT_AUTOMATIC_PLANNING_ENABLED': 'false',
            'PLATFORM_TOOLING_WEB_SEARCH_MODE': 'none', 'PLATFORM_RERANKER_MODE': 'none', 'PLATFORM_OBSERVABILITY_OTLP_ENABLED': 'false',
            'PLATFORM_MCP_REGISTRY_WORKER_ENABLED': 'false', 'PLATFORM_INFERENCE_HEALTH_PROBES_ENABLED': 'false'}.items():
        require(env.get(key) == expected, 'Optional heavy/background mode not disabled: ' + key)
    require(env.get('PLATFORM_ALLOW_INSECURE_LOCAL') == 'false' and env.get('PLATFORM_PUBLIC_UNTRUSTED_CODE_ENABLED') == 'false'
            and env.get('PLATFORM_SYSTEM_ADMIN_ALLOW_INSECURE_LOCAL') == 'false' and ae.get('ADMIN_PLATFORM_CLIENT_ALLOW_INSECURE_LOCAL') == 'false', 'Low RAM must not weaken the release/security boundary')
    require(ae.get('ADMIN_COOKIE_SECURE') == 'true', 'Secure administrator cookie required')
    endpoint = urlsplit(ae.get('ADMIN_PLATFORM_CLIENT_BASE_URL', ''))
    require(endpoint.scheme == 'https' and endpoint.hostname and not endpoint.username and not endpoint.password, 'Protected private administrator HTTPS/mTLS endpoint required')
    if runtime:
        value = services['postgres']['environment'].get('POSTGRES_PASSWORD', '')
        require(len(value) >= 16 and not re.search(r'replace|fixture|local-dev|change-me', value, re.I), 'Missing/placeholder database password')
        require(env.get('SPRING_DATASOURCE_PASSWORD') == value, 'Business database credential mismatch')
        require(env.get('PLATFORM_SANDBOX_INTERNAL_TOKEN') == we.get('SANDBOX_INTERNAL_TOKEN'), 'Sandbox internal credential mismatch')
        for service, names in [(api, ['PLATFORM_JWT_SECRET', 'PLATFORM_INTERNAL_TOKEN', 'PLATFORM_INFERENCE_MODEL_PROVIDER_ENCRYPTION_KEY', 'PLATFORM_TOOLING_MCP_ENCRYPTION_KEY', 'PLATFORM_IDENTITY_ACTIVITY_HASH_KEY', 'PLATFORM_SYSTEM_ADMIN_JWT_SECRET']),
                (admin, ['ADMIN_JWT_SECRET', 'ADMIN_PASSWORD', 'ADMIN_DB_PASSWORD'])]:
            for key in names:
                value = service['environment'].get(key, '')
                require(len(value) >= (16 if key.endswith('PASSWORD') else 32) and not re.search(r'replace|fixture|local-dev|change-me', value, re.I), 'Missing/placeholder release secret: ' + key)
        for name in PERSISTENT - {'postgres'}:
            image = services[name].get('image', '')
            require(image and not re.search(r'example|replace', image, re.I), 'Configure a real prebuilt image: ' + name)
        require(not endpoint.hostname.endswith('.invalid') and 'example' not in endpoint.hostname, 'Configure the real private mTLS endpoint')
    process_bytes = sum(memory(services[name]['mem_limit']) for name in PERSISTENT)
    init_bytes = max(memory(services[name]['mem_limit']) for name in INITIALIZERS)
    child = int(we['SANDBOX_MAX_MEMORY_BYTES'])
    require(process_bytes + child + init_bytes + HOST_RESERVE <= 4 * 1024 ** 3, 'Aggregate child/process/init/host memory budget exceeds 4GiB')
    return {'processCeilingsMiB': process_bytes // MIB, 'childReservationMiB': child // MIB,
            'hostReserveMiB': HOST_RESERVE // MIB, 'totalBudgetMiB': (process_bytes + child + HOST_RESERVE) // MIB,
            'peakBudgetMiB': (process_bytes + child + init_bytes + HOST_RESERVE) // MIB,
            'capacityAcceptance': False, 'selectedServices': sorted(services)}

if __name__ == '__main__':
    parser = argparse.ArgumentParser(); parser.add_argument('--runtime', action='store_true'); args = parser.parse_args()
    try:
        print(json.dumps(validate(json.load(sys.stdin), args.runtime)))
    except Exception as error:
        # Never print arbitrary JSON/env/exception values on this secret-bearing transport.
        print('LOW_RESOURCE_PLAN_INVALID: ' + (str(error) if isinstance(error, PlanError) else type(error).__name__), file=sys.stderr)
        sys.exit(1)

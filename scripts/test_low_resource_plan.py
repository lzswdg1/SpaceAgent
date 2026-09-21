"""Merged deployment tests; no daemon, keys, model calls or running-stack mutation."""
import copy
import json
from pathlib import Path
import runpy
import subprocess
import unittest

ROOT = Path(__file__).resolve().parents[1]
GUARD = runpy.run_path(str(ROOT / 'scripts/check-low-resource-plan.py'))

class LowResourcePlanTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.plan = json.loads(subprocess.check_output(['docker', 'compose', '--env-file', '.env.release.example', '--env-file', '.env.low-resource.example',
            '-f', 'docker-compose.yml', '-f', 'docker-compose.images.yml', '-f', 'docker-compose.release.yml', '-f', 'docker-compose.low-resource.yml',
            '--profile', 'web', '--profile', 'admin', '--profile', 'sandbox', 'config', '--format', 'json'], cwd=ROOT,
            env={**__import__('os').environ, 'COMPOSE_PROFILES': ''}))

    def test_core_budget_includes_child_and_host_not_only_worker(self):
        result = GUARD['validate'](self.plan)
        self.assertEqual(result['totalBudgetMiB'], 3744)
        self.assertEqual(result['childReservationMiB'], 512)
        self.assertEqual(result['peakBudgetMiB'], 3808)
        self.assertFalse(result['capacityAcceptance'])

    def test_negative_overrides_are_refused(self):
        for change in [lambda p: p['services'].__setitem__('rag-milvus', {}),
                       lambda p: p['services']['platform-server'].__setitem__('mem_limit', 2 * 1024 ** 3),
                       lambda p: p['services']['sandbox-worker']['environment'].__setitem__('SANDBOX_MAX_CONCURRENT_EXECUTIONS', '2'),
                       lambda p: p['services']['sandbox-worker']['environment'].__setitem__('SANDBOX_MAX_MEMORY_BYTES', '2147483648'),
                       lambda p: p['services']['platform-server']['environment'].__setitem__('PLATFORM_KNOWLEDGE_VECTOR_STORE_MODE', 'milvus'),
                       lambda p: p['services']['platform-server']['environment'].__setitem__('PLATFORM_SYSTEM_ADMIN_ALLOW_INSECURE_LOCAL', 'true'),
                       lambda p: p['services']['platform-server']['environment'].__setitem__('_JAVA_OPTIONS', '-Xmx2g')]:
            plan = copy.deepcopy(self.plan); change(plan)
            with self.assertRaises(ValueError): GUARD['validate'](plan)

    def test_unprotected_ingress_or_root_children_are_rejected(self):
        for change in [lambda p: p['services']['web']['ports'][0].__setitem__('host_ip', '0.0.0.0'),
                       lambda p: p['services']['sandbox-worker']['environment'].__setitem__('SANDBOX_CONTAINER_USER', '0:0'),
                       lambda p: p['services']['sandbox-worker'].__setitem__('privileged', True)]:
            plan = copy.deepcopy(self.plan); change(plan)
            with self.assertRaises(ValueError): GUARD['validate'](plan)

    def test_runtime_placeholders_are_not_deployment_ready(self):
        with self.assertRaises(ValueError): GUARD['validate'](self.plan, runtime=True)

    def test_normal_components_are_retained(self):
        services = subprocess.check_output(['docker', 'compose', '--profile', 'observability', '--profile', 'web', '--profile', 'admin', '--profile', 'sandbox',
            'config', '--services'], cwd=ROOT).decode()
        for component in ['grafana', 'loki', 'tempo', 'alloy', 'sandbox-worker', 'platform-admin-server']:
            self.assertIn(component, services)
        self.assertTrue((ROOT / 'docker-compose.rag.yml').is_file())
        self.assertTrue((ROOT / 'docker-compose.parser.yml').is_file())
        self.assertTrue((ROOT / 'docker-compose.knowledge-worker.yml').is_file())

if __name__ == '__main__': unittest.main()

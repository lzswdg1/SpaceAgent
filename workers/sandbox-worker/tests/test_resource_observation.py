import tempfile
import unittest
from pathlib import Path
from sandbox_worker.resource_observation import ResourceSampler


class ResourceObservationTests(unittest.TestCase):
    def test_counters_come_from_engine_and_bytes_from_private_fixture(self):
        class Container:
            def stats(self, **kwargs):
                return {"cpu_stats": {"cpu_usage": {"total_usage": 123}}, "memory_stats": {"usage": 456},
                        "networks": {"eth0": {"rx_bytes": 7, "tx_bytes": 8}}}
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / "file.txt").write_bytes(b"12345")
            sampler = ResourceSampler(Container(), root.resolve())
            sampler.sample()
            sampler.start()
            result = sampler.finish()
            self.assertEqual(123, result["cpuUsageNanos"])
            self.assertEqual(456, result["maxObservedMemoryBytes"])
            self.assertEqual(5, result["workspaceApparentBytes"])
            self.assertEqual(7, result["networkRxBytes"])
            self.assertIn("NOT_FULL_CPU", result["coverage"])

    def test_missing_measurements_are_not_zero_and_bad_counters_are_ignored(self):
        class Container:
            def stats(self, **kwargs):
                return {"cpu_stats": {"cpu_usage": {"total_usage": -1}}, "memory_stats": {"usage": True}}
        sampler = ResourceSampler(Container(), None)
        sampler.sample()
        sampler.start()
        result = sampler.finish()
        self.assertIsNone(result["cpuUsageNanos"])
        self.assertIsNone(result["maxObservedMemoryBytes"])
        self.assertIsNone(result["networkRxBytes"])
        self.assertEqual("UNAVAILABLE", result["coverage"])

"""Trusted control-plane measurements; never parse counters from command stdout."""
from pathlib import Path
import os
import threading


class ResourceSampler:
    def __init__(self, container, workspace: Path | None):
        self.container = container
        self.workspace = workspace
        self.values = {"cpuUsageNanos": None, "maxObservedMemoryBytes": None,
                       "networkRxBytes": None, "networkTxBytes": None, "workspaceApparentBytes": None,
                       "coverage": "UNAVAILABLE"}
        self.stop_event = threading.Event()
        self.thread = threading.Thread(target=self._run, daemon=True)

    @staticmethod
    def number(value):
        return value if isinstance(value, int) and not isinstance(value, bool) and 0 <= value <= 9_000_000_000_000_000_000 else None

    def sample(self):
        try:
            stats = self.container.stats(stream=False, one_shot=True)
            cpu = self.number(stats.get("cpu_stats", {}).get("cpu_usage", {}).get("total_usage"))
            memory = self.number(stats.get("memory_stats", {}).get("usage"))
            if cpu is not None:
                self.values["cpuUsageNanos"] = max(cpu, self.values["cpuUsageNanos"] or 0)
            if memory is not None:
                self.values["maxObservedMemoryBytes"] = max(memory, self.values["maxObservedMemoryBytes"] or 0)
            networks = stats.get("networks")
            if isinstance(networks, dict) and networks:
                for source, target in (("rx_bytes", "networkRxBytes"), ("tx_bytes", "networkTxBytes")):
                    values = [self.number(n.get(source)) for n in networks.values()]
                    if all(v is not None for v in values):
                        self.values[target] = max(sum(values), self.values[target] or 0)
        except Exception:
            pass  # Missing telemetry cannot change the known command outcome.

    def _run(self):
        while not self.stop_event.is_set():
            self.sample()
            self.stop_event.wait(.25)

    def start(self):
        self.thread.start()

    def finish(self):
        self.stop_event.set()
        self.thread.join(timeout=.1)
        result = dict(self.values)
        if self.workspace is not None:
            try:
                root = self.workspace.resolve(strict=True)
                current = self.workspace
                while current != current.parent:
                    if current.is_symlink():
                        raise ValueError("symlink")
                    current = current.parent
                total = count = 0
                for directory, dirs, files in os.walk(root, followlinks=False):
                    count += 1
                    if count > 50_000:
                        raise ValueError("measurement bound")
                    dirs[:] = [d for d in dirs if not (Path(directory) / d).is_symlink()]
                    for name in files:
                        file = Path(directory) / name
                        if file.is_symlink():
                            continue
                        count += 1
                        if count > 50_000:
                            raise ValueError("measurement bound")
                        total += file.stat(follow_symlinks=False).st_size
                result["workspaceApparentBytes"] = total
            except Exception:
                pass
        if any(result[k] is not None for k in result if k != "coverage"):
            result["coverage"] = "RUNNING_CGROUP_SAMPLES_AND_POST_EXECUTION_APPARENT_BYTES; NOT_FULL_CPU_OR_PEAK_RSS_OR_ALLOCATED_DISK"
        return result

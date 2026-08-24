from __future__ import annotations

import os
import subprocess
from dataclasses import dataclass
from pathlib import Path

from .config import GovernorConfig
from .models import BridgeSnapshot, GovernorDecision


@dataclass(frozen=True, slots=True)
class HostMetrics:
    available_ram_gib: float
    load_per_cpu: float


class Governor:
    def __init__(self, config: GovernorConfig) -> None:
        self._config = config

    def evaluate(
        self, bridge: BridgeSnapshot, host: HostMetrics
    ) -> GovernorDecision:
        if bridge.tps < self._config.min_tps:
            return GovernorDecision(False, f"tps {bridge.tps:.2f} below threshold")
        if bridge.tick_ms > self._config.max_tick_ms:
            return GovernorDecision(False, f"tick {bridge.tick_ms:.2f}ms above threshold")
        if host.available_ram_gib < self._config.min_available_ram_gib:
            return GovernorDecision(
                False, f"memory {host.available_ram_gib:.2f}GiB below threshold"
            )
        if host.load_per_cpu > self._config.max_load_per_cpu:
            return GovernorDecision(
                False, f"host load {host.load_per_cpu:.2f} above threshold"
            )
        return GovernorDecision(True, "all performance thresholds healthy")


def collect_host_metrics() -> HostMetrics:
    cpu_count = os.cpu_count() or 1
    load_per_cpu = os.getloadavg()[0] / cpu_count
    return HostMetrics(_available_ram_gib(), load_per_cpu)


def _available_ram_gib() -> float:
    meminfo = Path("/proc/meminfo")
    if meminfo.exists():
        for line in meminfo.read_text(encoding="ascii").splitlines():
            if line.startswith("MemAvailable:"):
                return int(line.split()[1]) / (1024 * 1024)
    if os.uname().sysname == "Darwin":
        return _darwin_available_ram_gib()
    return 0.0


def _darwin_available_ram_gib() -> float:
    try:
        page_size = int(
            subprocess.run(
                ["sysctl", "-n", "hw.pagesize"],
                check=True,
                capture_output=True,
                text=True,
                timeout=1,
            ).stdout
        )
        output = subprocess.run(
            ["vm_stat"],
            check=True,
            capture_output=True,
            text=True,
            timeout=1,
        ).stdout
        pages = 0
        for line in output.splitlines():
            label, separator, value = line.partition(":")
            if separator and label in {
                "Pages free",
                "Pages inactive",
                "Pages speculative",
                "Pages purgeable",
            }:
                pages += int(value.strip().rstrip("."))
        return pages * page_size / (1024**3)
    except (OSError, subprocess.SubprocessError, ValueError):
        return 0.0

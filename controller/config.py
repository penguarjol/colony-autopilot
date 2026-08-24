from __future__ import annotations

import tomllib
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any

from .models import ControllerMode


class ConfigError(ValueError):
    pass


@dataclass(frozen=True, slots=True)
class GovernorConfig:
    min_tps: float = 19.5
    max_tick_ms: float = 35.0
    min_available_ram_gib: float = 4.5
    max_load_per_cpu: float = 1.5


@dataclass(frozen=True, slots=True)
class PlannerConfig:
    enabled: bool = False
    endpoint: str = "http://127.0.0.1:11434/api/generate"
    model: str = "qwen2.5-coder:3b"
    temperature: float = 0.0
    num_thread: int = 2
    context: int = 1024
    keep_alive: int = 0
    timeout_seconds: float = 3.0
    cadence_seconds: float = 20 * 60


@dataclass(frozen=True, slots=True)
class BridgeConfig:
    transport: str = "spool"
    spool_path: Path = Path("controller/var/spool")
    socket_path: Path = Path("controller/var/bridge.sock")
    timeout_seconds: float = 3.0


@dataclass(frozen=True, slots=True)
class ControllerConfig:
    mode: ControllerMode = ControllerMode.OBSERVER
    interval_seconds: float = 30.0
    database_path: Path = Path("controller/var/controller.db")
    governor: GovernorConfig = field(default_factory=GovernorConfig)
    planner: PlannerConfig = field(default_factory=PlannerConfig)
    bridge: BridgeConfig = field(default_factory=BridgeConfig)
    grant_budget: int = 8
    allow_any_requested_item: bool = False


def _only(section: dict[str, Any], allowed: set[str], name: str) -> None:
    unknown = set(section) - allowed
    if unknown:
        raise ConfigError(f"unknown {name} keys: {', '.join(sorted(unknown))}")


def load_config(path: str | Path) -> ControllerConfig:
    config_path = Path(path)
    try:
        with config_path.open("rb") as stream:
            raw = tomllib.load(stream)
    except (OSError, tomllib.TOMLDecodeError) as exc:
        raise ConfigError(str(exc)) from exc

    _only(raw, {"controller", "governor", "planner", "bridge", "grants"}, "top-level")
    controller = raw.get("controller", {})
    governor = raw.get("governor", {})
    planner = raw.get("planner", {})
    bridge = raw.get("bridge", {})
    grants = raw.get("grants", {})
    _only(controller, {"mode", "interval_seconds", "database_path"}, "controller")
    _only(
        governor,
        {"min_tps", "max_tick_ms", "min_available_ram_gib", "max_load_per_cpu"},
        "governor",
    )
    _only(
        planner,
        {
            "enabled",
            "endpoint",
            "model",
            "temperature",
            "num_thread",
            "context",
            "keep_alive",
            "timeout_seconds",
            "cadence_seconds",
        },
        "planner",
    )
    _only(bridge, {"transport", "spool_path", "socket_path", "timeout_seconds"}, "bridge")
    _only(grants, {"budget", "allow_any_requested_item"}, "grants")

    try:
        result = ControllerConfig(
            mode=ControllerMode(controller.get("mode", "observer")),
            interval_seconds=float(controller.get("interval_seconds", 30.0)),
            database_path=Path(
                controller.get("database_path", "controller/var/controller.db")
            ),
            governor=GovernorConfig(**governor),
            planner=PlannerConfig(**planner),
            bridge=BridgeConfig(
                transport=str(bridge.get("transport", "spool")),
                spool_path=Path(bridge.get("spool_path", "controller/var/spool")),
                socket_path=Path(
                    bridge.get("socket_path", "controller/var/bridge.sock")
                ),
                timeout_seconds=float(bridge.get("timeout_seconds", 3.0)),
            ),
            grant_budget=int(grants.get("budget", 8)),
            allow_any_requested_item=grants.get(
                "allow_any_requested_item",
                False,
            ),
        )
    except (TypeError, ValueError) as exc:
        raise ConfigError(str(exc)) from exc
    _validate(result)
    return result


def _validate(config: ControllerConfig) -> None:
    if config.interval_seconds <= 0:
        raise ConfigError("interval_seconds must be positive")
    if not 0 < config.governor.min_tps <= 20:
        raise ConfigError("min_tps must be between 0 and 20")
    if config.governor.max_tick_ms <= 0:
        raise ConfigError("max_tick_ms must be positive")
    if config.governor.min_available_ram_gib < 0:
        raise ConfigError("min_available_ram_gib cannot be negative")
    if config.planner.temperature != 0:
        raise ConfigError("planner temperature must be 0 for determinism")
    if config.planner.num_thread != 2:
        raise ConfigError("planner num_thread must be 2")
    if config.planner.keep_alive != 0:
        raise ConfigError("planner keep_alive must be 0")
    if config.planner.cadence_seconds < config.interval_seconds:
        raise ConfigError("planner cadence must not be shorter than the controller loop")
    if not 256 <= config.planner.context <= 4096:
        raise ConfigError("planner context must be between 256 and 4096")
    if config.bridge.transport not in {"spool", "socket"}:
        raise ConfigError("bridge transport must be spool or socket")
    if config.bridge.timeout_seconds <= 1.0:
        raise ConfigError("bridge timeout must exceed the one-second poll interval")
    if config.grant_budget < 0:
        raise ConfigError("grant budget cannot be negative")
    if not isinstance(config.allow_any_requested_item, bool):
        raise ConfigError("allow_any_requested_item must be a boolean")

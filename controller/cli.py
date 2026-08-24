from __future__ import annotations

import argparse
import json
import signal
import sys
import time
from dataclasses import replace
from pathlib import Path
from typing import Sequence

from .config import ConfigError, ControllerConfig, load_config
from .governor import collect_host_metrics
from .ipc import Bridge, BridgeSnapshotProvider, SocketBridge, SpoolBridge
from .models import ActionBuilder, BridgeSnapshot, ControllerMode, Evidence
from .service import Controller
from .storage import Store


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(prog="python -m controller")
    parser.add_argument(
        "--config", type=Path, default=Path("controller/controller.toml")
    )
    parser.add_argument("--mode", choices=tuple(ControllerMode))
    commands = parser.add_subparsers(dest="command", required=True)
    start = commands.add_parser("start", help="run the controller loop")
    start.add_argument("--snapshot", type=Path)
    once = commands.add_parser("once", help="run one controller cycle")
    once.add_argument("--snapshot", type=Path)
    commands.add_parser("status", help="show persisted controller status")
    commands.add_parser("validate-config", help="validate configuration")
    return parser


def main(argv: Sequence[str] | None = None) -> int:
    args = build_parser().parse_args(argv)
    try:
        config = load_config(args.config)
    except ConfigError as exc:
        print(f"configuration error: {exc}", file=sys.stderr)
        return 2
    if args.mode:
        config = replace(config, mode=ControllerMode(args.mode))
    if args.command == "validate-config":
        print(f"valid: {args.config}")
        return 0

    store = Store(config.database_path)
    try:
        bridge = _make_bridge(config)
        if args.command == "status":
            return _status(config, store, bridge)
        if args.command == "once":
            return _run_once(config, store, bridge, args.snapshot)
        return _run_loop(config, store, bridge, args.snapshot)
    finally:
        store.close()


def _make_bridge(config: ControllerConfig) -> Bridge:
    if config.bridge.transport == "socket":
        return SocketBridge(
            config.bridge.socket_path, config.bridge.timeout_seconds
        )
    return SpoolBridge(config.bridge.spool_path)


def _run_once(
    config: ControllerConfig,
    store: Store,
    bridge: Bridge,
    snapshot_path: Path | None,
) -> int:
    try:
        controller = Controller(config, store, bridge)
        if snapshot_path is not None:
            player_id, evidence, metrics = read_snapshot(snapshot_path)
            result = controller.run_once(
                evidence, metrics, collect_host_metrics(), player_id
            )
        else:
            snapshot = BridgeSnapshotProvider(
                bridge, config.bridge.timeout_seconds
            ).read()
            result = controller.run_bridge_snapshot(
                snapshot, collect_host_metrics()
            )
    except (OSError, TimeoutError, ValueError, KeyError, json.JSONDecodeError) as exc:
        print(f"snapshot error: {exc}", file=sys.stderr)
        return 3
    print(
        json.dumps(
            {
                "state": result.state,
                "reason": result.reason,
                "action": result.action.to_json() if result.action else None,
            },
            sort_keys=True,
        )
    )
    return 0


def _run_loop(
    config: ControllerConfig,
    store: Store,
    bridge: Bridge,
    snapshot_path: Path | None,
) -> int:
    controller = Controller(config, store, bridge)
    provider = BridgeSnapshotProvider(bridge, config.bridge.timeout_seconds)
    stopping = False

    def stop(_signum: int, _frame: object) -> None:
        nonlocal stopping
        stopping = True

    previous = signal.signal(signal.SIGTERM, stop)
    try:
        while not stopping:
            started = time.monotonic()
            try:
                if snapshot_path is not None:
                    player_id, evidence, metrics = read_snapshot(snapshot_path)
                    controller.run_once(
                        evidence, metrics, collect_host_metrics(), player_id
                    )
                else:
                    controller.run_bridge_snapshot(
                        provider.read(), collect_host_metrics()
                    )
            except (
                OSError,
                TimeoutError,
                ValueError,
                KeyError,
                json.JSONDecodeError,
            ) as exc:
                store.audit("snapshot_error", {"error": str(exc)})
            remaining = config.interval_seconds - (time.monotonic() - started)
            if remaining > 0 and not stopping:
                time.sleep(remaining)
    except KeyboardInterrupt:
        return 0
    finally:
        signal.signal(signal.SIGTERM, previous)
    return 0


def _status(config: ControllerConfig, store: Store, bridge: Bridge) -> int:
    output = {"controller": store.status()}
    try:
        response = bridge.request(
            ActionBuilder.status(), config.bridge.timeout_seconds
        )
        output["bridge"] = {
            "status": response.status.value,
            "code": response.code,
            "message": response.message,
            "payload": response.payload,
        }
    except (OSError, TimeoutError, ValueError) as exc:
        output["bridge"] = {"status": "UNAVAILABLE", "message": str(exc)}
    print(json.dumps(output, sort_keys=True, default=str))
    return 0


def read_snapshot(path: Path) -> tuple[str, Evidence, BridgeSnapshot]:
    value = json.loads(path.read_text(encoding="utf-8"))
    if value.get("type") != "bridge.snapshot.v1":
        raise ValueError("unsupported snapshot type")
    return (
        str(value.get("player_id", "local-player")),
        Evidence.from_json(dict(value["evidence"])),
        BridgeSnapshot.from_json(dict(value["metrics"])),
    )

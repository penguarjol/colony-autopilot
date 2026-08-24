from __future__ import annotations

import argparse
import json
import time
from pathlib import Path
from typing import Any, Iterable

from .ipc import SpoolBridge
from .models import ActionBuilder


class AcceptanceError(RuntimeError):
    pass


def assert_healthy_runtime(
    status: dict[str, Any],
    snapshot: dict[str, Any],
) -> None:
    metrics = status.get("metrics")
    colonies = snapshot.get("colonies")
    checks = {
        "bridge enabled": status.get("enabled") is True,
        "bridge active": status.get("mode") == "ACTIVE",
        "bridge unpaused": status.get("paused") is False,
        "metrics present": isinstance(metrics, dict),
        "TPS healthy": isinstance(metrics, dict)
        and float(metrics.get("tps", 0)) >= 18.0,
        "no bridge failures": isinstance(metrics, dict)
        and int(metrics.get("failures", -1)) == 0,
        "offline progression active": status.get("offline_progress_active") is True,
        "offline lease held": int(status.get("offline_progress_leases", 0)) > 0,
        "offline chunks ticking": int(status.get("offline_ticking_chunks", 0)) > 0,
        "colony snapshot populated": isinstance(colonies, list) and bool(colonies),
    }
    failures = [name for name, passed in checks.items() if not passed]
    if failures:
        raise AcceptanceError("runtime acceptance failed: " + ", ".join(failures))


def assert_deadline_progress(
    before: dict[str, Any],
    after: dict[str, Any],
) -> None:
    before_jobs = _jobs_by_id(before)
    after_jobs = _jobs_by_id(after)
    failed = [
        work_order_id
        for work_order_id, job in after_jobs.items()
        if job.get("phase") == "FAILED"
    ]
    if failed:
        raise AcceptanceError(f"deadline jobs failed: {failed}")
    progressed = any(
        work_order_id not in after_jobs
        or after_jobs[work_order_id].get("phase") == "COMPLETED"
        or int(after_jobs[work_order_id].get("reconciliation_cursor", 0))
        > int(job.get("reconciliation_cursor", 0))
        for work_order_id, job in before_jobs.items()
    )
    if not before_jobs or not progressed:
        raise AcceptanceError("no construction deadline job progressed or completed")


def assert_request_completed(
    before: dict[str, Any],
    after: dict[str, Any],
    request_id: str,
) -> None:
    initial = {
        request["request_id"]: request
        for request in _requests(before)
        if isinstance(request.get("request_id"), str)
    }
    request = initial.get(request_id)
    if request is None:
        raise AcceptanceError(f"request was not present before dispatch: {request_id}")
    if request.get("request_origin") != "citizen":
        raise AcceptanceError("request is not identified as citizen-originated")
    if request.get("citizen_id") is None or request.get("deliverable") is not True:
        raise AcceptanceError("citizen request lacks deliverable ownership metadata")
    if any(request.get("request_id") == request_id for request in _requests(after)):
        raise AcceptanceError(f"request remains open after dispatch: {request_id}")


def _jobs_by_id(status: dict[str, Any]) -> dict[int, dict[str, Any]]:
    jobs = status.get("construction_jobs")
    if not isinstance(jobs, list):
        return {}
    return {
        int(job["work_order_id"]): job
        for job in jobs
        if isinstance(job, dict) and isinstance(job.get("work_order_id"), int)
    }


def _requests(snapshot: dict[str, Any]) -> Iterable[dict[str, Any]]:
    colonies = snapshot.get("colonies")
    if not isinstance(colonies, list):
        return
    for colony in colonies:
        buildings = colony.get("loaded_buildings", []) if isinstance(colony, dict) else []
        for building in buildings if isinstance(buildings, list) else []:
            requests = building.get("open_requests", []) if isinstance(building, dict) else []
            for request in requests if isinstance(requests, list) else []:
                if isinstance(request, dict):
                    yield request


def _read(bridge: SpoolBridge, timeout: float) -> tuple[dict[str, Any], dict[str, Any]]:
    status = bridge.request(ActionBuilder.status(), timeout).payload
    snapshot = bridge.request(ActionBuilder.snapshot(), timeout).payload
    return status, snapshot


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--spool", type=Path, required=True)
    parser.add_argument(
        "--scenario",
        choices=("health", "deadline", "request"),
        required=True,
    )
    parser.add_argument("--request-id")
    parser.add_argument("--wait-seconds", type=float, default=30.0)
    parser.add_argument("--timeout-seconds", type=float, default=3.0)
    args = parser.parse_args()
    bridge = SpoolBridge(args.spool)
    before_status, before_snapshot = _read(bridge, args.timeout_seconds)

    if args.scenario == "health":
        assert_healthy_runtime(before_status, before_snapshot)
    elif args.scenario == "deadline":
        time.sleep(args.wait_seconds)
        after_status, _ = _read(bridge, args.timeout_seconds)
        assert_deadline_progress(before_status, after_status)
    else:
        if not args.request_id:
            parser.error("--request-id is required for the request scenario")
        deadline = time.monotonic() + args.wait_seconds
        after_snapshot = before_snapshot
        print(json.dumps({
            "scenario": args.scenario,
            "status": "watching",
            "request_id": args.request_id,
        }), flush=True)
        while time.monotonic() < deadline:
            time.sleep(min(5.0, args.wait_seconds))
            _, after_snapshot = _read(bridge, args.timeout_seconds)
            if not any(
                request.get("request_id") == args.request_id
                for request in _requests(after_snapshot)
            ):
                break
        assert_request_completed(
            before_snapshot,
            after_snapshot,
            args.request_id,
        )

    print(json.dumps({"scenario": args.scenario, "status": "passed"}))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

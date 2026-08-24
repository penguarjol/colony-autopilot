from __future__ import annotations

import json
import os
import socket
import time
import uuid
from pathlib import Path
from typing import Protocol

from .models import (
    Action,
    ActionBuilder,
    BridgeRequest,
    BridgeResponse,
    BridgeSnapshot,
    ControllerSnapshot,
    ResponseStatus,
)


class Bridge(Protocol):
    def submit(self, action: Action) -> str: ...

    def poll_result(self, request_id: str) -> BridgeResponse | None: ...

    def request(self, action: Action, timeout_seconds: float) -> BridgeResponse: ...


class SpoolBridge:
    def __init__(self, root: str | Path) -> None:
        self._root = Path(root)
        self._requests = self._root / "requests"
        self._responses = self._root / "responses"
        self._rejected = self._root / "rejected"
        for path in (self._root, self._requests, self._responses, self._rejected):
            if path.is_symlink():
                raise OSError(f"IPC path must not be a symlink: {path}")
            path.mkdir(parents=True, exist_ok=True, mode=0o700)
            os.chmod(path, 0o700)

    def submit(self, action: Action) -> str:
        message = BridgeRequest.create(action)
        request_id = message.request_id
        target = self._requests / f"{request_id}.json"
        temporary = self._requests / f".{request_id}.{uuid.uuid4().hex}.tmp"
        temporary.write_text(_encode(message.to_json()), encoding="utf-8")
        os.chmod(temporary, 0o600)
        os.replace(temporary, target)
        return request_id

    def poll_result(self, request_id: str) -> BridgeResponse | None:
        response_path = self._responses / f"{request_id}.json"
        rejected_path = self._rejected / f"{request_id}.json"
        if rejected_path.exists():
            reason = rejected_path.read_text(encoding="utf-8")
            rejected_path.unlink()
            code, _, message = reason.partition(":")
            return BridgeResponse(
                request_id=request_id,
                status=ResponseStatus.REJECTED,
                payload={
                    "code": code.strip() or "INVALID_REQUEST",
                    "message": message.strip(),
                },
            )
        try:
            raw = response_path.read_text(encoding="utf-8")
        except FileNotFoundError:
            return None
        result = BridgeResponse.from_json(json.loads(raw))
        if result.request_id != request_id:
            raise ValueError("bridge result request_id mismatch")
        response_path.unlink()
        return result

    def request(self, action: Action, timeout_seconds: float) -> BridgeResponse:
        request_id = self.submit(action)
        deadline = time.monotonic() + timeout_seconds
        while time.monotonic() < deadline:
            result = self.poll_result(request_id)
            if result is not None:
                return result
            time.sleep(min(0.05, max(0.0, deadline - time.monotonic())))
        result = self.poll_result(request_id)
        if result is not None:
            return result
        (self._requests / f"{request_id}.json").unlink(missing_ok=True)
        raise TimeoutError(f"bridge request timed out: {request_id}")


class SocketBridge:
    def __init__(self, path: str | Path, timeout_seconds: float = 3.0) -> None:
        self._path = str(path)
        self._timeout = timeout_seconds
        self._results: dict[str, BridgeResponse] = {}

    def submit(self, action: Action) -> str:
        message = BridgeRequest.create(action)
        request_id = message.request_id
        with socket.socket(socket.AF_UNIX, socket.SOCK_STREAM) as client:
            client.settimeout(self._timeout)
            client.connect(self._path)
            client.sendall(_encode(message.to_json()).encode("utf-8") + b"\n")
            raw = _receive_line(client)
        result = BridgeResponse.from_json(json.loads(raw))
        if result.request_id != request_id:
            raise ValueError("bridge result request_id mismatch")
        self._results[request_id] = result
        return request_id

    def poll_result(self, request_id: str) -> BridgeResponse | None:
        return self._results.pop(request_id, None)

    def request(self, action: Action, timeout_seconds: float) -> BridgeResponse:
        request_id = self.submit(action)
        result = self.poll_result(request_id)
        if result is None:
            raise TimeoutError(f"bridge request timed out: {request_id}")
        return result


class BridgeSnapshotProvider:
    def __init__(self, bridge: Bridge, timeout_seconds: float) -> None:
        self._bridge = bridge
        self._timeout_seconds = timeout_seconds

    def read(self) -> ControllerSnapshot:
        status = self._bridge.request(
            ActionBuilder.status(), self._timeout_seconds
        )
        _require_completed(status)
        metrics = status.payload.get("metrics")
        if not isinstance(metrics, dict):
            metrics = {}
        tps = float(metrics.get("tps", 0.0))
        tick_value = float(
            metrics.get("tick_ms", metrics.get("tickMs", float("inf")))
        )

        snapshot = self._bridge.request(
            ActionBuilder.snapshot(), self._timeout_seconds
        )
        _require_completed(snapshot)
        colonies = snapshot.payload.get("colonies", [])
        if not isinstance(colonies, list) or not all(
            isinstance(colony, dict) for colony in colonies
        ):
            raise ValueError("bridge snapshot colonies must be objects")
        return ControllerSnapshot(
            enabled=bool(status.payload.get("enabled", False)),
            mode=str(status.payload.get("mode", "OBSERVER")),
            paused=bool(status.payload.get("paused", False)),
            metrics=BridgeSnapshot(tps, tick_value),
            colonies=tuple(colonies),
        )


def _require_completed(response: BridgeResponse) -> None:
    if response.status is not ResponseStatus.COMPLETED:
        raise ValueError(
            f"bridge request failed: {response.code} {response.message}".strip()
        )


def _receive_line(client: socket.socket, limit: int = 64 * 1024) -> str:
    chunks = bytearray()
    while len(chunks) < limit:
        chunk = client.recv(min(4096, limit - len(chunks)))
        if not chunk:
            break
        chunks.extend(chunk)
        if b"\n" in chunk:
            break
    if b"\n" not in chunks:
        raise ValueError("bridge response was not newline terminated")
    return bytes(chunks).split(b"\n", 1)[0].decode("utf-8")


def _encode(value: object) -> str:
    return json.dumps(value, sort_keys=True, separators=(",", ":"))

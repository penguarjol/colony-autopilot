from __future__ import annotations

import re
import time
from dataclasses import dataclass, field
from enum import StrEnum
from typing import Any
from uuid import UUID, uuid4


class ControllerMode(StrEnum):
    OBSERVER = "observer"
    DRY_RUN = "dry-run"
    ACTIVE = "active"


class Tier(StrEnum):
    WOOD = "wood"
    STONE = "stone"
    IRON = "iron"
    DIAMOND = "diamond"
    NETHER = "nether"
    ENDGAME = "endgame"


class ActionKind(StrEnum):
    STATUS = "status"
    SNAPSHOT = "snapshot"
    ASSIGN_BUILDER = "assign_builder"
    REQUEST_UPGRADE = "request_upgrade"
    GRANT = "grant"
    PAUSE = "pause"
    PLACE_BUILDING = "place_building"


class ResponseStatus(StrEnum):
    COMPLETED = "completed"
    REJECTED = "rejected"
    FAILED = "failed"


@dataclass(frozen=True, slots=True)
class BlockPosition:
    x: int
    y: int
    z: int

    def to_json(self) -> dict[str, int]:
        return {"x": self.x, "y": self.y, "z": self.z}


@dataclass(frozen=True, slots=True)
class Evidence:
    stats: dict[str, int] = field(default_factory=dict)
    advancements: set[str] = field(default_factory=set)
    inventory: dict[str, int] = field(default_factory=dict)
    dimensions: set[str] = field(default_factory=set)

    @classmethod
    def from_json(cls, value: dict[str, Any]) -> Evidence:
        return cls(
            stats={str(k): int(v) for k, v in value.get("stats", {}).items()},
            advancements={str(v) for v in value.get("advancements", [])},
            inventory={
                str(k).removeprefix("minecraft:"): int(v)
                for k, v in value.get("inventory", {}).items()
            },
            dimensions={str(v) for v in value.get("dimensions", [])},
        )


@dataclass(frozen=True, slots=True)
class BridgeSnapshot:
    tps: float
    tick_ms: float

    @classmethod
    def from_json(cls, value: dict[str, Any]) -> BridgeSnapshot:
        return cls(tps=float(value["tps"]), tick_ms=float(value["tick_ms"]))


@dataclass(frozen=True, slots=True)
class Action:
    kind: ActionKind
    payload: dict[str, Any] = field(default_factory=dict)
    rationale: str = ""
    idempotency_key: str = ""

    def to_wire_json(self) -> dict[str, Any]:
        expected = {
            ActionKind.STATUS: set(),
            ActionKind.SNAPSHOT: {"colony_id", "dimension"},
            ActionKind.ASSIGN_BUILDER: {
                "colony_id",
                "dimension",
                "work_order_id",
                "builder_pos",
                "actor_uuid",
            },
            ActionKind.REQUEST_UPGRADE: {
                "colony_id",
                "dimension",
                "building_pos",
                "builder_pos",
                "actor_uuid",
            },
            ActionKind.GRANT: {
                "colony_id",
                "dimension",
                "building_pos",
                "minecolonies_request_id",
                "item",
                "quantity",
                "actor_uuid",
            },
            ActionKind.PAUSE: {"ticks", "actor_uuid"},
            ActionKind.PLACE_BUILDING: {
                "colony_id",
                "dimension",
                "building_type",
                "position",
                "actor_uuid",
            },
        }
        allowed = expected[self.kind]
        present = {key for key, value in self.payload.items() if value is not None}
        if self.kind is ActionKind.SNAPSHOT:
            if present not in (set(), allowed):
                raise ValueError("snapshot target must be complete or absent")
        elif present != allowed:
            raise ValueError(f"{self.kind.value} payload fields are incomplete")
        return _json_value(
            {key: value for key, value in self.payload.items() if value is not None}
        )

    def to_json(self) -> dict[str, Any]:
        return {
            "kind": self.kind.value,
            "payload": _json_value(self.payload),
            "rationale": self.rationale,
            "idempotency_key": self.idempotency_key,
        }


class ActionBuilder:
    @staticmethod
    def status() -> Action:
        return Action(ActionKind.STATUS, rationale="periodic health poll")

    @staticmethod
    def snapshot(
        colony_id: int | None = None, dimension: str | None = None
    ) -> Action:
        if (colony_id is None) != (dimension is None):
            raise ValueError("snapshot colony_id and dimension must be provided together")
        return Action(
            ActionKind.SNAPSHOT,
            {"colony_id": colony_id, "dimension": dimension},
            "refresh colony evidence",
        )

    @staticmethod
    def request_upgrade(
        *,
        colony_id: int,
        dimension: str,
        building_pos: BlockPosition,
        builder_pos: BlockPosition,
        actor_uuid: UUID,
        idempotency_key: str = "",
    ) -> Action:
        if colony_id < 1 or not dimension:
            raise ValueError("upgrade target is invalid")
        return Action(
            ActionKind.REQUEST_UPGRADE,
            {
                "colony_id": colony_id,
                "dimension": dimension,
                "building_pos": building_pos,
                "builder_pos": builder_pos,
                "actor_uuid": actor_uuid,
            },
            "upgrade an idle existing building",
            idempotency_key,
        )

    @staticmethod
    def assign_builder(
        *,
        colony_id: int,
        dimension: str,
        work_order_id: int,
        builder_pos: BlockPosition,
        actor_uuid: UUID,
        idempotency_key: str = "",
    ) -> Action:
        return Action(
            ActionKind.ASSIGN_BUILDER,
            {
                "colony_id": colony_id,
                "dimension": dimension,
                "work_order_id": work_order_id,
                "builder_pos": builder_pos,
                "actor_uuid": actor_uuid,
            },
            "assign the compatible idle builder",
            idempotency_key,
        )

    @staticmethod
    def grant_targeted_open_request(
        *,
        colony_id: int,
        dimension: str,
        building_pos: BlockPosition,
        minecolonies_request_id: UUID,
        item: str,
        quantity: int,
        actor_uuid: UUID,
        idempotency_key: str = "",
    ) -> Action:
        if not 1 <= quantity <= 64:
            raise ValueError("grant quantity must be between 1 and 64")
        return Action(
            ActionKind.GRANT,
            {
                "colony_id": colony_id,
                "dimension": dimension,
                "building_pos": building_pos,
                "minecolonies_request_id": minecolonies_request_id,
                "item": item,
                "quantity": quantity,
                "actor_uuid": actor_uuid,
            },
            "fulfill one observed open request",
            idempotency_key,
        )

    @staticmethod
    def pause(
        *, ticks: int, actor_uuid: UUID, idempotency_key: str = ""
    ) -> Action:
        if not 0 <= ticks <= 72_000:
            raise ValueError("pause ticks must be between 0 and 72000")
        return Action(
            ActionKind.PAUSE,
            {"ticks": ticks, "actor_uuid": actor_uuid},
            "operator-requested pause",
            idempotency_key,
        )

    @staticmethod
    def place_building(
        *,
        colony_id: int,
        dimension: str,
        building_type: str,
        position: BlockPosition,
        actor_uuid: UUID,
        idempotency_key: str = "",
    ) -> Action:
        return Action(
            ActionKind.PLACE_BUILDING,
            {
                "colony_id": colony_id,
                "dimension": dimension,
                "building_type": building_type,
                "position": position,
                "actor_uuid": actor_uuid,
            },
            "typed request; building placement is disabled",
            idempotency_key,
        )


@dataclass(frozen=True, slots=True)
class BridgeRequest:
    request_id: str
    idempotency_key: str
    nonce: UUID
    issued_at_epoch_ms: int
    action: Action

    @classmethod
    def create(
        cls,
        action: Action,
        *,
        request_id: str | UUID | None = None,
        nonce: UUID | None = None,
        issued_at_epoch_ms: int | None = None,
    ) -> BridgeRequest:
        identifier = str(
            request_id
            or action.idempotency_key
            or f"{action.kind.value}-{uuid4().hex}"
        )
        if re.fullmatch(r"[A-Za-z0-9._-]{1,128}", identifier) is None:
            raise ValueError("request_id is not spool-safe")
        if not action.rationale.strip() or len(action.rationale) > 1_024:
            raise ValueError("rationale must contain 1 to 1024 characters")
        return cls(
            request_id=identifier,
            idempotency_key=identifier,
            nonce=nonce or uuid4(),
            issued_at_epoch_ms=(
                issued_at_epoch_ms
                if issued_at_epoch_ms is not None
                else int(time.time() * 1000)
            ),
            action=action,
        )

    def to_json(self) -> dict[str, Any]:
        return {
            "type": "controller.action.v1",
            "request_id": self.request_id,
            "idempotency_key": self.idempotency_key,
            "nonce": str(self.nonce),
            "issued_at_epoch_ms": self.issued_at_epoch_ms,
            "action": self.action.kind.value,
            "payload": self.action.to_wire_json(),
            "rationale": self.action.rationale,
        }


@dataclass(frozen=True, slots=True)
class BridgeResponse:
    request_id: str
    status: ResponseStatus
    payload: dict[str, Any]

    @property
    def code(self) -> str:
        return str(self.payload.get("code", ""))

    @property
    def message(self) -> str:
        return str(self.payload.get("message", ""))

    @classmethod
    def from_json(cls, value: dict[str, Any]) -> BridgeResponse:
        required = {"type", "request_id", "status"}
        if not required.issubset(value) or set(value) - (required | {"payload"}):
            raise ValueError("bridge response fields do not match protocol v1")
        if value["type"] != "bridge.result.v1":
            raise ValueError("unsupported bridge protocol version")
        payload = value.get("payload")
        if payload is not None and not isinstance(payload, dict):
            raise ValueError("bridge response payload must be an object or null")
        return cls(
            request_id=str(value["request_id"]),
            status=ResponseStatus(str(value["status"])),
            payload=dict(payload or {}),
        )

@dataclass(frozen=True, slots=True)
class ControllerSnapshot:
    enabled: bool
    mode: str
    paused: bool
    metrics: BridgeSnapshot
    colonies: tuple[dict[str, Any], ...]


def _json_value(value: Any) -> Any:
    if isinstance(value, BlockPosition):
        return value.to_json()
    if isinstance(value, UUID):
        return str(value)
    if isinstance(value, dict):
        return {str(key): _json_value(item) for key, item in value.items()}
    if isinstance(value, (list, tuple, set)):
        return [_json_value(item) for item in value]
    return value


@dataclass(frozen=True, slots=True)
class GovernorDecision:
    open: bool
    reason: str


@dataclass(frozen=True, slots=True)
class RunResult:
    state: str
    reason: str
    action: Action | None = None

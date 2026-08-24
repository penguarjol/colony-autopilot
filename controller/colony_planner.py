from __future__ import annotations

import hashlib
import json
from typing import Any, Iterable
from uuid import UUID

from .models import Action, ActionBuilder, BlockPosition, Evidence, Tier
from .progression import GrantPolicy, infer_tier
from .storage import Store


OWNER_EVIDENCE_FIELDS = {"stats", "advancements", "inventory", "dimensions"}
BUILDER_MARKER_FIELDS = {
    "building_type",
    "is_builder",
    "builder_idle",
    "compatible_work_order_ids",
    "position",
}
BUILDING_STATE_FIELDS = {
    "level",
    "maximum_level",
    "built",
    "pending_construction",
    "open_requests",
}
WORK_ORDER_FIELDS = {
    "id",
    "type",
    "location",
    "target_level",
    "claimed",
    "claimed_by",
}
OPEN_REQUEST_BASE_FIELDS = {
    "request_id",
    "state",
    "description",
    "display_items",
}
BOOTSTRAP_TYPES = (
    "minecolonies:builder",
    "minecolonies:townhall",
)
OPEN_REQUEST_STATES = {"OPEN", "REQUESTED", "IN_PROGRESS", "ASSIGNED"}
TOOL_MATERIAL_PREFERENCE = {
    "netherite": 0,
    "diamond": 1,
    "iron": 2,
    "stone": 3,
    "golden": 4,
    "wooden": 5,
}
TOOL_SUFFIXES = ("_axe", "_hoe", "_pickaxe", "_shovel", "_sword")


class ColonyPlanner:
    def __init__(self, grant_policy: GrantPolicy, store: Store) -> None:
        self._grant_policy = grant_policy
        self._store = store

    def persist_owner_evidence(
        self, colonies: Iterable[dict[str, Any]]
    ) -> None:
        for colony in colonies:
            owner_uuid = _owner_uuid(colony)
            evidence_json = colony.get("owner_evidence")
            if owner_uuid is None or not _valid_evidence_json(evidence_json):
                continue
            try:
                evidence = Evidence.from_json(evidence_json)
            except (TypeError, ValueError):
                continue
            owner_id = str(owner_uuid)
            spent = self._store.grant_spent(owner_id)
            self._store.save_progression(
                owner_id,
                infer_tier(evidence),
                _evidence_json(evidence),
                spent,
            )

    def plan(self, colonies: Iterable[dict[str, Any]]) -> Action | None:
        ordered = sorted(
            (colony for colony in colonies if isinstance(colony, dict)),
            key=lambda colony: _integer(colony.get("id")) or 2**31,
        )
        for colony in ordered:
            context = _colony_context(colony)
            if context is None:
                continue
            progression = self._store.progression(str(context.owner_uuid))
            if progression is None:
                continue
            try:
                evidence = Evidence.from_json(
                    json.loads(str(progression["evidence"]))
                )
                tier = Tier(str(progression["tier"]))
            except (json.JSONDecodeError, TypeError, ValueError):
                continue
            spent = self._store.grant_spent(str(context.owner_uuid))

            action = self._assign_builder(context)
            if action is not None:
                return action
            action = self._start_bootstrap_building(context)
            if action is not None:
                return action
            action = self._grant_open_request(context, evidence, tier, spent)
            if action is not None:
                return action
            action = self._upgrade_existing_building(context)
            if action is not None:
                return action
        return None

    def _assign_builder(self, context: _ColonyContext) -> Action | None:
        builders = _idle_builders(context.buildings)
        work_orders = sorted(
            (
                order
                for order in context.work_orders
                if isinstance(order, dict)
                and order.get("claimed") is False
                and (_integer(order.get("id")) or 0) > 0
            ),
            key=lambda order: int(order["id"]),
        )
        for order in work_orders:
            work_order_id = int(order["id"])
            for builder in builders:
                compatible = builder.get("compatible_work_order_ids")
                if not isinstance(compatible, list) or work_order_id not in compatible:
                    continue
                builder_position = _position(builder.get("position"))
                if builder_position is None:
                    continue
                return ActionBuilder.assign_builder(
                    colony_id=context.colony_id,
                    dimension=context.dimension,
                    work_order_id=work_order_id,
                    builder_pos=builder_position,
                    actor_uuid=context.owner_uuid,
                    idempotency_key=_key(
                        "assign",
                        context.colony_id,
                        work_order_id,
                        builder_position.to_json(),
                    ),
                )
        return None

    def _start_bootstrap_building(
        self, context: _ColonyContext
    ) -> Action | None:
        builders = _idle_builders(context.buildings)
        if not builders:
            return None
        builder_position = _position(builders[0].get("position"))
        if builder_position is None:
            return None
        targets = sorted(
            (
                building
                for building in context.buildings
                if isinstance(building, dict)
                and building.get("building_type") in BOOTSTRAP_TYPES
                and _integer(building.get("level")) == 0
                and building.get("built") is False
                and building.get("pending_construction") is False
                and _position(building.get("position")) is not None
            ),
            key=lambda building: (
                BOOTSTRAP_TYPES.index(str(building["building_type"])),
                _position_key(building),
            ),
        )
        if not targets:
            return None
        target = targets[0]
        target_position = _position(target["position"])
        return ActionBuilder.request_upgrade(
            colony_id=context.colony_id,
            dimension=context.dimension,
            building_pos=target_position,
            builder_pos=builder_position,
            actor_uuid=context.owner_uuid,
            idempotency_key=_key(
                "bootstrap",
                context.colony_id,
                target_position.to_json(),
                1,
            ),
        )

    def _grant_open_request(
        self,
        context: _ColonyContext,
        evidence: Evidence,
        tier: Tier,
        spent: int,
    ) -> Action | None:
        for building in sorted(context.buildings, key=_position_key):
            if not isinstance(building, dict):
                continue
            building_position = _position(building.get("position"))
            requests = building.get("open_requests")
            if building_position is None or not isinstance(requests, list):
                continue
            for open_request in sorted(
                (value for value in requests if isinstance(value, dict)),
                key=lambda value: (
                    value.get("request_origin") != "citizen",
                    str(value.get("state", "")).upper() != "IN_PROGRESS",
                    str(value.get("request_id", "")),
                ),
            ):
                action = self._grant_for_request(
                    context,
                    building_position,
                    open_request,
                    evidence,
                    tier,
                    spent,
                )
                if action is not None:
                    return action
        return None

    def _grant_for_request(
        self,
        context: _ColonyContext,
        building_position: BlockPosition,
        open_request: dict[str, Any],
        evidence: Evidence,
        tier: Tier,
        spent: int,
    ) -> Action | None:
        if str(open_request.get("state", "")).upper() not in OPEN_REQUEST_STATES:
            return None
        display_items = open_request.get("display_items")
        if not isinstance(display_items, list) or not display_items:
            return None
        if len(display_items) != 1 and not self._grant_policy.allow_any_requested_item:
            return None
        minimum = _integer(open_request.get("minimum_count"))
        requested = _integer(open_request.get("requested_count"))
        if (
            minimum is None
            or requested is None
            or minimum < 1
            or minimum > requested
            or minimum > 64
        ):
            return None
        matching_items = sorted(
            (
                str(display_item["item"])
                for display_item in display_items
                if isinstance(display_item, dict)
                and isinstance(display_item.get("item"), str)
                and display_item["item"]
                and (_integer(display_item.get("count")) or 0) >= minimum
            ),
            key=_requested_item_preference,
        )
        if not matching_items:
            return None
        item = matching_items[0]
        try:
            request_id = UUID(str(open_request["request_id"]))
        except (KeyError, ValueError):
            return None
        decision = self._grant_policy.evaluate(
            item,
            minimum,
            tier,
            evidence,
            spent,
            targeted_request=True,
        )
        if not decision.allowed:
            return None
        return ActionBuilder.grant_targeted_open_request(
            colony_id=context.colony_id,
            dimension=context.dimension,
            building_pos=building_position,
            minecolonies_request_id=request_id,
            item=item,
            quantity=minimum,
            actor_uuid=context.owner_uuid,
            idempotency_key=_key(
                "grant",
                context.colony_id,
                building_position.to_json(),
                f"request:{request_id}",
                item,
                minimum,
            ),
        )

    def _upgrade_existing_building(
        self, context: _ColonyContext
    ) -> Action | None:
        if context.work_orders or _has_open_requests(context.buildings):
            return None
        if any(
            not isinstance(building, dict)
            or building.get("pending_construction") is not False
            for building in context.buildings
        ):
            return None
        builders = _idle_builders(context.buildings)
        if not builders:
            return None
        builder_position = _position(builders[0].get("position"))
        if builder_position is None:
            return None
        targets = sorted(
            (
                building
                for building in context.buildings
                if isinstance(building, dict)
                and building.get("is_builder") is False
                and building.get("built") is True
                and (_integer(building.get("level")) or 0) >= 1
                and (_integer(building.get("maximum_level")) or 0)
                > (_integer(building.get("level")) or 0)
                and _position(building.get("position")) is not None
            ),
            key=lambda building: (
                int(building["level"]),
                str(building.get("building_type", "")),
                _position_key(building),
            ),
        )
        if not targets:
            return None
        target = targets[0]
        target_position = _position(target["position"])
        target_level = int(target["level"]) + 1
        return ActionBuilder.request_upgrade(
            colony_id=context.colony_id,
            dimension=context.dimension,
            building_pos=target_position,
            builder_pos=builder_position,
            actor_uuid=context.owner_uuid,
            idempotency_key=_key(
                "upgrade",
                context.colony_id,
                target_position.to_json(),
                target_level,
            ),
        )


class _ColonyContext:
    __slots__ = (
        "colony_id",
        "dimension",
        "owner_uuid",
        "buildings",
        "work_orders",
    )

    def __init__(
        self,
        colony_id: int,
        dimension: str,
        owner_uuid: UUID,
        buildings: list[Any],
        work_orders: list[Any],
    ) -> None:
        self.colony_id = colony_id
        self.dimension = dimension
        self.owner_uuid = owner_uuid
        self.buildings = buildings
        self.work_orders = work_orders


def _colony_context(colony: dict[str, Any]) -> _ColonyContext | None:
    colony_id = _integer(colony.get("id"))
    dimension = colony.get("dimension")
    buildings = colony.get("loaded_buildings")
    work_orders = colony.get("work_orders")
    if (
        colony.get("active") is not True
        or colony.get("under_attack") is not False
        or colony_id is None
        or colony_id < 1
        or not isinstance(dimension, str)
        or not dimension
        or not isinstance(buildings, list)
        or not isinstance(work_orders, list)
        or not _valid_snapshot_shapes(buildings, work_orders)
    ):
        return None
    owner_uuid = _owner_uuid(colony)
    if owner_uuid is None:
        return None
    return _ColonyContext(
        colony_id, dimension, owner_uuid, buildings, work_orders
    )


def _owner_uuid(colony: dict[str, Any]) -> UUID | None:
    try:
        return UUID(str(colony["owner_uuid"]))
    except (KeyError, ValueError):
        return None


def _valid_snapshot_shapes(buildings: list[Any], work_orders: list[Any]) -> bool:
    for building in buildings:
        if (
            not isinstance(building, dict)
            or not (BUILDER_MARKER_FIELDS | BUILDING_STATE_FIELDS).issubset(building)
            or _position(building.get("position")) is None
            or not isinstance(building.get("open_requests"), list)
        ):
            return False
        for open_request in building["open_requests"]:
            if (
                not isinstance(open_request, dict)
                or not OPEN_REQUEST_BASE_FIELDS.issubset(open_request)
            ):
                return False
    return all(
        isinstance(order, dict)
        and WORK_ORDER_FIELDS.issubset(order)
        and _position(order.get("location")) is not None
        for order in work_orders
    )


def _idle_builders(buildings: list[Any]) -> list[dict[str, Any]]:
    return sorted(
        (
            building
            for building in buildings
            if isinstance(building, dict)
            and BUILDER_MARKER_FIELDS.issubset(building)
            and building.get("is_builder") is True
            and building.get("builder_idle") is True
            and isinstance(building.get("compatible_work_order_ids"), list)
            and _position(building.get("position")) is not None
        ),
        key=_position_key,
    )


def _has_open_requests(buildings: list[Any]) -> bool:
    for building in buildings:
        if not isinstance(building, dict):
            return True
        requests = building.get("open_requests")
        if not isinstance(requests, list):
            return True
        if requests:
            return True
    return False


def _valid_evidence_json(value: Any) -> bool:
    return isinstance(value, dict) and OWNER_EVIDENCE_FIELDS.issubset(value)


def _position(value: Any) -> BlockPosition | None:
    if not isinstance(value, dict) or set(value) != {"x", "y", "z"}:
        return None
    coordinates = tuple(_integer(value.get(axis)) for axis in ("x", "y", "z"))
    if any(coordinate is None for coordinate in coordinates):
        return None
    return BlockPosition(*coordinates)


def _position_key(building: Any) -> tuple[int, int, int]:
    if not isinstance(building, dict):
        return (2**31, 2**31, 2**31)
    value = _position(building.get("position"))
    return (value.x, value.y, value.z) if value else (2**31, 2**31, 2**31)


def _integer(value: Any) -> int | None:
    return value if isinstance(value, int) and not isinstance(value, bool) else None


def _requested_item_preference(item: str) -> tuple[bool, int, str]:
    namespace, separator, path = item.partition(":")
    tool_rank = len(TOOL_MATERIAL_PREFERENCE)
    if separator and any(path.endswith(suffix) for suffix in TOOL_SUFFIXES):
        material = path.split("_", 1)[0]
        tool_rank = TOOL_MATERIAL_PREFERENCE.get(material, tool_rank)
    return (namespace != "minecraft", tool_rank, item)


def _key(prefix: str, *parts: Any) -> str:
    canonical = json.dumps(parts, sort_keys=True, separators=(",", ":"), default=str)
    digest = hashlib.sha256(canonical.encode("utf-8")).hexdigest()[:24]
    return f"{prefix}-{digest}"


def _evidence_json(evidence: Evidence) -> dict[str, object]:
    return {
        "stats": evidence.stats,
        "advancements": sorted(evidence.advancements),
        "inventory": evidence.inventory,
        "dimensions": sorted(evidence.dimensions),
    }

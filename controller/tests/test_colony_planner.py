from __future__ import annotations

import tempfile
import unittest
from pathlib import Path

from controller.colony_planner import ColonyPlanner
from controller.config import ControllerConfig
from controller.governor import HostMetrics
from controller.ipc import SpoolBridge
from controller.models import (
    ActionKind,
    BridgeSnapshot,
    ControllerMode,
    ControllerSnapshot,
)
from controller.progression import GrantPolicy
from controller.service import Controller
from controller.storage import Store


OWNER_UUID = "591e02e4-5297-4b20-9105-690748338427"
REQUEST_UUID = "1d6514f4-17b9-4f06-ad27-d3c199e9bf55"


def position(x: int, z: int) -> dict[str, int]:
    return {"x": x, "y": 64, "z": z}


def builder(
    x: int = 100,
    z: int = 200,
    *,
    compatible: tuple[int, ...] = (),
    idle: bool = True,
) -> dict[str, object]:
    return {
        "position": position(x, z),
        "name": "Builder Hut",
        "building_type": "minecolonies:builder",
        "is_builder": True,
        "builder_idle": idle,
        "compatible_work_order_ids": list(compatible),
        "level": 2,
        "maximum_level": 5,
        "built": True,
        "pending_construction": False,
        "open_requests": [],
    }


def building(
    building_type: str,
    x: int,
    z: int,
    *,
    level: int,
    built: bool,
    pending: bool = False,
    requests: list[dict[str, object]] | None = None,
) -> dict[str, object]:
    return {
        "position": position(x, z),
        "name": building_type,
        "building_type": building_type,
        "is_builder": False,
        "builder_idle": False,
        "compatible_work_order_ids": [],
        "level": level,
        "maximum_level": 5,
        "built": built,
        "pending_construction": pending,
        "open_requests": requests or [],
    }


def colony(
    *,
    active: bool = True,
    under_attack: bool = False,
    owner_uuid: str | None = OWNER_UUID,
    buildings: list[dict[str, object]] | None = None,
    work_orders: list[dict[str, object]] | None = None,
) -> dict[str, object]:
    value: dict[str, object] = {
        "id": 7,
        "name": "Test Colony",
        "dimension": "minecraft:overworld",
        "active": active,
        "under_attack": under_attack,
        "loaded_buildings": buildings or [],
        "work_orders": work_orders or [],
        "owner_evidence": {
            "stats": {},
            "advancements": [],
            "inventory": {"minecraft:oak_log": 4},
            "dimensions": ["minecraft:overworld"],
        },
    }
    if owner_uuid is not None:
        value["owner_uuid"] = owner_uuid
    return value


class ColonyPlannerTests(unittest.TestCase):
    def setUp(self) -> None:
        self.directory = tempfile.TemporaryDirectory()
        self.store = Store(Path(self.directory.name) / "state.db")
        self.planner = ColonyPlanner(GrantPolicy(default_budget=8), self.store)

    def tearDown(self) -> None:
        self.store.close()
        self.directory.cleanup()

    def persist_owner(self, value: dict[str, object]) -> None:
        self.planner.persist_owner_evidence((value,))

    def test_assign_builder_has_priority_and_exact_targets(self) -> None:
        open_request = {
            "request_id": REQUEST_UUID,
            "state": "OPEN",
            "description": "Oak logs",
            "minimum_count": 2,
            "requested_count": 8,
            "display_items": [{"item": "minecraft:oak_log", "count": 8}],
        }
        value = colony(
            buildings=[
                builder(compatible=(42,)),
                building(
                    "minecolonies:townhall", 120, 210, level=0, built=False
                ),
                building(
                    "minecolonies:lumberjack",
                    140,
                    230,
                    level=1,
                    built=True,
                    requests=[open_request],
                ),
            ],
            work_orders=[
                {
                    "id": 42,
                    "type": "BUILD",
                    "location": position(120, 210),
                    "target_level": 1,
                    "claimed": False,
                    "claimed_by": None,
                }
            ],
        )
        self.persist_owner(value)

        action = self.planner.plan((value,))

        self.assertEqual(action.kind, ActionKind.ASSIGN_BUILDER)
        self.assertEqual(action.payload["work_order_id"], 42)
        self.assertEqual(action.payload["builder_pos"].to_json(), position(100, 200))
        self.assertEqual(str(action.payload["actor_uuid"]), OWNER_UUID)

    def test_level_zero_builder_hut_starts_before_town_hall(self) -> None:
        open_request = {
            "request_id": REQUEST_UUID,
            "state": "OPEN",
            "description": "Oak logs",
            "minimum_count": 2,
            "requested_count": 8,
            "display_items": [{"item": "minecraft:oak_log", "count": 8}],
        }
        value = colony(
            buildings=[
                builder(),
                building("minecolonies:builder", 130, 220, level=0, built=False),
                building("minecolonies:townhall", 120, 210, level=0, built=False),
                building(
                    "minecolonies:lumberjack",
                    140,
                    230,
                    level=1,
                    built=True,
                    requests=[open_request],
                ),
            ]
        )
        self.persist_owner(value)

        action = self.planner.plan((value,))

        self.assertEqual(action.kind, ActionKind.REQUEST_UPGRADE)
        self.assertEqual(action.payload["building_pos"].to_json(), position(130, 220))
        self.assertEqual(action.payload["builder_pos"].to_json(), position(100, 200))

    def test_pickup_request_without_item_counts_does_not_block_bootstrap(self) -> None:
        bootstrap_builder = builder(133, -15)
        bootstrap_builder.update(
            {
                "level": 0,
                "built": False,
                "open_requests": [
                    {
                        "request_id": REQUEST_UUID,
                        "state": "IN_PROGRESS",
                        "description": "Pickup",
                        "display_items": [],
                    }
                ],
            }
        )
        value = colony(
            buildings=[
                building(
                    "minecolonies:townhall", 105, 8, level=0, built=False
                ),
                bootstrap_builder,
            ]
        )
        self.persist_owner(value)

        action = self.planner.plan((value,))

        self.assertEqual(action.kind, ActionKind.REQUEST_UPGRADE)
        self.assertEqual(action.payload["building_pos"].to_json(), position(133, -15))

    def test_grant_targets_one_concrete_open_request(self) -> None:
        request = {
            "request_id": REQUEST_UUID,
            "state": "OPEN",
            "description": "Oak logs",
            "minimum_count": 2,
            "requested_count": 8,
            "display_items": [{"item": "minecraft:oak_log", "count": 8}],
        }
        value = colony(
            buildings=[
                builder(),
                building(
                    "minecolonies:lumberjack",
                    120,
                    210,
                    level=1,
                    built=True,
                    requests=[request],
                ),
            ]
        )
        self.persist_owner(value)

        action = self.planner.plan((value,))

        self.assertEqual(action.kind, ActionKind.GRANT)
        self.assertEqual(str(action.payload["minecolonies_request_id"]), REQUEST_UUID)
        self.assertEqual(action.payload["building_pos"].to_json(), position(120, 210))
        self.assertEqual(action.payload["item"], "minecraft:oak_log")
        self.assertEqual(action.payload["quantity"], 2)

    def test_grant_prioritizes_a_blocked_citizen_request(self) -> None:
        planner = ColonyPlanner(
            GrantPolicy(default_budget=64, allow_any_requested_item=True),
            self.store,
        )
        building_request = {
            "request_id": "00000000-0000-0000-0000-000000000001",
            "state": "OPEN",
            "description": "Building logs",
            "minimum_count": 2,
            "requested_count": 8,
            "display_items": [{"item": "minecraft:oak_log", "count": 8}],
            "request_origin": "building",
            "deliverable": True,
        }
        citizen_request = {
            "request_id": "ffffffff-ffff-ffff-ffff-ffffffffffff",
            "state": "IN_PROGRESS",
            "description": "Worker shovel",
            "minimum_count": 1,
            "requested_count": 1,
            "display_items": [{"item": "minecraft:stone_shovel", "count": 1}],
            "citizen_id": 4,
            "request_origin": "citizen",
            "deliverable": True,
        }
        value = colony(
            buildings=[
                builder(),
                building(
                    "minecolonies:lumberjack",
                    120,
                    210,
                    level=1,
                    built=True,
                    requests=[building_request, citizen_request],
                ),
            ]
        )
        self.persist_owner(value)

        action = planner.plan((value,))

        self.assertEqual(
            str(action.payload["minecolonies_request_id"]),
            citizen_request["request_id"],
        )

    def test_unrestricted_grant_chooses_vanilla_item_from_request_options(
        self,
    ) -> None:
        planner = ColonyPlanner(
            GrantPolicy(default_budget=64, allow_any_requested_item=True),
            self.store,
        )
        request = {
            "request_id": REQUEST_UUID,
            "state": "IN_PROGRESS",
            "description": "Shears",
            "minimum_count": 1,
            "requested_count": 1,
            "display_items": [
                {"item": "betternether:cincinnasite_shears", "count": 1},
                {"item": "minecraft:shears", "count": 1},
            ],
        }
        value = colony(
            buildings=[
                builder(),
                building(
                    "minecolonies:builder",
                    133,
                    -15,
                    level=0,
                    built=False,
                    pending=True,
                    requests=[request],
                ),
            ]
        )
        planner.persist_owner_evidence((value,))

        action = planner.plan((value,))

        self.assertEqual(action.kind, ActionKind.GRANT)
        self.assertEqual(action.payload["item"], "minecraft:shears")
        self.assertEqual(action.payload["quantity"], 1)

    def test_unrestricted_grant_chooses_best_accepted_tool_tier(self) -> None:
        planner = ColonyPlanner(
            GrantPolicy(default_budget=64, allow_any_requested_item=True),
            self.store,
        )
        request = {
            "request_id": REQUEST_UUID,
            "state": "IN_PROGRESS",
            "description": "Pickaxe",
            "minimum_count": 1,
            "requested_count": 1,
            "display_items": [
                {"item": "minecraft:wooden_pickaxe", "count": 1},
                {"item": "minecraft:iron_pickaxe", "count": 1},
                {"item": "minecraft:diamond_pickaxe", "count": 1},
                {"item": "minecraft:netherite_pickaxe", "count": 1},
            ],
        }
        value = colony(
            buildings=[
                builder(),
                building(
                    "minecolonies:builder",
                    133,
                    -15,
                    level=0,
                    built=False,
                    pending=True,
                    requests=[request],
                ),
            ]
        )
        planner.persist_owner_evidence((value,))

        action = planner.plan((value,))

        self.assertEqual(action.kind, ActionKind.GRANT)
        self.assertEqual(action.payload["item"], "minecraft:netherite_pickaxe")

    def test_recreated_builder_request_gets_a_new_idempotency_key(
        self,
    ) -> None:
        planner = ColonyPlanner(
            GrantPolicy(default_budget=64, allow_any_requested_item=True),
            self.store,
        )
        request = {
            "request_id": REQUEST_UUID,
            "state": "IN_PROGRESS",
            "description": "Shears",
            "minimum_count": 1,
            "requested_count": 1,
            "display_items": [{"item": "minecraft:shears", "count": 1}],
        }
        active_builder = builder(133, -15, idle=False)
        active_builder.update(
            {
                "level": 0,
                "built": False,
                "pending_construction": True,
                "open_requests": [request],
            }
        )
        value = colony(
            buildings=[active_builder],
            work_orders=[
                {
                    "id": 1,
                    "type": "BUILD",
                    "location": position(133, -15),
                    "target_level": 1,
                    "claimed": True,
                    "claimed_by": position(133, -15),
                }
            ],
        )
        planner.persist_owner_evidence((value,))

        first = planner.plan((value,))
        request["request_id"] = "80af04e5-d265-44d4-889e-3e2ff9a3f53e"
        second = planner.plan((value,))

        self.assertNotEqual(first.idempotency_key, second.idempotency_key)

    def test_persisted_evidence_supports_offline_owner_actions(self) -> None:
        self.persist_owner(colony(buildings=[builder()]))
        request = {
            "request_id": REQUEST_UUID,
            "state": "OPEN",
            "description": "Oak logs",
            "minimum_count": 2,
            "requested_count": 8,
            "display_items": [{"item": "minecraft:oak_log", "count": 8}],
        }
        assign_snapshot = colony(
            buildings=[builder(compatible=(42,))],
            work_orders=[
                {
                    "id": 42,
                    "type": "BUILD",
                    "location": position(120, 210),
                    "target_level": 1,
                    "claimed": False,
                    "claimed_by": None,
                }
            ],
        )
        build_snapshot = colony(
            buildings=[
                builder(),
                building(
                    "minecolonies:townhall", 120, 210, level=0, built=False
                ),
            ]
        )
        grant_snapshot = colony(
            buildings=[
                builder(),
                building(
                    "minecolonies:lumberjack",
                    120,
                    210,
                    level=1,
                    built=True,
                    requests=[request],
                ),
            ]
        )
        for value in (assign_snapshot, build_snapshot, grant_snapshot):
            value.pop("owner_evidence")

        self.assertEqual(
            self.planner.plan((assign_snapshot,)).kind,
            ActionKind.ASSIGN_BUILDER,
        )
        self.assertEqual(
            self.planner.plan((build_snapshot,)).kind,
            ActionKind.REQUEST_UPGRADE,
        )
        self.assertEqual(
            self.planner.plan((grant_snapshot,)).kind,
            ActionKind.GRANT,
        )

    def test_never_seen_offline_owner_fails_closed(self) -> None:
        value = colony(
            owner_uuid="dbb1d9f8-c945-4b45-8f67-7c8177f1f1a9",
            buildings=[builder(compatible=(42,))],
            work_orders=[
                {
                    "id": 42,
                    "type": "BUILD",
                    "location": position(120, 210),
                    "target_level": 1,
                    "claimed": False,
                    "claimed_by": None,
                }
            ],
        )
        value.pop("owner_evidence")

        self.assertIsNone(self.planner.plan((value,)))

    def test_existing_upgrade_requires_no_blockers(self) -> None:
        value = colony(
            buildings=[
                builder(),
                building(
                    "minecolonies:lumberjack", 120, 210, level=1, built=True
                ),
            ]
        )
        self.persist_owner(value)

        action = self.planner.plan((value,))

        self.assertEqual(action.kind, ActionKind.REQUEST_UPGRADE)
        self.assertEqual(action.payload["building_pos"].to_json(), position(120, 210))

    def test_no_action_when_inactive_under_attack_or_owner_missing(self) -> None:
        missing_evidence = colony(buildings=[builder(compatible=(42,))])
        missing_evidence.pop("owner_evidence")
        candidates = (
            colony(active=False, buildings=[builder(compatible=(42,))]),
            colony(under_attack=True, buildings=[builder(compatible=(42,))]),
            colony(owner_uuid=None, buildings=[builder(compatible=(42,))]),
            missing_evidence,
        )
        for value in candidates:
            with self.subTest(value=value):
                self.persist_owner(value)
                self.assertIsNone(self.planner.plan((value,)))

    def test_no_arbitrary_grant_or_upgrade_around_open_blocker(self) -> None:
        ambiguous_request = {
            "request_id": REQUEST_UUID,
            "state": "OPEN",
            "description": "Choose an item",
            "minimum_count": 1,
            "requested_count": 4,
            "display_items": [
                {"item": "minecraft:oak_log", "count": 4},
                {"item": "minecraft:spruce_log", "count": 4},
            ],
        }
        value = colony(
            buildings=[
                builder(),
                building(
                    "minecolonies:lumberjack",
                    120,
                    210,
                    level=1,
                    built=True,
                    requests=[ambiguous_request],
                ),
            ]
        )
        self.persist_owner(value)

        self.assertIsNone(self.planner.plan((value,)))

    def test_missing_builder_markers_fail_closed(self) -> None:
        incomplete_builder = builder(compatible=(42,))
        incomplete_builder.pop("compatible_work_order_ids")
        value = colony(
            buildings=[incomplete_builder],
            work_orders=[
                {
                    "id": 42,
                    "type": "BUILD",
                    "location": position(120, 210),
                    "target_level": 1,
                    "claimed": False,
                    "claimed_by": None,
                }
            ],
        )
        self.persist_owner(value)

        self.assertIsNone(self.planner.plan((value,)))


class ColonyControllerIntegrationTests(unittest.TestCase):
    def test_unrestricted_targeted_request_is_dispatched(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            store = Store(Path(directory) / "state.db")
            spool = Path(directory) / "spool"
            config = ControllerConfig(
                mode=ControllerMode.ACTIVE,
                grant_budget=512,
                allow_any_requested_item=True,
            )
            controller = Controller(config, store, SpoolBridge(spool))
            request = {
                "request_id": REQUEST_UUID,
                "state": "IN_PROGRESS",
                "description": "Shears",
                "minimum_count": 1,
                "requested_count": 1,
                "display_items": [
                    {"item": "betternether:cincinnasite_shears", "count": 1},
                    {"item": "minecraft:shears", "count": 1},
                ],
            }
            value = colony(
                buildings=[
                    builder(),
                    building(
                        "minecolonies:builder",
                        133,
                        -15,
                        level=0,
                        built=False,
                        pending=True,
                        requests=[request],
                    ),
                ]
            )
            snapshot = ControllerSnapshot(
                enabled=True,
                mode="ACTIVE",
                paused=False,
                metrics=BridgeSnapshot(20.0, 10.0),
                colonies=(value,),
            )

            result = controller.run_bridge_snapshot(
                snapshot,
                HostMetrics(8.0, 0.1),
            )

            self.assertEqual(result.state, "waiting")
            self.assertEqual(result.action.kind, ActionKind.GRANT)
            self.assertEqual(result.action.payload["item"], "minecraft:shears")
            self.assertEqual(len(list((spool / "requests").glob("*.json"))), 1)
            store.close()

    def test_observer_bridge_returns_proposal_without_dispatch(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            store = Store(Path(directory) / "state.db")
            spool = Path(directory) / "spool"
            controller = Controller(
                ControllerConfig(mode=ControllerMode.OBSERVER),
                store,
                SpoolBridge(spool),
            )
            value = colony(
                buildings=[builder(compatible=(42,))],
                work_orders=[
                    {
                        "id": 42,
                        "type": "BUILD",
                        "location": position(120, 210),
                        "target_level": 1,
                        "claimed": False,
                        "claimed_by": None,
                    }
                ],
            )
            snapshot = ControllerSnapshot(
                enabled=False,
                mode="OBSERVER",
                paused=False,
                metrics=BridgeSnapshot(20.0, 10.0),
                colonies=(value,),
            )

            result = controller.run_bridge_snapshot(
                snapshot, HostMetrics(8.0, 0.1)
            )

            self.assertEqual(result.state, "observing")
            self.assertEqual(result.action.kind, ActionKind.ASSIGN_BUILDER)
            self.assertIsNotNone(store.progression(OWNER_UUID))
            self.assertFalse(any((spool / "requests").glob("*.json")))
            self.assertEqual(store.audit_entries()[-1]["event"], "observed")
            store.close()

    def test_dispatch_requires_controller_and_bridge_active(self) -> None:
        combinations = (
            (ControllerMode.ACTIVE, False, "OBSERVER", False),
            (ControllerMode.OBSERVER, True, "ACTIVE", False),
            (ControllerMode.ACTIVE, True, "ACTIVE", True),
        )
        for controller_mode, enabled, bridge_mode, should_dispatch in combinations:
            with self.subTest(
                controller_mode=controller_mode,
                enabled=enabled,
                bridge_mode=bridge_mode,
            ), tempfile.TemporaryDirectory() as directory:
                store = Store(Path(directory) / "state.db")
                spool = Path(directory) / "spool"
                controller = Controller(
                    ControllerConfig(mode=controller_mode),
                    store,
                    SpoolBridge(spool),
                )
                value = colony(
                    buildings=[builder(compatible=(42,))],
                    work_orders=[
                        {
                            "id": 42,
                            "type": "BUILD",
                            "location": position(120, 210),
                            "target_level": 1,
                            "claimed": False,
                            "claimed_by": None,
                        }
                    ],
                )
                snapshot = ControllerSnapshot(
                    enabled=enabled,
                    mode=bridge_mode,
                    paused=False,
                    metrics=BridgeSnapshot(20.0, 10.0),
                    colonies=(value,),
                )

                result = controller.run_bridge_snapshot(
                    snapshot, HostMetrics(8.0, 0.1)
                )

                self.assertEqual(
                    bool(list((spool / "requests").glob("*.json"))),
                    should_dispatch,
                )
                self.assertEqual(
                    result.state, "waiting" if should_dispatch else "observing"
                )
                self.assertEqual(result.action.kind, ActionKind.ASSIGN_BUILDER)
                store.close()

    def test_bridge_snapshot_plans_colony_and_persists_owner(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            store = Store(Path(directory) / "state.db")
            spool = Path(directory) / "spool"
            controller = Controller(
                ControllerConfig(mode=ControllerMode.ACTIVE),
                store,
                SpoolBridge(spool),
            )
            value = colony(
                buildings=[builder(compatible=(42,))],
                work_orders=[
                    {
                        "id": 42,
                        "type": "BUILD",
                        "location": position(120, 210),
                        "target_level": 1,
                        "claimed": False,
                        "claimed_by": None,
                    }
                ],
            )
            snapshot = ControllerSnapshot(
                enabled=True,
                mode="ACTIVE",
                paused=False,
                metrics=BridgeSnapshot(20.0, 10.0),
                colonies=(value,),
            )

            first = controller.run_bridge_snapshot(
                snapshot, HostMetrics(8.0, 0.1)
            )
            second = controller.run_bridge_snapshot(
                snapshot, HostMetrics(8.0, 0.1)
            )

            self.assertEqual(first.action.kind, ActionKind.ASSIGN_BUILDER)
            self.assertEqual(first.state, "waiting")
            self.assertEqual(second.state, "waiting")
            self.assertIsNotNone(store.progression(OWNER_UUID))
            self.assertEqual(len(list((spool / "requests").glob("*.json"))), 1)
            store.close()

    def test_bridge_snapshot_governor_closes_before_colony_action(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            store = Store(Path(directory) / "state.db")
            spool = Path(directory) / "spool"
            controller = Controller(
                ControllerConfig(mode=ControllerMode.ACTIVE),
                store,
                SpoolBridge(spool),
            )
            value = colony(
                buildings=[builder(compatible=(42,))],
                work_orders=[
                    {
                        "id": 42,
                        "type": "BUILD",
                        "location": position(120, 210),
                        "target_level": 1,
                        "claimed": False,
                        "claimed_by": None,
                    }
                ],
            )
            snapshot = ControllerSnapshot(
                enabled=False,
                mode="OBSERVER",
                paused=False,
                metrics=BridgeSnapshot(18.0, 10.0),
                colonies=(value,),
            )

            result = controller.run_bridge_snapshot(
                snapshot, HostMetrics(8.0, 0.1)
            )

            self.assertEqual(result.state, "suspended")
            self.assertFalse(any((spool / "requests").glob("*.json")))
            store.close()


if __name__ == "__main__":
    unittest.main()

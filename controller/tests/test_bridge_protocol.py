from __future__ import annotations

import json
import tempfile
import unittest
from dataclasses import replace
from datetime import UTC, datetime, timedelta
from pathlib import Path
from unittest.mock import patch
from uuid import UUID

from controller.config import ControllerConfig
from controller.governor import HostMetrics
from controller.ipc import BridgeSnapshotProvider, SpoolBridge
from controller.models import (
    ActionBuilder,
    ActionKind,
    BlockPosition,
    BridgeRequest,
    BridgeResponse,
    ResponseStatus,
    Tier,
    ControllerMode,
    BridgeSnapshot,
)
from controller.service import Controller
from controller.storage import Store


class ProtocolModelTests(unittest.TestCase):
    def test_status_request_matches_bridge_v1_json(self) -> None:
        action = ActionBuilder.status()
        request = BridgeRequest.create(
            action,
            request_id="status-20260821-01",
            nonce=UUID("046583ec-522b-45d2-8655-3cd7c83e8f42"),
            issued_at_epoch_ms=1_787_342_400_000,
        )

        self.assertEqual(
            request.to_json(),
            {
                "type": "controller.action.v1",
                "request_id": "status-20260821-01",
                "idempotency_key": "status-20260821-01",
                "nonce": "046583ec-522b-45d2-8655-3cd7c83e8f42",
                "issued_at_epoch_ms": 1_787_342_400_000,
                "action": "status",
                "payload": {},
                "rationale": "periodic health poll",
            },
        )

    def test_upgrade_builder_emits_exact_bridge_fields(self) -> None:
        action = ActionBuilder.request_upgrade(
            colony_id=7,
            dimension="minecraft:overworld",
            building_pos=BlockPosition(1, 64, 2),
            builder_pos=BlockPosition(3, 64, 4),
            actor_uuid=UUID("16fd2706-8baf-433b-82eb-8c7fada847da"),
        )

        self.assertEqual(action.kind, ActionKind.REQUEST_UPGRADE)
        self.assertEqual(
            action.to_wire_json(),
            {
                "colony_id": 7,
                "dimension": "minecraft:overworld",
                "building_pos": {"x": 1, "y": 64, "z": 2},
                "builder_pos": {"x": 3, "y": 64, "z": 4},
                "actor_uuid": "16fd2706-8baf-433b-82eb-8c7fada847da",
            },
        )

    def test_all_codec_actions_are_typed(self) -> None:
        self.assertEqual(ActionKind.ASSIGN_BUILDER.value, "assign_builder")
        self.assertEqual(ActionKind.GRANT.value, "grant")
        self.assertEqual(ActionKind.PAUSE.value, "pause")
        place = ActionBuilder.place_building(
            colony_id=7,
            dimension="minecraft:overworld",
            building_type="minecolonies:builder",
            position=BlockPosition(5, 64, 6),
            actor_uuid=UUID("16fd2706-8baf-433b-82eb-8c7fada847da"),
        )
        self.assertEqual(place.kind, ActionKind.PLACE_BUILDING)
        self.assertEqual(place.to_wire_json()["building_type"], "minecolonies:builder")

    def test_assign_grant_and_pause_build_exact_payloads(self) -> None:
        actor = UUID("16fd2706-8baf-433b-82eb-8c7fada847da")
        position = BlockPosition(1, 64, 2)
        assign = ActionBuilder.assign_builder(
            colony_id=7,
            dimension="minecraft:overworld",
            work_order_id=42,
            builder_pos=position,
            actor_uuid=actor,
        )
        grant = ActionBuilder.grant_targeted_open_request(
            colony_id=7,
            dimension="minecraft:overworld",
            building_pos=position,
            minecolonies_request_id=UUID(
                "1d6514f4-17b9-4f06-ad27-d3c199e9bf55"
            ),
            item="minecraft:oak_log",
            quantity=8,
            actor_uuid=actor,
        )
        pause = ActionBuilder.pause(ticks=1_200, actor_uuid=actor)

        self.assertEqual(assign.to_wire_json()["work_order_id"], 42)
        self.assertEqual(
            grant.to_wire_json()["minecolonies_request_id"],
            "1d6514f4-17b9-4f06-ad27-d3c199e9bf55",
        )
        self.assertEqual(pause.to_wire_json()["ticks"], 1_200)

    def test_response_parser_accepts_completed_and_rejected(self) -> None:
        base = {
            "type": "bridge.result.v1",
            "request_id": "status-20260821-01",
        }

        ok = BridgeResponse.from_json(
            {**base, "status": "completed", "payload": {"enabled": False}}
        )
        rejected = BridgeResponse.from_json(
            {
                **base,
                "status": "rejected",
                "payload": {"code": "PAUSED", "message": "bridge is paused"},
            }
        )

        self.assertEqual(ok.status, ResponseStatus.COMPLETED)
        self.assertEqual(rejected.status, ResponseStatus.REJECTED)

    def test_response_parser_tolerates_gson_omitted_null_payload(self) -> None:
        response = BridgeResponse.from_json(
            {
                "type": "bridge.result.v1",
                "request_id": "status-20260821-01",
                "status": "rejected",
            }
        )

        self.assertEqual(response.status, ResponseStatus.REJECTED)
        self.assertEqual(response.payload, {})


class VersionedSpoolTests(unittest.TestCase):
    def test_unsafe_idempotency_key_is_rejected_before_file_write(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            bridge = SpoolBridge(Path(directory))
            action = replace(ActionBuilder.status(), idempotency_key="../escape")

            with self.assertRaises(ValueError):
                bridge.submit(action)

            self.assertFalse((Path(directory).parent / "escape.json").exists())

    def test_requests_responses_and_rejected_directories(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            bridge = SpoolBridge(Path(directory))
            request_id = bridge.submit(ActionBuilder.status())
            request_path = Path(directory) / "requests" / f"{request_id}.json"
            outbound = json.loads(request_path.read_text(encoding="utf-8"))

            response = {
                "type": "bridge.result.v1",
                "request_id": request_id,
                "status": "completed",
                "payload": {"enabled": False},
            }
            (Path(directory) / "responses" / f"{request_id}.json").write_text(
                json.dumps(response), encoding="utf-8"
            )

            result = bridge.poll_result(request_id)

            self.assertEqual(result.status, ResponseStatus.COMPLETED)
            self.assertFalse(
                (Path(directory) / "responses" / f"{request_id}.json").exists()
            )
            self.assertTrue((Path(directory) / "rejected").is_dir())

    def test_plain_text_quarantine_becomes_typed_rejection(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            bridge = SpoolBridge(Path(directory))
            request_id = bridge.submit(ActionBuilder.status())
            rejected = Path(directory) / "rejected" / f"{request_id}.json"
            rejected.write_text("INVALID_REQUEST: malformed", encoding="utf-8")

            result = bridge.poll_result(request_id)

            self.assertEqual(result.status, ResponseStatus.REJECTED)
            self.assertEqual(result.code, "INVALID_REQUEST")


class SnapshotProviderTests(unittest.TestCase):
    def test_provider_combines_status_and_snapshot_payloads(self) -> None:
        status = BridgeResponse(
            request_id="status-1",
            status=ResponseStatus.COMPLETED,
            payload={
                "enabled": True,
                "mode": "ACTIVE",
                "paused": False,
                "metrics": {"tps": 19.8, "tickMs": 22.5},
            },
        )
        snapshot = BridgeResponse(
            request_id="snapshot-1",
            status=ResponseStatus.COMPLETED,
            payload={"serverTick": 10, "colonies": [{"id": 7}]},
        )

        class FakeBridge:
            def request(self, action, timeout_seconds):
                return status if action.kind is ActionKind.STATUS else snapshot

        value = BridgeSnapshotProvider(FakeBridge(), timeout_seconds=1).read()

        self.assertEqual(value.metrics.tps, 19.8)
        self.assertEqual(value.metrics.tick_ms, 22.5)
        self.assertEqual(value.colonies, ({"id": 7},))
        self.assertTrue(value.enabled)

    def test_missing_performance_metrics_produce_closed_snapshot(self) -> None:
        response = BridgeResponse(
            request_id="status-1",
            status=ResponseStatus.COMPLETED,
            payload={"enabled": False, "metrics": {}},
        )

        class FakeBridge:
            def request(self, action, timeout_seconds):
                return response

        value = BridgeSnapshotProvider(FakeBridge(), timeout_seconds=1).read()

        self.assertEqual(value.metrics.tps, 0.0)
        self.assertEqual(value.metrics.tick_ms, float("inf"))


class DailyBudgetTests(unittest.TestCase):
    def test_grant_budget_resets_on_utc_day_boundary(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            store = Store(Path(directory) / "state.db")
            day_one = datetime(2026, 8, 21, 23, 59, tzinfo=UTC)
            day_two = day_one + timedelta(minutes=2)
            store.save_progression(
                "player", tier=Tier.WOOD, evidence={}, spent=0, now=day_one
            )
            store.increment_grant_spent("player", 5, now=day_one)

            self.assertEqual(store.grant_spent("player", now=day_one), 5)
            self.assertEqual(store.grant_spent("player", now=day_two), 0)
            store.close()


class CadenceConfigTests(unittest.TestCase):
    def test_service_defaults_to_30_second_loop_and_20_minute_llm(self) -> None:
        config = ControllerConfig()

        self.assertEqual(config.interval_seconds, 30.0)
        self.assertEqual(config.planner.cadence_seconds, 20 * 60)

    def test_persistent_cadence_claims_once_per_window(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            store = Store(Path(directory) / "state.db")

            self.assertTrue(store.claim_cadence("llm", 1_200, now=10_000))
            self.assertFalse(store.claim_cadence("llm", 1_200, now=11_199))
            self.assertTrue(store.claim_cadence("llm", 1_200, now=11_200))
            store.close()


class TypedDispatchTests(unittest.TestCase):
    def test_direct_dispatch_requires_explicit_active_bridge(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            store = Store(Path(directory) / "state.db")
            spool = Path(directory) / "spool"
            controller = Controller(
                ControllerConfig(mode=ControllerMode.ACTIVE),
                store,
                SpoolBridge(spool),
            )
            action = ActionBuilder.request_upgrade(
                colony_id=7,
                dimension="minecraft:overworld",
                building_pos=BlockPosition(1, 64, 2),
                builder_pos=BlockPosition(3, 64, 4),
                actor_uuid=UUID("16fd2706-8baf-433b-82eb-8c7fada847da"),
            )

            result = controller.dispatch_action(
                action,
                BridgeSnapshot(20.0, 10.0),
                HostMetrics(8.0, 0.1),
            )

            self.assertEqual(result.state, "observing")
            self.assertFalse(any((spool / "requests").glob("*.json")))
            store.close()

    def test_place_building_is_typed_but_never_dispatched(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            store = Store(Path(directory) / "state.db")
            spool = Path(directory) / "spool"
            controller = Controller(
                ControllerConfig(mode=ControllerMode.ACTIVE),
                store,
                SpoolBridge(spool),
            )
            action = ActionBuilder.place_building(
                colony_id=7,
                dimension="minecraft:overworld",
                building_type="minecolonies:builder",
                position=BlockPosition(5, 64, 6),
                actor_uuid=UUID("16fd2706-8baf-433b-82eb-8c7fada847da"),
            )

            result = controller.dispatch_action(
                action,
                BridgeSnapshot(20.0, 10.0),
                HostMetrics(8.0, 0.1),
                bridge_enabled=True,
                bridge_mode="ACTIVE",
            )

            self.assertEqual(result.state, "disabled")
            self.assertFalse(any((spool / "requests").glob("*.json")))
            store.close()

    def test_targeted_grant_requires_player_evidence_and_policy(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            store = Store(Path(directory) / "state.db")
            bridge = SpoolBridge(Path(directory) / "spool")
            controller = Controller(
                ControllerConfig(mode=ControllerMode.ACTIVE), store, bridge
            )
            actor = UUID("16fd2706-8baf-433b-82eb-8c7fada847da")
            action = ActionBuilder.grant_targeted_open_request(
                colony_id=7,
                dimension="minecraft:overworld",
                building_pos=BlockPosition(1, 64, 2),
                minecolonies_request_id=UUID(
                    "1d6514f4-17b9-4f06-ad27-d3c199e9bf55"
                ),
                item="minecraft:diamond",
                quantity=1,
                actor_uuid=actor,
            )

            result = controller.dispatch_action(
                action,
                BridgeSnapshot(20.0, 10.0),
                HostMetrics(8.0, 0.1),
                bridge_enabled=True,
                bridge_mode="ACTIVE",
            )

            self.assertEqual(result.state, "disabled")
            self.assertFalse(
                any((Path(directory) / "spool" / "requests").glob("*.json"))
            )
            store.close()

    def test_completed_targeted_grant_charges_daily_actor_budget(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            store = Store(Path(directory) / "state.db")
            spool = Path(directory) / "spool"
            bridge = SpoolBridge(spool)
            controller = Controller(
                ControllerConfig(mode=ControllerMode.ACTIVE), store, bridge
            )
            actor = UUID("16fd2706-8baf-433b-82eb-8c7fada847da")
            store.save_progression(
                str(actor),
                Tier.WOOD,
                {"inventory": {"oak_log": 1}},
                spent=0,
            )
            action = ActionBuilder.grant_targeted_open_request(
                colony_id=7,
                dimension="minecraft:overworld",
                building_pos=BlockPosition(1, 64, 2),
                minecolonies_request_id=UUID(
                    "1d6514f4-17b9-4f06-ad27-d3c199e9bf55"
                ),
                item="minecraft:oak_log",
                quantity=2,
                actor_uuid=actor,
            )
            metrics = BridgeSnapshot(20.0, 10.0)
            host = HostMetrics(8.0, 0.1)

            dispatched = controller.dispatch_action(
                action,
                metrics,
                host,
                bridge_enabled=True,
                bridge_mode="ACTIVE",
            )
            request_id = dispatched.action.idempotency_key
            response = {
                "type": "bridge.result.v1",
                "request_id": request_id,
                "status": "completed",
                "payload": {"granted": 2},
            }
            (spool / "responses" / f"{request_id}.json").write_text(
                json.dumps(response), encoding="utf-8"
            )
            result = controller.dispatch_action(
                action,
                metrics,
                host,
                bridge_enabled=True,
                bridge_mode="ACTIVE",
            )

            self.assertEqual(result.state, "completed")
            self.assertEqual(store.grant_spent(str(actor)), 2)
            store.close()

    def test_only_one_supported_mutation_is_dispatched(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            store = Store(Path(directory) / "state.db")
            spool = Path(directory) / "spool"
            bridge = SpoolBridge(spool)
            controller = Controller(
                ControllerConfig(mode=ControllerMode.ACTIVE), store, bridge
            )
            action = ActionBuilder.request_upgrade(
                colony_id=7,
                dimension="minecraft:overworld",
                building_pos=BlockPosition(1, 64, 2),
                builder_pos=BlockPosition(3, 64, 4),
                actor_uuid=UUID("16fd2706-8baf-433b-82eb-8c7fada847da"),
            )
            metrics = BridgeSnapshot(20.0, 10.0)
            host = HostMetrics(8.0, 0.1)

            first = controller.dispatch_action(
                action,
                metrics,
                host,
                bridge_enabled=True,
                bridge_mode="ACTIVE",
            )
            second = controller.dispatch_action(
                action,
                metrics,
                host,
                bridge_enabled=True,
                bridge_mode="ACTIVE",
            )

            self.assertEqual(first.state, "waiting")
            self.assertEqual(second.state, "waiting")
            self.assertEqual(len(list((spool / "requests").glob("*.json"))), 1)
            store.close()

from __future__ import annotations

import json
import tempfile
import unittest
from dataclasses import replace
from pathlib import Path
from unittest.mock import patch

from controller.config import ConfigError, ControllerConfig, load_config
from controller.governor import Governor, HostMetrics
from controller.ipc import SpoolBridge
from controller.models import (
    Action,
    ActionBuilder,
    ActionKind,
    BridgeSnapshot,
    ControllerMode,
    Evidence,
    GovernorDecision,
    Tier,
)
from controller.planner import DeterministicPlanner, OllamaPlanner
from controller.progression import GrantPolicy, infer_tier
from controller.service import Controller
from controller.storage import Store


class ConfigTests(unittest.TestCase):
    def test_defaults_are_observer_only_and_conservative(self) -> None:
        config = ControllerConfig()

        self.assertEqual(config.mode, ControllerMode.OBSERVER)
        self.assertEqual(config.governor.min_tps, 19.5)
        self.assertEqual(config.governor.max_tick_ms, 35.0)
        self.assertEqual(config.governor.min_available_ram_gib, 4.5)
        self.assertEqual(config.planner.model, "qwen2.5-coder:3b")
        self.assertEqual(config.planner.temperature, 0.0)
        self.assertEqual(config.planner.num_thread, 2)
        self.assertEqual(config.planner.keep_alive, 0)
        self.assertEqual(config.bridge.timeout_seconds, 3.0)
        self.assertEqual(config.interval_seconds, 30.0)
        self.assertFalse(config.allow_any_requested_item)

    def test_shipped_configs_exceed_one_second_bridge_poll(self) -> None:
        root = Path(__file__).resolve().parents[2]

        for path in (
            root / "controller" / "controller.toml",
            root / "deploy" / "config" / "controller.toml",
        ):
            with self.subTest(path=path):
                config = load_config(path)
                self.assertEqual(config.bridge.timeout_seconds, 3.0)
                self.assertGreater(config.bridge.timeout_seconds, 1.0)
                self.assertEqual(config.interval_seconds, 30.0)

        deployed = load_config(root / "deploy" / "config" / "controller.toml")
        self.assertTrue(deployed.allow_any_requested_item)
        self.assertEqual(deployed.grant_budget, 512)

    def test_loads_toml_and_rejects_unsafe_values(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "controller.toml"
            path.write_text(
                '[controller]\nmode = "dry-run"\n'
                "[governor]\nmin_tps = 19.7\n"
                "[planner]\nenabled = true\ncontext = 1024\n",
                encoding="utf-8",
            )

            config = load_config(path)
            self.assertEqual(config.mode, ControllerMode.DRY_RUN)
            self.assertEqual(config.governor.min_tps, 19.7)
            self.assertTrue(config.planner.enabled)

            path.write_text("[planner]\ntemperature = 0.3\n", encoding="utf-8")
            with self.assertRaises(ConfigError):
                load_config(path)

            path.write_text("[bridge]\ntimeout_seconds = 1.0\n", encoding="utf-8")
            with self.assertRaises(ConfigError):
                load_config(path)


class GovernorTests(unittest.TestCase):
    def setUp(self) -> None:
        self.governor = Governor(ControllerConfig().governor)
        self.healthy_host = HostMetrics(available_ram_gib=8.0, load_per_cpu=0.25)

    def test_closes_at_each_required_threshold(self) -> None:
        cases = (
            (BridgeSnapshot(tps=19.49, tick_ms=20.0), "tps"),
            (BridgeSnapshot(tps=20.0, tick_ms=35.01), "tick"),
        )
        for snapshot, reason in cases:
            with self.subTest(reason=reason):
                decision = self.governor.evaluate(snapshot, self.healthy_host)
                self.assertFalse(decision.open)
                self.assertIn(reason, decision.reason)

        decision = self.governor.evaluate(
            BridgeSnapshot(tps=20.0, tick_ms=20.0),
            HostMetrics(available_ram_gib=4.49, load_per_cpu=0.25),
        )
        self.assertFalse(decision.open)
        self.assertIn("memory", decision.reason)

    def test_opens_only_when_all_metrics_are_healthy(self) -> None:
        decision = self.governor.evaluate(
            BridgeSnapshot(tps=19.5, tick_ms=35.0), self.healthy_host
        )
        self.assertTrue(decision.open)


class ProgressionTests(unittest.TestCase):
    def test_tiers_require_player_evidence(self) -> None:
        cases = (
            (Evidence(), Tier.WOOD),
            (Evidence(inventory={"cobblestone": 1}), Tier.STONE),
            (Evidence(stats={"minecraft:mined/iron_ore": 1}), Tier.IRON),
            (Evidence(inventory={"diamond": 1}), Tier.DIAMOND),
            (Evidence(dimensions={"minecraft:the_nether"}), Tier.NETHER),
            (
                Evidence(advancements={"minecraft:end/kill_dragon"}),
                Tier.ENDGAME,
            ),
        )
        for evidence, expected in cases:
            with self.subTest(expected=expected):
                self.assertEqual(infer_tier(evidence), expected)

    def test_grants_are_budgeted_common_and_already_unlocked(self) -> None:
        policy = GrantPolicy(default_budget=8)
        evidence = Evidence(inventory={"cobblestone": 1})

        allowed = policy.evaluate("cobblestone", 4, Tier.STONE, evidence, spent=2)
        locked = policy.evaluate("iron_ingot", 1, Tier.STONE, evidence, spent=0)
        over_budget = policy.evaluate("cobblestone", 7, Tier.STONE, evidence, spent=2)

        self.assertTrue(allowed.allowed)
        self.assertEqual(allowed.cost, 4)
        self.assertFalse(locked.allowed)
        self.assertFalse(over_budget.allowed)

    def test_rare_quest_combat_and_boss_items_are_always_denied(self) -> None:
        policy = GrantPolicy(default_budget=100)
        evidence = Evidence(
            inventory={
                "diamond": 64,
                "nether_star": 64,
                "ender_dragon_spawn_egg": 1,
                "totem_of_undying": 1,
            }
        )
        for item in (
            "diamond",
            "nether_star",
            "ender_dragon_spawn_egg",
            "totem_of_undying",
            "diamond_sword",
        ):
            with self.subTest(item=item):
                result = policy.evaluate(item, 1, Tier.ENDGAME, evidence, spent=0)
                self.assertFalse(result.allowed)

    def test_unrestricted_policy_only_allows_items_for_targeted_requests(self) -> None:
        policy = GrantPolicy(
            default_budget=64,
            allow_any_requested_item=True,
        )
        evidence = Evidence()

        targeted = policy.evaluate(
            "minecraft:nether_star",
            1,
            Tier.WOOD,
            evidence,
            spent=0,
            targeted_request=True,
        )
        arbitrary = policy.evaluate(
            "minecraft:nether_star",
            1,
            Tier.WOOD,
            evidence,
            spent=0,
        )
        over_budget = policy.evaluate(
            "minecraft:oak_log",
            64,
            Tier.WOOD,
            evidence,
            spent=1,
            targeted_request=True,
        )

        self.assertTrue(targeted.allowed)
        self.assertEqual(targeted.cost, 1)
        self.assertFalse(arbitrary.allowed)
        self.assertFalse(over_budget.allowed)


class StoreTests(unittest.TestCase):
    def test_action_reservation_is_idempotent_and_audited(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            store = Store(Path(directory) / "state.db")
            action = Action(
                idempotency_key="same",
                kind=ActionKind.GRANT,
                payload={"item": "cobblestone", "quantity": 1},
                rationale="bootstrap",
            )

            self.assertTrue(store.reserve_action(action))
            self.assertFalse(store.reserve_action(action))
            store.complete_action(action.idempotency_key, "completed", {"ok": True})

            rows = store.audit_entries()
            self.assertEqual([row["event"] for row in rows], ["reserved", "completed"])
            store.close()

    def test_progression_tier_never_regresses(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            store = Store(Path(directory) / "state.db")
            store.save_progression("player", Tier.DIAMOND, {"inventory": {}}, 3)
            store.save_progression("player", Tier.WOOD, {"inventory": {}}, 0)

            progression = store.progression("player")

            self.assertEqual(progression["tier"], Tier.DIAMOND.value)
            self.assertEqual(progression["grant_spent"], 3)
            store.close()


class SpoolBridgeTests(unittest.TestCase):
    def test_timeout_removes_unclaimed_request_from_spool(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            bridge = SpoolBridge(Path(directory))

            with self.assertRaises(TimeoutError):
                bridge.request(ActionBuilder.status(), timeout_seconds=0)

            self.assertEqual(
                list((Path(directory) / "requests").glob("*.json")),
                [],
            )

    def test_typed_json_spool_round_trip(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            bridge = SpoolBridge(Path(directory))
            request_id = bridge.submit(ActionBuilder.status())
            outbound = json.loads(
                (Path(directory) / "requests" / f"{request_id}.json").read_text()
            )
            self.assertEqual(outbound["type"], "controller.action.v1")
            self.assertEqual(outbound["action"], "status")
            self.assertEqual(outbound["payload"], {})

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
            self.assertEqual(result.status, "completed")
            self.assertFalse(
                (Path(directory) / "responses" / f"{request_id}.json").exists()
            )


class PlannerTests(unittest.TestCase):
    def test_deterministic_planner_never_emits_multiple_actions(self) -> None:
        planner = DeterministicPlanner(GrantPolicy(default_budget=8))
        evidence = Evidence(inventory={"oak_log": 1})

        action = planner.plan(evidence, Tier.WOOD, spent=0)

        self.assertIsNotNone(action)
        self.assertEqual(action.kind, ActionKind.GRANT)

    @patch("controller.planner.request.urlopen")
    def test_ollama_rejects_malformed_or_direct_commands(self, urlopen) -> None:
        response = urlopen.return_value.__enter__.return_value
        response.read.return_value = json.dumps(
            {"response": '{"command":"give @p diamond"}'}
        ).encode()
        planner = OllamaPlanner(ControllerConfig().planner)

        self.assertIsNone(planner.plan(Evidence(), Tier.WOOD, spent=0))


class ControllerTests(unittest.TestCase):
    def test_ollama_planner_runs_once_per_cadence_window(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            config = replace(
                ControllerConfig(),
                planner=replace(ControllerConfig().planner, enabled=True),
            )
            store = Store(Path(directory) / "state.db")
            bridge = SpoolBridge(Path(directory) / "spool")
            controller = Controller(config, store, bridge)

            class CountingPlanner:
                calls = 0

                def plan(self, evidence, tier, spent):
                    self.calls += 1
                    return None

            planner = CountingPlanner()
            controller._planner = planner
            metrics = BridgeSnapshot(20.0, 10.0)
            host = HostMetrics(8.0, 0.1)
            evidence = Evidence(inventory={"oak_log": 1})

            controller.run_once(evidence, metrics, host)
            controller.run_once(evidence, metrics, host)

            self.assertEqual(planner.calls, 1)
            store.close()

    def test_observer_mode_records_decision_without_dispatch(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            config = ControllerConfig()
            store = Store(Path(directory) / "state.db")
            bridge = SpoolBridge(Path(directory) / "spool")
            controller = Controller(config, store, bridge)

            result = controller.run_once(
                Evidence(inventory={"oak_log": 1}),
                BridgeSnapshot(tps=20.0, tick_ms=10.0),
                HostMetrics(available_ram_gib=8.0, load_per_cpu=0.1),
            )

            self.assertEqual(result.state, "observing")
            self.assertFalse(any((Path(directory) / "spool" / "requests").iterdir()))
            store.close()

    def test_governor_close_aborts_before_planning(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            store = Store(Path(directory) / "state.db")
            bridge = SpoolBridge(Path(directory) / "spool")
            controller = Controller(ControllerConfig(), store, bridge)

            result = controller.run_once(
                Evidence(inventory={"oak_log": 1}),
                BridgeSnapshot(tps=18.0, tick_ms=10.0),
                HostMetrics(available_ram_gib=8.0, load_per_cpu=0.1),
            )

            self.assertEqual(result.state, "suspended")
            self.assertIsNone(result.action)
            store.close()

    def test_unsupported_grant_is_never_dispatched(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            config = ControllerConfig(mode=ControllerMode.ACTIVE)
            store = Store(Path(directory) / "state.db")
            bridge = SpoolBridge(Path(directory) / "spool")
            controller = Controller(config, store, bridge)
            metrics = BridgeSnapshot(tps=20.0, tick_ms=10.0)
            host = HostMetrics(available_ram_gib=8.0, load_per_cpu=0.1)
            evidence = Evidence(inventory={"oak_log": 1})

            first = controller.run_once(evidence, metrics, host)

            self.assertEqual(first.state, "disabled")
            self.assertFalse(
                any((Path(directory) / "spool" / "requests").iterdir())
            )
            store.close()

    def test_actions_are_bound_to_player_identity(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            config = ControllerConfig(mode=ControllerMode.DRY_RUN)
            store = Store(Path(directory) / "state.db")
            bridge = SpoolBridge(Path(directory) / "spool")
            controller = Controller(config, store, bridge)
            metrics = BridgeSnapshot(tps=20.0, tick_ms=10.0)
            host = HostMetrics(available_ram_gib=8.0, load_per_cpu=0.1)
            evidence = Evidence(inventory={"oak_log": 1})

            first = controller.run_once(evidence, metrics, host, "player-a")
            second = controller.run_once(evidence, metrics, host, "player-b")

            self.assertEqual(first.action.payload["player_id"], "player-a")
            self.assertEqual(second.action.payload["player_id"], "player-b")
            self.assertNotEqual(
                first.action.idempotency_key, second.action.idempotency_key
            )
            store.close()

    def test_governor_rechecks_host_before_dispatch(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            config = ControllerConfig(mode=ControllerMode.ACTIVE)
            store = Store(Path(directory) / "state.db")
            spool = Path(directory) / "spool"
            bridge = SpoolBridge(spool)
            readings = iter(
                (
                    HostMetrics(available_ram_gib=8.0, load_per_cpu=0.1),
                    HostMetrics(available_ram_gib=4.0, load_per_cpu=0.1),
                )
            )
            controller = Controller(config, store, bridge, lambda: next(readings))

            result = controller.run_once(
                Evidence(inventory={"oak_log": 1}),
                BridgeSnapshot(tps=20.0, tick_ms=10.0),
            )

            self.assertEqual(result.state, "suspended")
            self.assertFalse(any((spool / "requests").iterdir()))
            store.close()


if __name__ == "__main__":
    unittest.main()

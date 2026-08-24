from __future__ import annotations

import hashlib
import json
import time
from typing import Callable

from .colony_planner import ColonyPlanner
from .config import ControllerConfig
from .governor import Governor, HostMetrics, collect_host_metrics
from .ipc import Bridge
from .models import (
    Action,
    ActionKind,
    BridgeSnapshot,
    ControllerSnapshot,
    ControllerMode,
    Evidence,
    RunResult,
    Tier,
)
from .planner import DeterministicPlanner, OllamaPlanner, Planner
from .progression import GrantPolicy, infer_tier
from .storage import Store


class Controller:
    def __init__(
        self,
        config: ControllerConfig,
        store: Store,
        bridge: Bridge,
        host_metrics: Callable[[], HostMetrics] = collect_host_metrics,
    ) -> None:
        self._config = config
        self._store = store
        self._bridge = bridge
        self._host_metrics = host_metrics
        self._governor = Governor(config.governor)
        policy = GrantPolicy(
            config.grant_budget,
            allow_any_requested_item=config.allow_any_requested_item,
        )
        self._grant_policy = policy
        self._colony_planner = ColonyPlanner(policy, store)
        self._fallback = DeterministicPlanner(policy)
        self._planner: Planner = (
            OllamaPlanner(config.planner, policy)
            if config.planner.enabled
            else self._fallback
        )

    def run_bridge_snapshot(
        self,
        snapshot: ControllerSnapshot,
        host_metrics: HostMetrics | None = None,
    ) -> RunResult:
        self._colony_planner.persist_owner_evidence(snapshot.colonies)
        if snapshot.paused:
            self._store.audit("suspended", {"reason": "bridge is paused"})
            return RunResult("suspended", "bridge is paused")
        pending = self._store.pending_action()
        if pending is not None:
            result = self._poll_pending(pending)
            return result or RunResult(
                "waiting", "one action is already in progress"
            )
        host = host_metrics or self._host_metrics()
        decision = self._governor.evaluate(snapshot.metrics, host)
        if not decision.open:
            self._store.audit("suspended", {"reason": decision.reason})
            return RunResult("suspended", decision.reason)
        action = self._colony_planner.plan(snapshot.colonies)
        if action is None:
            self._store.audit("idle", {"reason": "no eligible colony action"})
            return RunResult("idle", "no eligible colony action")
        bridge_active = snapshot.enabled and snapshot.mode == "ACTIVE"
        controller_active = self._config.mode is ControllerMode.ACTIVE
        if not bridge_active or not controller_active:
            reason = "controller and bridge must both be active to dispatch"
            event = (
                "dry_run"
                if self._config.mode is ControllerMode.DRY_RUN
                else "observed"
            )
            self._store.audit(
                event,
                {
                    "reason": reason,
                    "action": action.to_json(),
                    "bridge_enabled": snapshot.enabled,
                    "bridge_mode": snapshot.mode,
                    "controller_mode": self._config.mode.value,
                },
            )
            state = (
                "dry-run"
                if self._config.mode is ControllerMode.DRY_RUN
                else "observing"
            )
            return RunResult(state, reason, action)
        return self.dispatch_action(
            action,
            snapshot.metrics,
            host,
            bridge_enabled=snapshot.enabled,
            bridge_mode=snapshot.mode,
        )

    def dispatch_action(
        self,
        action: Action,
        bridge_metrics: BridgeSnapshot,
        host_metrics: HostMetrics | None = None,
        *,
        bridge_enabled: bool = False,
        bridge_mode: str = "OBSERVER",
    ) -> RunResult:
        pending = self._store.pending_action()
        if pending is not None:
            result = self._poll_pending(pending)
            return result or RunResult(
                "waiting", "one action is already in progress"
            )
        if not bridge_enabled or bridge_mode != "ACTIVE":
            reason = "bridge must be enabled and active to dispatch"
            self._store.audit(
                "observed",
                {
                    "action": action.to_json(),
                    "reason": reason,
                    "bridge_enabled": bridge_enabled,
                    "bridge_mode": bridge_mode,
                },
            )
            return RunResult("observing", reason, action)
        if self._config.mode is ControllerMode.OBSERVER:
            self._store.audit(
                "observed", {"action": action.to_json(), "reason": "inactive mode"}
            )
            return RunResult("observing", "controller mode prevents dispatch", action)
        if self._config.mode is ControllerMode.DRY_RUN:
            self._store.audit("dry_run", {"action": action.to_json()})
            return RunResult("dry-run", "dry-run mode prevents dispatch", action)
        if action.kind is ActionKind.PLACE_BUILDING:
            reason = "place_building is intentionally disabled"
            self._store.audit(
                "disabled_action", {"action": action.to_json(), "reason": reason}
            )
            return RunResult("disabled", reason, action)
        grant_rejection = self._grant_rejection(action)
        if grant_rejection is not None:
            self._store.audit(
                "disabled_action",
                {"action": action.to_json(), "reason": grant_rejection},
            )
            return RunResult("disabled", grant_rejection, action)
        host = host_metrics or self._host_metrics()
        decision = self._governor.evaluate(bridge_metrics, host)
        if not decision.open:
            self._store.audit("suspended", {"reason": decision.reason})
            return RunResult("suspended", decision.reason, action)
        try:
            action.to_wire_json()
        except ValueError as exc:
            self._store.audit(
                "disabled_action", {"action": action.to_json(), "reason": str(exc)}
            )
            return RunResult("disabled", str(exc), action)
        action = _ensure_idempotency(action)
        if not self._store.reserve_action(action):
            return RunResult("waiting", "action is duplicate or another action is pending")
        try:
            request_id = self._bridge.submit(action)
        except (OSError, ValueError) as exc:
            self._store.complete_action(
                action.idempotency_key, "failed", {"error": str(exc)}
            )
            return RunResult("failed", f"bridge dispatch failed: {exc}", action)
        self._store.mark_dispatched(action.idempotency_key, request_id)
        return RunResult("waiting", "action dispatched", action)

    def _grant_rejection(self, action: Action) -> str | None:
        if action.kind is not ActionKind.GRANT:
            return None
        actor = str(action.payload.get("actor_uuid", ""))
        progression = self._store.progression(actor)
        if progression is None:
            return "targeted grants require persisted player evidence"
        evidence = Evidence.from_json(json.loads(str(progression["evidence"])))
        targeted_request = {
            "colony_id",
            "dimension",
            "building_pos",
            "minecolonies_request_id",
            "actor_uuid",
        }.issubset(action.payload)
        decision = self._grant_policy.evaluate(
            str(action.payload.get("item", "")),
            int(action.payload.get("quantity", 0)),
            Tier(str(progression["tier"])),
            evidence,
            self._store.grant_spent(actor),
            targeted_request=targeted_request,
        )
        return None if decision.allowed else decision.reason

    def run_once(
        self,
        evidence: Evidence,
        bridge_metrics: BridgeSnapshot,
        host_metrics: HostMetrics | None = None,
        player_id: str = "local-player",
    ) -> RunResult:
        tier = infer_tier(evidence)
        spent = self._store.grant_spent(player_id)
        self._store.save_progression(
            player_id, tier, _evidence_json(evidence), spent
        )

        pending = self._store.pending_action()
        if pending is not None:
            result = self._poll_pending(pending)
            if result is not None:
                return result
            return RunResult("waiting", "one action is already in progress")

        host = host_metrics or self._host_metrics()
        decision = self._governor.evaluate(bridge_metrics, host)
        if not decision.open:
            self._store.audit("suspended", {"reason": decision.reason})
            return RunResult("suspended", decision.reason)

        use_model = (
            self._planner is not self._fallback
            and self._store.claim_cadence(
                "llm", self._config.planner.cadence_seconds
            )
        )
        action = (
            self._planner.plan(evidence, tier, spent)
            if use_model
            else self._fallback.plan(evidence, tier, spent)
        )
        if action is None and use_model:
            self._store.audit("planner_fallback", {"reason": "no valid model proposal"})
            action = self._fallback.plan(evidence, tier, spent)
        if action is None:
            self._store.audit("idle", {"reason": "no eligible action"})
            return RunResult("idle", "no eligible action")
        action = _bind_player(action, player_id)

        if self._config.mode is ControllerMode.OBSERVER:
            self._store.audit(
                "observed", {"action": action.to_json(), "tier": tier.value}
            )
            return RunResult("observing", "observer mode prevents dispatch", action)
        if self._config.mode is ControllerMode.DRY_RUN:
            self._store.audit(
                "dry_run", {"action": action.to_json(), "tier": tier.value}
            )
            return RunResult("dry-run", "dry-run mode prevents dispatch", action)
        final_host = host_metrics if host_metrics is not None else self._host_metrics()
        final_decision = self._governor.evaluate(bridge_metrics, final_host)
        if not final_decision.open:
            self._store.audit("aborted", {"reason": final_decision.reason})
            return RunResult("suspended", final_decision.reason)
        try:
            action.to_wire_json()
        except ValueError as exc:
            self._store.audit(
                "disabled_action", {"action": action.to_json(), "reason": str(exc)}
            )
            return RunResult("disabled", str(exc), action)
        if not self._store.reserve_action(action):
            return RunResult("waiting", "action is duplicate or another action is pending")
        try:
            request_id = self._bridge.submit(action)
        except (OSError, ValueError) as exc:
            self._store.complete_action(
                action.idempotency_key, "failed", {"error": str(exc)}
            )
            return RunResult("failed", f"bridge dispatch failed: {exc}", action)
        self._store.mark_dispatched(action.idempotency_key, request_id)
        return RunResult("waiting", "action dispatched", action)

    def _poll_pending(self, pending: dict[str, object]) -> RunResult | None:
        request_id = pending.get("request_id")
        if not request_id:
            self._store.complete_action(
                str(pending["idempotency_key"]),
                "failed",
                {"error": "reserved action was never dispatched"},
            )
            return RunResult("failed", "recovered an undispatched action")
        try:
            result = self._bridge.poll_result(str(request_id))
        except (OSError, ValueError) as exc:
            return RunResult("waiting", f"bridge result unavailable: {exc}")
        if result is None:
            return None
        key = str(pending["idempotency_key"])
        terminal_status = result.status.value
        self._store.complete_action(
            key,
            terminal_status,
            {
                "code": result.code,
                "message": result.message,
                "payload": result.payload,
            },
        )
        if terminal_status == "completed" and pending["kind"] == ActionKind.GRANT.value:
            payload = json.loads(str(pending["payload"]))
            self._store.increment_grant_spent(
                str(payload.get("player_id", payload.get("actor_uuid"))),
                int(payload["quantity"]),
            )
        return RunResult(
            terminal_status,
            f"bridge action {terminal_status}: {result.code}",
        )

    def run_forever(
        self,
        snapshot_provider: Callable[[], tuple[Evidence, BridgeSnapshot]],
    ) -> None:
        while True:
            evidence, bridge_metrics = snapshot_provider()
            self.run_once(evidence, bridge_metrics)
            time.sleep(self._config.interval_seconds)


def _evidence_json(evidence: Evidence) -> dict[str, object]:
    return {
        "stats": evidence.stats,
        "advancements": sorted(evidence.advancements),
        "inventory": evidence.inventory,
        "dimensions": sorted(evidence.dimensions),
    }


def _bind_player(action: Action, player_id: str) -> Action:
    key = hashlib.sha256(
        f"{player_id}:{action.idempotency_key}".encode("utf-8")
    ).hexdigest()[:32]
    return Action(
        idempotency_key=key,
        kind=action.kind,
        payload={**action.payload, "player_id": player_id},
        rationale=action.rationale,
    )


def _ensure_idempotency(action: Action) -> Action:
    if action.idempotency_key:
        return action
    canonical = json.dumps(
        action.to_wire_json(), sort_keys=True, separators=(",", ":")
    )
    return Action(
        kind=action.kind,
        payload=action.payload,
        rationale=action.rationale,
        idempotency_key=hashlib.sha256(canonical.encode("utf-8")).hexdigest()[:32],
    )

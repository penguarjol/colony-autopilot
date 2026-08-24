from __future__ import annotations

import hashlib
import json
from typing import Protocol
from urllib import request

from .config import PlannerConfig
from .models import Action, ActionKind, Evidence, Tier
from .progression import GrantPolicy


class Planner(Protocol):
    def plan(self, evidence: Evidence, tier: Tier, spent: int) -> Action | None: ...


class DeterministicPlanner:
    def __init__(self, policy: GrantPolicy) -> None:
        self._policy = policy

    def plan(self, evidence: Evidence, tier: Tier, spent: int) -> Action | None:
        remaining = self._policy.default_budget - spent
        if remaining <= 0:
            return None
        for item in sorted(evidence.inventory):
            quantity = min(remaining, max(1, 4 - evidence.inventory[item]))
            decision = self._policy.evaluate(item, quantity, tier, evidence, spent)
            if decision.allowed:
                return _grant_action(item, quantity, tier, "deterministic bootstrap")
        return None


class OllamaPlanner:
    def __init__(self, config: PlannerConfig, policy: GrantPolicy | None = None) -> None:
        self._config = config
        self._policy = policy or GrantPolicy(default_budget=8)

    def plan(self, evidence: Evidence, tier: Tier, spent: int) -> Action | None:
        prompt = json.dumps(
            {
                "task": "Propose at most one common bootstrap resource grant.",
                "constraints": [
                    "Only resources already present in player evidence.",
                    "Never propose rare, quest, combat, or boss items.",
                    "Return JSON matching the supplied schema; no commands or prose.",
                ],
                "tier": tier.value,
                "inventory": evidence.inventory,
                "spent": spent,
                "budget": self._policy.default_budget,
            },
            sort_keys=True,
        )
        payload = {
            "model": self._config.model,
            "prompt": prompt,
            "stream": False,
            "keep_alive": self._config.keep_alive,
            "format": {
                "type": "object",
                "properties": {
                    "item": {"type": "string"},
                    "quantity": {"type": "integer", "minimum": 1},
                    "rationale": {"type": "string"},
                },
                "required": ["item", "quantity", "rationale"],
                "additionalProperties": False,
            },
            "options": {
                "temperature": 0,
                "num_thread": self._config.num_thread,
                "num_ctx": self._config.context,
            },
        }
        try:
            http_request = request.Request(
                self._config.endpoint,
                data=json.dumps(payload).encode("utf-8"),
                headers={"Content-Type": "application/json"},
                method="POST",
            )
            with request.urlopen(
                http_request, timeout=self._config.timeout_seconds
            ) as response:
                envelope = json.loads(response.read())
            proposal = json.loads(envelope["response"])
            if set(proposal) != {"item", "quantity", "rationale"}:
                return None
            item = str(proposal["item"])
            quantity = int(proposal["quantity"])
            rationale = str(proposal["rationale"])
            decision = self._policy.evaluate(item, quantity, tier, evidence, spent)
            if not decision.allowed:
                return None
            return _grant_action(item, quantity, tier, rationale)
        except (OSError, ValueError, TypeError, KeyError, json.JSONDecodeError):
            return None


def _grant_action(item: str, quantity: int, tier: Tier, rationale: str) -> Action:
    canonical = f"grant:{tier.value}:{item}:{quantity}"
    key = hashlib.sha256(canonical.encode("utf-8")).hexdigest()[:32]
    return Action(
        idempotency_key=key,
        kind=ActionKind.GRANT,
        payload={"item": item, "quantity": quantity},
        rationale=rationale[:256],
    )

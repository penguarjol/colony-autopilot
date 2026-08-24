from __future__ import annotations

from dataclasses import dataclass

from .models import Evidence, Tier


TIER_ORDER = tuple(Tier)
COMMON_RESOURCES: dict[Tier, frozenset[str]] = {
    Tier.WOOD: frozenset({"oak_log", "birch_log", "spruce_log", "stick", "dirt"}),
    Tier.STONE: frozenset({"cobblestone", "stone", "coal", "charcoal"}),
    Tier.IRON: frozenset({"iron_ingot", "raw_iron"}),
    Tier.DIAMOND: frozenset(),
    Tier.NETHER: frozenset({"netherrack", "basalt", "blackstone"}),
    Tier.ENDGAME: frozenset({"end_stone"}),
}
ALWAYS_DENIED = frozenset(
    {
        "diamond",
        "emerald",
        "ancient_debris",
        "netherite_ingot",
        "nether_star",
        "dragon_egg",
        "ender_dragon_spawn_egg",
        "wither_skeleton_skull",
        "totem_of_undying",
        "elytra",
        "trident",
        "enchanted_golden_apple",
        "beacon",
        "end_crystal",
    }
)
COMBAT_SUFFIXES = ("_sword", "_axe", "_bow", "_crossbow", "_armor", "_helmet")


@dataclass(frozen=True, slots=True)
class GrantDecision:
    allowed: bool
    reason: str
    cost: int = 0


def infer_tier(evidence: Evidence) -> Tier:
    if _has_advancement(evidence, "end/kill_dragon"):
        return Tier.ENDGAME
    if "minecraft:the_nether" in evidence.dimensions:
        return Tier.NETHER
    if _has_item(evidence, {"diamond", "diamond_pickaxe"}) or _has_stat(
        evidence, "diamond_ore"
    ):
        return Tier.DIAMOND
    if _has_item(evidence, {"iron_ingot", "raw_iron", "iron_pickaxe"}) or _has_stat(
        evidence, "iron_ore"
    ):
        return Tier.IRON
    if _has_item(evidence, {"cobblestone", "stone", "stone_pickaxe"}) or _has_stat(
        evidence, "stone"
    ):
        return Tier.STONE
    return Tier.WOOD


def _has_advancement(evidence: Evidence, suffix: str) -> bool:
    return any(value.removeprefix("minecraft:") == suffix for value in evidence.advancements)


def _has_item(evidence: Evidence, names: set[str]) -> bool:
    return any(evidence.inventory.get(name, 0) > 0 for name in names)


def _has_stat(evidence: Evidence, resource: str) -> bool:
    return any(key.endswith(resource) and count > 0 for key, count in evidence.stats.items())


class GrantPolicy:
    def __init__(
        self,
        default_budget: int,
        allow_any_requested_item: bool = False,
    ) -> None:
        self.default_budget = default_budget
        self.allow_any_requested_item = allow_any_requested_item

    def evaluate(
        self,
        item: str,
        quantity: int,
        tier: Tier,
        evidence: Evidence,
        spent: int,
        *,
        targeted_request: bool = False,
    ) -> GrantDecision:
        normalized = item.removeprefix("minecraft:")
        if not normalized:
            return GrantDecision(False, "item must be non-empty")
        if quantity <= 0:
            return GrantDecision(False, "quantity must be positive")
        if spent + quantity > self.default_budget:
            return GrantDecision(False, "bootstrap budget exceeded")
        if self.allow_any_requested_item and targeted_request:
            return GrantDecision(
                True,
                "concrete MineColonies request is within budget",
                quantity,
            )
        if normalized in ALWAYS_DENIED or normalized.endswith(COMBAT_SUFFIXES):
            return GrantDecision(False, "rare, quest, combat, and boss items are denied")
        unlocked = set().union(
            *(
                COMMON_RESOURCES[candidate]
                for candidate in TIER_ORDER[: TIER_ORDER.index(tier) + 1]
            )
        )
        if normalized not in unlocked:
            return GrantDecision(False, "resource is not a common unlocked resource")
        if evidence.inventory.get(normalized, 0) <= 0:
            return GrantDecision(False, "player evidence does not unlock this resource")
        return GrantDecision(True, "common resource is evidenced and within budget", quantity)

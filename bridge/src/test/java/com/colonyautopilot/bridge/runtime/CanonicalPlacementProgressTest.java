package com.colonyautopilot.bridge.runtime;

import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CanonicalPlacementProgressTest {
    @Test
    void advancesThroughCanonicalPlacementBeforeVerification() {
        CanonicalPlacementProgress progress = CanonicalPlacementProgress.start();

        for (CanonicalPlacementPhase expected : List.of(
            CanonicalPlacementPhase.SOLID,
            CanonicalPlacementPhase.WEAK_SOLID,
            CanonicalPlacementPhase.CLEAR_WATER,
            CanonicalPlacementPhase.NON_SOLID,
            CanonicalPlacementPhase.ENTITIES,
            CanonicalPlacementPhase.VERIFYING
        )) {
            assertEquals(expected, progress.phase());
            if (expected != CanonicalPlacementPhase.VERIFYING) {
                progress = progress.nextPhase();
            }
        }

        assertEquals(CanonicalPlacementPhase.VERIFYING, progress.phase());
        assertFalse(progress.canComplete());
        assertTrue(progress.verificationPassed().canComplete());
    }

    @Test
    void retainsIteratorPositionAcrossSessionRecreation() {
        CanonicalPlacementProgress progress = new CanonicalPlacementProgress(
            CanonicalPlacementPhase.NON_SOLID,
            new BlockPos(16, 4, 50),
            12_600,
            false
        );

        assertEquals(
            new BlockPos(16, 4, 50),
            progress.iteratorPosition()
        );
        assertEquals(12_600, progress.completedSteps());
    }

    @Test
    void acceptsOnlyKnownContextualPillarPropertyDifference() {
        Map<String, String> blueprint = Map.of(
            "axis", "y",
            "column", "pillar_base"
        );
        Map<String, String> world = Map.of(
            "axis", "y",
            "column", "full_pillar"
        );

        assertTrue(ContextualBlockStateEquivalence.matches(
            "domum_ornamentum:blockpillar",
            blueprint,
            "domum_ornamentum:blockpillar",
            world
        ));
        assertFalse(ContextualBlockStateEquivalence.matches(
            "minecraft:oak_log",
            blueprint,
            "minecraft:oak_log",
            world
        ));
        assertFalse(ContextualBlockStateEquivalence.matches(
            "domum_ornamentum:blockpillar",
            blueprint,
            "domum_ornamentum:blockpillar",
            Map.of("axis", "x", "column", "full_pillar")
        ));
    }
}

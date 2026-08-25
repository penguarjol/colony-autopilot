package com.colonyautopilot.bridge.runtime;

import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;
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

    @Test
    void acceptsLeafDistanceButNotPersistentDifferences() {
        Map<String, String> blueprint = Map.of(
            "distance", "7",
            "persistent", "true",
            "waterlogged", "false"
        );
        Map<String, String> world = Map.of(
            "distance", "2",
            "persistent", "true",
            "waterlogged", "false"
        );

        assertTrue(ContextualBlockStateEquivalence.matchesLeaves(
            "minecraft:oak_leaves",
            blueprint,
            "minecraft:oak_leaves",
            world
        ));
        assertFalse(ContextualBlockStateEquivalence.matchesLeaves(
            "minecraft:oak_leaves",
            blueprint,
            "minecraft:oak_leaves",
            Map.of(
                "distance", "2",
                "persistent", "false",
                "waterlogged", "false"
            )
        ));
        assertFalse(ContextualBlockStateEquivalence.matchesLeaves(
            "minecraft:oak_leaves",
            blueprint,
            "minecraft:birch_leaves",
            world
        ));
    }

    @Test
    void acceptsOnlyTexturesUsedByMateriallyTexturedBlock() {
        Map<String, String> blueprint = Map.of(
            "minecraft:block/oak_planks", "domum_ornamentum:brick_extra",
            "minecraft:block/dark_oak_planks", "minecraft:spruce_planks",
            "minecraft:block/acacia_planks", "domum_ornamentum:brick_extra"
        );
        Map<String, String> world = Map.of(
            "minecraft:block/oak_planks", "domum_ornamentum:brick_extra",
            "minecraft:block/dark_oak_planks", "minecraft:spruce_planks"
        );
        Set<String> components = Set.of(
            "minecraft:block/oak_planks",
            "minecraft:block/dark_oak_planks"
        );

        assertTrue(ContextualBlockEntityEquivalence.matchesMaterialTextures(
            "domum_ornamentum:materially_retexturable",
            blueprint,
            "domum_ornamentum:materially_retexturable",
            world,
            components
        ));
        assertFalse(ContextualBlockEntityEquivalence.matchesMaterialTextures(
            "domum_ornamentum:materially_retexturable",
            blueprint,
            "domum_ornamentum:materially_retexturable",
            Map.of(
                "minecraft:block/oak_planks",
                "domum_ornamentum:brick_extra"
            ),
            components
        ));
        assertFalse(ContextualBlockEntityEquivalence.matchesMaterialTextures(
            "domum_ornamentum:materially_retexturable",
            blueprint,
            "domum_ornamentum:materially_retexturable",
            Map.of(
                "minecraft:block/oak_planks",
                "domum_ornamentum:brick_extra",
                "minecraft:block/dark_oak_planks",
                "minecraft:birch_planks"
            ),
            components
        ));
    }

    @Test
    void distinguishesExteriorFromEnclosedBlueprintAir() {
        BlockPos size = new BlockPos(3, 3, 3);
        BlockPos center = new BlockPos(1, 1, 1);

        assertTrue(BlueprintAirConnectivity.isExterior(
            center,
            size,
            position -> position.getZ() == 1
                && position.getY() == 1
                && position.getX() <= 1
        ));
        assertFalse(BlueprintAirConnectivity.isExterior(
            center,
            size,
            position -> position.equals(center)
        ));
        assertTrue(ContextualBlockStateEquivalence.matchesExteriorWater(
            "minecraft:air",
            "minecraft:water",
            true
        ));
        assertFalse(ContextualBlockStateEquivalence.matchesExteriorWater(
            "minecraft:air",
            "minecraft:water",
            false
        ));
    }
}

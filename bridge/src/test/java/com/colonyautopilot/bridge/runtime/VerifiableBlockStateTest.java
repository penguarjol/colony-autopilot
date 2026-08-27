package com.colonyautopilot.bridge.runtime;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VerifiableBlockStateTest {
    @Test
    void ordinaryBlocksAreVerifiable() {
        assertTrue(
            VerifiableBlockState.isVerifiable("minecraft:stone", Map.of())
        );
        assertTrue(
            VerifiableBlockState.isVerifiable(
                "minecraft:oak_planks",
                Map.of("waterlogged", "false")
            )
        );
    }

    @Test
    void generationScaffoldingIsNeverVerifiable() {
        assertFalse(
            VerifiableBlockState.isVerifiable(
                "minecraft:jigsaw",
                Map.of("orientation", "east_up")
            )
        );
        assertFalse(
            VerifiableBlockState.isVerifiable("minecraft:structure_block", Map.of())
        );
        assertFalse(
            VerifiableBlockState.isVerifiable("minecraft:structure_void", Map.of())
        );
    }

    @Test
    void flowingFluidsAreNotVerifiableButSourcesAre() {
        assertFalse(
            VerifiableBlockState.isVerifiable(
                "minecraft:water",
                Map.of("level", "1")
            )
        );
        assertFalse(
            VerifiableBlockState.isVerifiable(
                "minecraft:lava",
                Map.of("level", "7")
            )
        );
        assertTrue(
            VerifiableBlockState.isVerifiable(
                "minecraft:water",
                Map.of("level", "0")
            )
        );
        assertTrue(
            VerifiableBlockState.isVerifiable(
                "minecraft:lava",
                Map.of("level", "0")
            )
        );
    }

    @Test
    void fluidWithoutALevelPropertyIsTreatedAsASource() {
        assertTrue(
            VerifiableBlockState.isVerifiable("minecraft:water", Map.of())
        );
    }

    @Test
    void unparsableFluidLevelIsNotVerifiable() {
        assertFalse(
            VerifiableBlockState.isVerifiable(
                "minecraft:water",
                Map.of("level", "not-a-number")
            )
        );
    }
}

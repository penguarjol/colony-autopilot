package com.colonyautopilot.bridge.runtime;

import java.util.Map;
import java.util.Set;

/**
 * Decides whether a blueprint block state describes something the world can
 * still be expected to contain once construction has settled.
 *
 * <p>Some blueprint states can never match a finished world. Jigsaw and
 * structure blocks are consumed by structure generation and leave air behind,
 * and a flowing fluid only exists while a source feeds it. Treating those as
 * deadline conditions makes verification permanently unsatisfiable.
 */
final class VerifiableBlockState {
    private static final Set<String> GENERATION_SCAFFOLDING = Set.of(
        "minecraft:jigsaw",
        "minecraft:structure_block",
        "minecraft:structure_void"
    );
    private static final Set<String> FLUIDS = Set.of(
        "minecraft:water",
        "minecraft:lava"
    );
    private static final String FLUID_LEVEL_PROPERTY = "level";
    private static final String SOURCE_FLUID_LEVEL = "0";

    private VerifiableBlockState() {
    }

    static boolean isVerifiable(String blockId, Map<String, String> properties) {
        if (GENERATION_SCAFFOLDING.contains(blockId)) {
            return false;
        }
        if (!FLUIDS.contains(blockId)) {
            return true;
        }
        String level = properties.get(FLUID_LEVEL_PROPERTY);
        return level == null || SOURCE_FLUID_LEVEL.equals(level);
    }
}

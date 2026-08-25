package com.colonyautopilot.bridge.runtime;

import java.util.HashMap;
import java.util.Map;

final class ContextualBlockStateEquivalence {
    private static final String DOMUM_ORNAMENTUM_PILLAR =
        "domum_ornamentum:blockpillar";
    private static final String CONTEXTUAL_COLUMN_PROPERTY = "column";
    private static final String LEAF_DISTANCE_PROPERTY = "distance";

    private ContextualBlockStateEquivalence() {
    }

    static boolean matches(
        String blueprintBlock,
        Map<String, String> blueprintProperties,
        String worldBlock,
        Map<String, String> worldProperties
    ) {
        if (!DOMUM_ORNAMENTUM_PILLAR.equals(blueprintBlock)
            || !blueprintBlock.equals(worldBlock)) {
            return false;
        }
        return matchesIgnoringProperty(
            blueprintProperties,
            worldProperties,
            CONTEXTUAL_COLUMN_PROPERTY
        );
    }

    static boolean matchesLeaves(
        String blueprintBlock,
        Map<String, String> blueprintProperties,
        String worldBlock,
        Map<String, String> worldProperties
    ) {
        if (!blueprintBlock.equals(worldBlock)) {
            return false;
        }
        return matchesIgnoringProperty(
            blueprintProperties,
            worldProperties,
            LEAF_DISTANCE_PROPERTY
        );
    }

    static boolean matchesExteriorWater(
        String blueprintBlock,
        String worldBlock,
        boolean exteriorAir
    ) {
        return exteriorAir
            && "minecraft:air".equals(blueprintBlock)
            && "minecraft:water".equals(worldBlock);
    }

    private static boolean matchesIgnoringProperty(
        Map<String, String> blueprintProperties,
        Map<String, String> worldProperties,
        String ignoredProperty
    ) {
        Map<String, String> expected = new HashMap<>(blueprintProperties);
        Map<String, String> actual = new HashMap<>(worldProperties);
        expected.remove(ignoredProperty);
        actual.remove(ignoredProperty);
        return expected.equals(actual);
    }
}

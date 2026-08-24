package com.colonyautopilot.bridge.runtime;

import java.util.HashMap;
import java.util.Map;

final class ContextualBlockStateEquivalence {
    private static final String DOMUM_ORNAMENTUM_PILLAR =
        "domum_ornamentum:blockpillar";
    private static final String CONTEXTUAL_COLUMN_PROPERTY = "column";

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
        Map<String, String> expected = withoutContextualColumn(blueprintProperties);
        Map<String, String> actual = withoutContextualColumn(worldProperties);
        return expected.equals(actual);
    }

    private static Map<String, String> withoutContextualColumn(
        Map<String, String> properties
    ) {
        Map<String, String> result = new HashMap<>(properties);
        result.remove(CONTEXTUAL_COLUMN_PROPERTY);
        return result;
    }
}

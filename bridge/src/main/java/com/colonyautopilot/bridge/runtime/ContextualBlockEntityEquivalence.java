package com.colonyautopilot.bridge.runtime;

import java.util.Map;
import java.util.Set;

final class ContextualBlockEntityEquivalence {
    private static final String MATERIALLY_RETEXTURABLE =
        "domum_ornamentum:materially_retexturable";

    private ContextualBlockEntityEquivalence() {
    }

    static boolean matchesMaterialTextures(
        String blueprintEntity,
        Map<String, String> blueprintTextures,
        String worldEntity,
        Map<String, String> worldTextures,
        Set<String> validComponents
    ) {
        if (!MATERIALLY_RETEXTURABLE.equals(blueprintEntity)
            || !blueprintEntity.equals(worldEntity)
            || validComponents.isEmpty()
            || !worldTextures.keySet().equals(validComponents)) {
            return false;
        }
        return validComponents.stream().allMatch(component ->
            worldTextures.get(component).equals(blueprintTextures.get(component))
        );
    }
}

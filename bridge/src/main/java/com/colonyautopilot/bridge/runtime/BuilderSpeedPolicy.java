package com.colonyautopilot.bridge.runtime;

final class BuilderSpeedPolicy {
    private static final int MINIMUM_MULTIPLIER = 1;
    private static final int MAXIMUM_MULTIPLIER = 10;

    static int effectiveMultiplier(
        int requestedMultiplier,
        boolean enabled,
        boolean healthy
    ) {
        if (!enabled || !healthy) {
            return MINIMUM_MULTIPLIER;
        }
        return Math.max(
            MINIMUM_MULTIPLIER,
            Math.min(MAXIMUM_MULTIPLIER, requestedMultiplier)
        );
    }

    private BuilderSpeedPolicy() {
    }
}

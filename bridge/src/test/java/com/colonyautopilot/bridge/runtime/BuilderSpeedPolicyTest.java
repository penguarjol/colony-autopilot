package com.colonyautopilot.bridge.runtime;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class BuilderSpeedPolicyTest {
    @Test
    void appliesConfiguredSpeedOnlyWhileEnabledAndHealthy() {
        assertEquals(10, BuilderSpeedPolicy.effectiveMultiplier(10, true, true));
        assertEquals(1, BuilderSpeedPolicy.effectiveMultiplier(10, false, true));
        assertEquals(1, BuilderSpeedPolicy.effectiveMultiplier(10, true, false));
    }

    @Test
    void capsSpeedAtTheSupportedRange() {
        assertEquals(1, BuilderSpeedPolicy.effectiveMultiplier(0, true, true));
        assertEquals(10, BuilderSpeedPolicy.effectiveMultiplier(20, true, true));
    }
}

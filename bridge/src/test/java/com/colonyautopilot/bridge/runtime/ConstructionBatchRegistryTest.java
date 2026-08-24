package com.colonyautopilot.bridge.runtime;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ConstructionBatchRegistryTest {
    @Test
    void defaultsToSingleStepAndTracksWorkOrderIdentity() {
        Object order = new Object();

        assertEquals(1, ConstructionBatchRegistry.stepsFor(order));
        ConstructionBatchRegistry.setSteps(order, 8);
        assertEquals(8, ConstructionBatchRegistry.stepsFor(order));
        ConstructionBatchRegistry.clear(order);
        assertEquals(1, ConstructionBatchRegistry.stepsFor(order));
    }
}

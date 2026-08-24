package com.colonyautopilot.bridge.runtime;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OfflineProgressGateTest {
    @Test
    void requiresSustainedHealthAndReleasesImmediately() {
        OfflineProgressGate gate = new OfflineProgressGate(3);

        assertFalse(gate.update(true, true));
        assertFalse(gate.update(true, true));
        assertTrue(gate.update(true, true));
        assertFalse(gate.update(true, false));
        assertFalse(gate.update(true, true));
        assertFalse(gate.update(false, true));
    }
}

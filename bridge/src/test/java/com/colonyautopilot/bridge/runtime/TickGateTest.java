package com.colonyautopilot.bridge.runtime;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TickGateTest {
    @Test
    void admitsOnlyOnePollPerConfiguredInterval() {
        TickGate gate = new TickGate();

        assertTrue(gate.tryAcquire(100, 20));
        assertFalse(gate.tryAcquire(119, 20));
        assertTrue(gate.tryAcquire(120, 20));
    }

    @Test
    void resetSchedulesTheNextTickImmediately() {
        TickGate gate = new TickGate();
        assertTrue(gate.tryAcquire(100, 20));

        gate.reset();

        assertTrue(gate.tryAcquire(101, 20));
    }
}

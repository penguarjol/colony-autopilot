package com.colonyautopilot.bridge.runtime;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ConstructionDeadlinePolicyTest {
    private final ConstructionDeadlinePolicy policy =
        new ConstructionDeadlinePolicy(72_000, 12_000, 4, 64);

    @Test
    void usesNormalBuilderPathBeforeTheReconciliationWindow() {
        var decision = policy.decide(40_000, 20_000, true);

        assertEquals(ConstructionDeadlinePolicy.Phase.NORMAL, decision.phase());
        assertEquals(1, decision.stepsPerCall());
    }

    @Test
    void increasesVisibleBatchSizeInsideTheReconciliationWindow() {
        var decision = policy.decide(10_000, 66_000, true);

        assertEquals(ConstructionDeadlinePolicy.Phase.ACCELERATED, decision.phase());
        assertEquals(4, decision.stepsPerCall());
    }

    @Test
    void startsVerifiedReconciliationBeforeRemainingCapacityIsExhausted() {
        var decision = policy.decide(20_000, 66_000, true);

        assertEquals(ConstructionDeadlinePolicy.Phase.RECONCILING, decision.phase());
        assertEquals(64, decision.stepsPerCall());
    }

    @Test
    void entersVerifiedReconciliationAtTheDeadline() {
        var decision = policy.decide(2_000, 72_000, true);

        assertEquals(ConstructionDeadlinePolicy.Phase.RECONCILING, decision.phase());
        assertEquals(64, decision.stepsPerCall());
    }

    @Test
    void unhealthyRuntimePausesAllDeadlineWork() {
        var decision = policy.decide(2_000, 72_000, false);

        assertEquals(ConstructionDeadlinePolicy.Phase.PAUSED, decision.phase());
        assertEquals(0, decision.stepsPerCall());
    }
}

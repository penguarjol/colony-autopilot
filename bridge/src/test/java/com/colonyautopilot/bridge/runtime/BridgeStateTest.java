package com.colonyautopilot.bridge.runtime;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BridgeStateTest {
    private static final long DAY = 24_000L;

    @Test
    void spendsTheBudgetThenRefusesFurtherActions() {
        BridgeState state = new BridgeState();

        assertTrue(state.tryConsumeBudget(0, 2));
        assertTrue(state.tryConsumeBudget(0, 2));
        assertFalse(state.tryConsumeBudget(0, 2));
        assertEquals(2, state.actionsThisDay(0));
    }

    @Test
    void resetsOnTheMinecraftDayBoundary() {
        BridgeState state = new BridgeState();

        assertTrue(state.tryConsumeBudget(0, 1));
        assertFalse(state.tryConsumeBudget(0, 1));
        assertTrue(state.tryConsumeBudget(DAY, 1));
    }

    // Budget is consumed before the mutation runs, so a mutation that fails
    // afterwards was charging for work never done. Eight failing grants used to
    // exhaust a whole day's allowance and lock out every later action.
    @Test
    void refundsBudgetWhenTheActionFailsAfterConsuming() {
        BridgeState state = new BridgeState();

        assertTrue(state.tryConsumeBudget(0, 1));
        state.refundBudget(0);

        assertEquals(0, state.actionsThisDay(0));
        assertTrue(state.tryConsumeBudget(0, 1));
    }

    @Test
    void neverRefundsBelowZero() {
        BridgeState state = new BridgeState();

        state.refundBudget(0);

        assertEquals(0, state.actionsThisDay(0));
    }

    // A refund arriving after the day rolled must not credit the new day with
    // an action the previous day paid for.
    @Test
    void doesNotCreditANewDayForAnOlderFailure() {
        BridgeState state = new BridgeState();

        assertTrue(state.tryConsumeBudget(0, 4));
        assertTrue(state.tryConsumeBudget(DAY, 4));
        state.refundBudget(DAY);

        assertEquals(0, state.actionsThisDay(DAY));
    }
}

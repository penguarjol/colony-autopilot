package com.colonyautopilot.bridge.runtime;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VerificationRetryBudgetTest {
    @Test
    void reportsExhaustionOnlyAfterTheAttemptLimit() {
        VerificationRetryBudget<String> budget = new VerificationRetryBudget<>(3);

        assertFalse(budget.recordFailure("order-16"));
        assertFalse(budget.recordFailure("order-16"));
        assertTrue(budget.recordFailure("order-16"));
    }

    @Test
    void staysExhaustedOnceTheLimitIsReached() {
        VerificationRetryBudget<String> budget = new VerificationRetryBudget<>(2);

        assertFalse(budget.recordFailure("order-13"));
        assertTrue(budget.recordFailure("order-13"));
        assertTrue(budget.recordFailure("order-13"));
    }

    @Test
    void successResetsTheBudget() {
        VerificationRetryBudget<String> budget = new VerificationRetryBudget<>(2);

        assertFalse(budget.recordFailure("order-13"));
        budget.recordSuccess("order-13");
        assertFalse(budget.recordFailure("order-13"));
        assertTrue(budget.recordFailure("order-13"));
    }

    @Test
    void tracksEachKeyIndependently() {
        VerificationRetryBudget<String> budget = new VerificationRetryBudget<>(2);

        assertFalse(budget.recordFailure("order-13"));
        assertFalse(budget.recordFailure("order-16"));
        assertTrue(budget.recordFailure("order-13"));
        assertTrue(budget.recordFailure("order-16"));
    }

    @Test
    void forgettingAKeyReleasesItsState() {
        VerificationRetryBudget<String> budget = new VerificationRetryBudget<>(2);

        assertFalse(budget.recordFailure("order-13"));
        budget.forget("order-13");
        assertFalse(budget.recordFailure("order-13"));
    }

    @Test
    void anAttemptLimitBelowOneStillFailsTerminallyOnFirstFailure() {
        VerificationRetryBudget<String> budget = new VerificationRetryBudget<>(0);

        assertTrue(budget.recordFailure("order-13"));
    }
}

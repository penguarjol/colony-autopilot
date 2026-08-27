package com.colonyautopilot.bridge.runtime;

import java.util.HashMap;
import java.util.Map;

/**
 * Bounds how many consecutive times the same key may fail before the caller
 * should treat the failure as terminal.
 */
final class VerificationRetryBudget<K> {
    private final int maxAttempts;
    private final Map<K, Integer> failures = new HashMap<>();

    VerificationRetryBudget(int maxAttempts) {
        this.maxAttempts = maxAttempts;
    }

    /** Records a failure and reports whether the budget is now spent. */
    boolean recordFailure(K key) {
        int attempts = failures.merge(key, 1, Integer::sum);
        return attempts >= Math.max(1, maxAttempts);
    }

    void recordSuccess(K key) {
        failures.remove(key);
    }

    void forget(K key) {
        failures.remove(key);
    }
}

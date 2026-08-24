package com.colonyautopilot.bridge.runtime;

final class OfflineProgressGate {
    private final int recoveryChecks;
    private int healthyChecks;

    OfflineProgressGate(int recoveryChecks) {
        if (recoveryChecks < 1) {
            throw new IllegalArgumentException("recoveryChecks must be positive");
        }
        this.recoveryChecks = recoveryChecks;
    }

    boolean update(boolean requested, boolean healthy) {
        if (!requested || !healthy) {
            healthyChecks = 0;
            return false;
        }
        healthyChecks = Math.min(recoveryChecks, healthyChecks + 1);
        return healthyChecks >= recoveryChecks;
    }
}

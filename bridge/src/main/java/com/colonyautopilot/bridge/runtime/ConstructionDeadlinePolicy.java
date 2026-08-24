package com.colonyautopilot.bridge.runtime;

final class ConstructionDeadlinePolicy {
    private final long deadlineTicks;
    private final long reconciliationLeadTicks;
    private final int acceleratedSteps;
    private final int reconciliationSteps;

    ConstructionDeadlinePolicy(
        long deadlineTicks,
        long reconciliationLeadTicks,
        int acceleratedSteps,
        int reconciliationSteps
    ) {
        if (deadlineTicks < 1
            || reconciliationLeadTicks < 1
            || reconciliationLeadTicks >= deadlineTicks
            || acceleratedSteps < 1
            || reconciliationSteps < acceleratedSteps) {
            throw new IllegalArgumentException("invalid construction deadline policy");
        }
        this.deadlineTicks = deadlineTicks;
        this.reconciliationLeadTicks = reconciliationLeadTicks;
        this.acceleratedSteps = acceleratedSteps;
        this.reconciliationSteps = reconciliationSteps;
    }

    Decision decide(
        long remainingOperations,
        long healthyTicks,
        boolean healthy
    ) {
        if (!healthy) {
            return new Decision(Phase.PAUSED, 0);
        }
        long remainingTicks = Math.max(0, deadlineTicks - healthyTicks);
        long remainingControllerCalls = remainingTicks / 20;
        long reconciliationCapacity = multiplySaturated(
            remainingControllerCalls,
            reconciliationSteps
        );
        if (healthyTicks >= deadlineTicks
            || remainingOperations > reconciliationCapacity) {
            return new Decision(Phase.RECONCILING, reconciliationSteps);
        }
        if (healthyTicks >= deadlineTicks - reconciliationLeadTicks) {
            long expectedCalls = Math.max(1, remainingTicks / 10);
            int required = (int) Math.min(
                acceleratedSteps,
                Math.max(2, ceilDivide(remainingOperations, expectedCalls))
            );
            return new Decision(Phase.ACCELERATED, required);
        }
        return new Decision(Phase.NORMAL, 1);
    }

    long deadlineTicks() {
        return deadlineTicks;
    }

    private static long ceilDivide(long value, long divisor) {
        return Math.max(1, (value + divisor - 1) / divisor);
    }

    private static long multiplySaturated(long left, long right) {
        if (left == 0 || right == 0) {
            return 0;
        }
        return left > Long.MAX_VALUE / right
            ? Long.MAX_VALUE
            : left * right;
    }

    enum Phase {
        PAUSED,
        NORMAL,
        ACCELERATED,
        RECONCILING
    }

    record Decision(Phase phase, int stepsPerCall) {
    }
}

package com.colonyautopilot.bridge.runtime;

public final class TickGate {
    private long nextTick;

    public boolean tryAcquire(long currentTick, int intervalTicks) {
        if (currentTick < nextTick) {
            return false;
        }
        nextTick = currentTick + Math.max(1, intervalTicks);
        return true;
    }

    public void reset() {
        nextTick = 0;
    }
}

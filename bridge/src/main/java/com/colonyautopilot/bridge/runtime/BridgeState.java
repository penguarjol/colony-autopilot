package com.colonyautopilot.bridge.runtime;

public final class BridgeState {
    private long pausedUntilTick;
    private long budgetDay = Long.MIN_VALUE;
    private int actionsThisDay;

    public void pause(long currentTick, long durationTicks) {
        pausedUntilTick = Math.max(pausedUntilTick, currentTick + Math.max(0, durationTicks));
    }

    public void resume() {
        pausedUntilTick = 0;
    }

    public boolean isPaused(long currentTick) {
        return currentTick < pausedUntilTick;
    }

    public long pausedUntilTick() {
        return pausedUntilTick;
    }

    public int actionsThisDay(long gameTime) {
        rollDay(gameTime);
        return actionsThisDay;
    }

    public boolean tryConsumeBudget(long gameTime, int dailyBudget) {
        rollDay(gameTime);
        if (dailyBudget <= 0 || actionsThisDay >= dailyBudget) {
            return false;
        }
        actionsThisDay++;
        return true;
    }

    private void rollDay(long gameTime) {
        long currentDay = Math.floorDiv(gameTime, 24_000L);
        if (currentDay != budgetDay) {
            budgetDay = currentDay;
            actionsThisDay = 0;
        }
    }
}

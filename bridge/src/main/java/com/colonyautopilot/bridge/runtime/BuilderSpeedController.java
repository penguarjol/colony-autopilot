package com.colonyautopilot.bridge.runtime;

import com.colonyautopilot.bridge.config.BridgeServerConfig;
import com.minecolonies.api.colony.IColony;
import com.minecolonies.api.colony.IColonyManager;
import com.minecolonies.api.colony.ICitizenData;
import net.minecraft.server.MinecraftServer;

final class BuilderSpeedController implements AutoCloseable {
    private static final int CHECK_INTERVAL_TICKS = 100;
    private static final String BUILDER_JOB = "minecolonies:builder";

    private final MinecraftServer server;
    private final BridgeState bridgeState;
    private final TickGate tickGate = new TickGate();

    BuilderSpeedController(MinecraftServer server, BridgeState bridgeState) {
        this.server = server;
        this.bridgeState = bridgeState;
    }

    void tick() {
        if (!tickGate.tryAcquire(server.getTickCount(), CHECK_INTERVAL_TICKS)) {
            return;
        }

        float tickMillis = server.getAverageTickTime();
        double tps = tickMillis <= 0.0F
            ? 20.0
            : Math.min(20.0, 1_000.0 / tickMillis);
        boolean healthy = tps >= BridgeServerConfig.OFFLINE_MIN_TPS.get()
            && tickMillis <= BridgeServerConfig.OFFLINE_MAX_TICK_MILLIS.get();
        boolean enabled = BridgeServerConfig.ENABLED.get()
            && BridgeServerConfig.MODE.get() == BridgeServerConfig.Mode.ACTIVE
            && !bridgeState.isPaused(server.getTickCount());
        applyMultiplier(enabled, healthy);
    }

    @Override
    public void close() {
        applyMultiplier(false, false);
    }

    private void applyMultiplier(boolean enabled, boolean healthy) {
        int requested = BridgeServerConfig.BUILDER_SPEED_MULTIPLIER.get();
        for (IColony colony : IColonyManager.getInstance().getAllColonies()) {
            int multiplier = BuilderSpeedPolicy.effectiveMultiplier(
                requested,
                enabled && !colony.isColonyUnderAttack(),
                healthy
            );
            for (ICitizenData citizen : colony.getCitizenManager().getCitizens()) {
                var job = citizen.getJob();
                if (job == null
                    || !BUILDER_JOB.equals(job.getJobRegistryEntry().getKey().toString())) {
                    continue;
                }
                var workerAi = job.getWorkerAI();
                if (workerAi != null) {
                    workerAi.getStateAI().setTickRate(multiplier);
                }
            }
        }
    }
}

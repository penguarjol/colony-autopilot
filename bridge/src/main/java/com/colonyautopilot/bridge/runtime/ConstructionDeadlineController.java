package com.colonyautopilot.bridge.runtime;

import com.colonyautopilot.bridge.ColonyAutopilotBridge;
import com.colonyautopilot.bridge.config.BridgeServerConfig;
import com.minecolonies.api.colony.IColony;
import com.minecolonies.api.colony.IColonyManager;
import com.minecolonies.api.colony.workorders.IBuilderWorkOrder;
import com.minecolonies.core.colony.buildings.workerbuildings.BuildingBuilder;
import com.minecolonies.core.entity.ai.workers.AbstractEntityAIStructure;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public final class ConstructionDeadlineController implements AutoCloseable {
    private static final int CHECK_INTERVAL_TICKS = 20;
    private static final int ESTIMATED_STRUCTURE_PASSES = 2;

    private final MinecraftServer server;
    private final BridgeState bridgeState;
    private final TickGate tickGate = new TickGate();
    private final Set<IBuilderWorkOrder> registeredOrders = new HashSet<>();
    private final StructureDeadlineReconciler reconciler =
        new StructureDeadlineReconciler();

    private List<Status> statuses = List.of();

    ConstructionDeadlineController(
        MinecraftServer server,
        BridgeState bridgeState
    ) {
        this.server = server;
        this.bridgeState = bridgeState;
    }

    void tick() {
        if (!tickGate.tryAcquire(server.getTickCount(), CHECK_INTERVAL_TICKS)) {
            return;
        }

        ConstructionDeadlineSavedData data = server.overworld()
            .getDataStorage()
            .computeIfAbsent(
                ConstructionDeadlineSavedData::load,
                ConstructionDeadlineSavedData::new,
                ConstructionDeadlineSavedData.DATA_NAME
            );
        ConstructionDeadlinePolicy policy = policy();
        boolean runtimeHealthy = runtimeHealthy();
        Set<IBuilderWorkOrder> activeOrders = new HashSet<>();
        List<Status> currentStatuses = new ArrayList<>();

        for (IColony colony : IColonyManager.getInstance().getAllColonies()) {
            colony.getCitizenManager().getCitizens().forEach(citizen -> {
                var job = citizen.getJob();
                var workBuilding = citizen.getWorkBuilding();
                IBuilderWorkOrder order = workBuilding instanceof BuildingBuilder builder
                    ? builder.getWorkOrder()
                    : null;
                if (job == null
                    || order == null
                    || !order.isClaimed()
                    || !"minecolonies:builder".equals(
                        job.getJobRegistryEntry().getKey().toString()
                    )) {
                    return;
                }

                ConstructionDeadlineState.Key key =
                    new ConstructionDeadlineState.Key(
                        colony.getDimension().location().toString(),
                        colony.getID(),
                        order.getID()
                    );
                activeOrders.add(order);
                if (order.getBlueprint() == null
                    && colony.getWorld() instanceof ServerLevel level) {
                    order.loadBlueprint(level, blueprint -> {
                        if (blueprint == null) {
                            ColonyAutopilotBridge.LOGGER.warn(
                                "Could not load blueprint for colony {} work order {}",
                                colony.getID(),
                                order.getID()
                            );
                        }
                    });
                }
                long estimate = estimateOperations(order);
                ConstructionDeadlineState state = data.getOrCreate(
                    key,
                    job.getActionsDone(),
                    estimate
                );
                boolean eligible = BridgeServerConfig.CONSTRUCTION_DEADLINE_ENABLED.get()
                    && BridgeServerConfig.ENABLED.get()
                    && BridgeServerConfig.MODE.get() == BridgeServerConfig.Mode.ACTIVE
                    && !bridgeState.isPaused(server.getTickCount())
                    && !colony.isColonyUnderAttack()
                    && !citizen.isPaused()
                    && !citizen.isAsleep()
                    && citizen.getEntity().isPresent()
                    && !state.paused()
                    && runtimeHealthy;
                state.observe(
                    job.getActionsDone(),
                    estimate,
                    eligible,
                    CHECK_INTERVAL_TICKS
                );
                ConstructionDeadlinePolicy.Decision decision = policy.decide(
                    state.estimatedRemainingOperations(),
                    state.healthyTicks(),
                    eligible
                );
                String phase = decision.phase().name();
                int checked = 0;
                int repaired = 0;
                var workerAi = job.getWorkerAI();
                if (decision.phase()
                    == ConstructionDeadlinePolicy.Phase.RECONCILING
                    && colony.getWorld() instanceof ServerLevel level
                    && workerAi instanceof AbstractEntityAIStructure<?, ?> structureAi) {
                    StructureDeadlineReconciler.Result result = reconciler.tick(
                        level,
                        order,
                        structureAi,
                        state,
                        decision.stepsPerCall()
                    );
                    phase = result.status().name();
                    checked = result.checked();
                    repaired = result.repaired();
                    if (result.status()
                        == StructureDeadlineReconciler.Status.COMPLETED) {
                        data.remove(key);
                    }
                }
                if (decision.stepsPerCall() > 0) {
                    ConstructionBatchRegistry.setSteps(
                        order,
                        decision.stepsPerCall()
                    );
                } else {
                    ConstructionBatchRegistry.clear(order);
                }
                currentStatuses.add(new Status(
                    key.dimension(),
                    key.colonyId(),
                    key.workOrderId(),
                    state.healthyTicks() / 20,
                    policy.deadlineTicks() / 20,
                    state.estimatedRemainingOperations(),
                    phase,
                    state.placementProgress().phase().name(),
                    decision.stepsPerCall(),
                    state.reconciliationCursor(),
                    checked,
                    repaired,
                    state.paused()
                ));
            });
        }

        for (IBuilderWorkOrder order : registeredOrders) {
            if (!activeOrders.contains(order)) {
                ConstructionBatchRegistry.clear(order);
            }
        }
        registeredOrders.clear();
        registeredOrders.addAll(activeOrders);
        reconciler.retainOnly(activeOrders);
        data.changed();
        statuses = List.copyOf(currentStatuses);
    }

    public List<Status> status() {
        return statuses;
    }

    public boolean setPaused(int colonyId, int workOrderId, boolean paused) {
        ConstructionDeadlineState state = findState(colonyId, workOrderId);
        if (state == null) {
            return false;
        }
        state.setPaused(paused);
        savedData().changed();
        return true;
    }

    public boolean reset(int colonyId, int workOrderId) {
        boolean removed = statuses.stream()
            .filter(status -> status.colonyId() == colonyId)
            .filter(status -> status.workOrderId() == workOrderId)
            .findFirst()
            .map(status -> savedData().remove(new ConstructionDeadlineState.Key(
                status.dimension(),
                colonyId,
                workOrderId
            )))
            .orElse(false);
        if (removed) {
            reconciler.remove(colonyId, workOrderId);
        }
        return removed;
    }

    boolean isControlling(int colonyId, int workOrderId) {
        return statuses.stream().anyMatch(status ->
            status.colonyId() == colonyId
                && status.workOrderId() == workOrderId
                && ("RECONCILING".equals(status.phase())
                    || "VERIFYING".equals(status.phase())
                    || "WAITING_FOR_BLUEPRINT".equals(status.phase())
                    || "FAILED".equals(status.phase()))
        );
    }

    @Override
    public void close() {
        ConstructionBatchRegistry.clearAll();
        registeredOrders.clear();
        reconciler.retainOnly(List.of());
    }

    private ConstructionDeadlinePolicy policy() {
        long deadlineTicks =
            BridgeServerConfig.CONSTRUCTION_DEADLINE_MINUTES.get() * 1_200L;
        long requestedLead =
            BridgeServerConfig.CONSTRUCTION_RECONCILIATION_LEAD_MINUTES.get()
                * 1_200L;
        long leadTicks = Math.min(deadlineTicks - 1_200L, requestedLead);
        return new ConstructionDeadlinePolicy(
            deadlineTicks,
            Math.max(1_200L, leadTicks),
            BridgeServerConfig.CONSTRUCTION_ACCELERATED_STEPS.get(),
            BridgeServerConfig.CONSTRUCTION_RECONCILIATION_STEPS.get()
        );
    }

    private ConstructionDeadlineState findState(int colonyId, int workOrderId) {
        return statuses.stream()
            .filter(status -> status.colonyId() == colonyId)
            .filter(status -> status.workOrderId() == workOrderId)
            .findFirst()
            .map(status -> savedData().get(new ConstructionDeadlineState.Key(
                status.dimension(),
                colonyId,
                workOrderId
            )))
            .orElse(null);
    }

    private ConstructionDeadlineSavedData savedData() {
        return server.overworld().getDataStorage().computeIfAbsent(
            ConstructionDeadlineSavedData::load,
            ConstructionDeadlineSavedData::new,
            ConstructionDeadlineSavedData.DATA_NAME
        );
    }

    private boolean runtimeHealthy() {
        float tickMillis = server.getAverageTickTime();
        double tps = tickMillis <= 0.0F
            ? 20.0
            : Math.min(20.0, 1_000.0 / tickMillis);
        return tps >= BridgeServerConfig.OFFLINE_MIN_TPS.get()
            && tickMillis <= BridgeServerConfig.OFFLINE_MAX_TICK_MILLIS.get();
    }

    private static long estimateOperations(IBuilderWorkOrder order) {
        var blueprint = order.getBlueprint();
        if (blueprint == null) {
            return 1;
        }
        return Math.max(
            1L,
            (long) blueprint.getSizeX()
                * blueprint.getSizeY()
                * blueprint.getSizeZ()
                * ESTIMATED_STRUCTURE_PASSES
        );
    }

    public record Status(
        String dimension,
        int colonyId,
        int workOrderId,
        long healthySeconds,
        long deadlineSeconds,
        long estimatedRemainingOperations,
        String phase,
        String canonicalPhase,
        int stepsPerCall,
        long reconciliationCursor,
        int lastChecked,
        int lastRepaired,
        boolean paused
    ) {
    }
}

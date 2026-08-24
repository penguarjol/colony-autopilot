package com.colonyautopilot.bridge.runtime;

import com.colonyautopilot.bridge.ColonyAutopilotBridge;
import com.colonyautopilot.bridge.config.BridgeServerConfig;
import com.minecolonies.api.colony.IColony;
import com.minecolonies.api.colony.ICitizenData;
import com.minecolonies.api.colony.workorders.IBuilderWorkOrder;
import com.minecolonies.api.colony.workorders.WorkOrderType;
import com.minecolonies.core.colony.buildings.workerbuildings.BuildingBuilder;
import com.minecolonies.core.entity.ai.workers.AbstractEntityAIStructure;
import net.minecraft.server.level.ServerLevel;

import java.util.function.IntPredicate;

final class BuilderStallRecovery {
    private static final int CHECKS_PER_MINUTE = 12;

    private final ProgressStallDetector stalls;
    private int recoveryCount;
    private String lastRecovery = "none";

    BuilderStallRecovery() {
        stalls = new ProgressStallDetector(
            BridgeServerConfig.OFFLINE_STALL_MINUTES.get() * CHECKS_PER_MINUTE
        );
    }

    void tick(
        IColony colony,
        ServerLevel level,
        IntPredicate deadlineControlsWorkOrder
    ) {
        for (ICitizenData citizen : colony.getCitizenManager().getCitizens()) {
            recoverIfStalled(
                colony,
                level,
                citizen,
                deadlineControlsWorkOrder
            );
        }
    }

    int recoveryCount() {
        return recoveryCount;
    }

    String lastRecovery() {
        return lastRecovery;
    }

    private void recoverIfStalled(
        IColony colony,
        ServerLevel level,
        ICitizenData citizen,
        IntPredicate deadlineControlsWorkOrder
    ) {
        var job = citizen.getJob();
        var workerAi = job == null ? null : job.getWorkerAI();
        var workBuilding = citizen.getWorkBuilding();
        IBuilderWorkOrder order = workBuilding instanceof BuildingBuilder builder
            ? builder.getWorkOrder()
            : null;
        String key = level.dimension().location() + ":"
            + colony.getID() + ":" + citizen.getId();
        boolean eligible = workerAi instanceof AbstractEntityAIStructure<?, ?>
            && order != null
            && !deadlineControlsWorkOrder.test(order.getID())
            && "LOAD_STRUCTURE".equals(workerAi.getState().toString())
            && !citizen.isPaused()
            && !citizen.isAsleep()
            && citizen.getEntity().isPresent();
        String fingerprint = eligible
            ? workerAi.getState() + ":" + job.getActionsDone() + ":" + order.getID()
            : "";
        if (!stalls.update(key, fingerprint, eligible)) {
            return;
        }

        @SuppressWarnings("rawtypes")
        AbstractEntityAIStructure structureAi = (AbstractEntityAIStructure) workerAi;
        order.clearBlueprint();
        initializeStructure(structureAi, order);
        recordRecovery(colony, citizen, order);
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static void initializeStructure(
        AbstractEntityAIStructure structureAi,
        IBuilderWorkOrder order
    ) {
        structureAi.loadStructure(
            order,
            order.getLocation(),
            order.getWorkOrderType() == WorkOrderType.REMOVE
        );
    }

    private void recordRecovery(
        IColony colony,
        ICitizenData citizen,
        IBuilderWorkOrder order
    ) {
        recoveryCount++;
        lastRecovery = "colony=" + colony.getID()
            + ", citizen=" + citizen.getId()
            + ", workOrder=" + order.getID();
        ColonyAutopilotBridge.LOGGER.info(
            "Recovered stalled builder {} in colony {} for work order {}",
            citizen.getId(),
            colony.getID(),
            order.getID()
        );
    }
}

package com.colonyautopilot.bridge.runtime;

import com.colonyautopilot.bridge.ColonyAutopilotBridge;
import com.colonyautopilot.bridge.mixin.StructureAiAccessor;
import com.ldtteam.structurize.placement.StructurePlacer;
import com.minecolonies.api.colony.workorders.IBuilderWorkOrder;
import com.minecolonies.api.colony.workorders.WorkOrderType;
import com.minecolonies.core.entity.ai.workers.AbstractEntityAIStructure;
import net.minecraft.server.level.ServerLevel;

import java.util.IdentityHashMap;
import java.util.Map;

final class StructureDeadlineReconciler {
    private final Map<IBuilderWorkOrder, Session> sessions =
        new IdentityHashMap<>();

    Result tick(
        ServerLevel level,
        IBuilderWorkOrder order,
        AbstractEntityAIStructure<?, ?> structureAi,
        ConstructionDeadlineState state,
        int operationBudget
    ) {
        var blueprint = order.getBlueprint();
        if (blueprint == null) {
            return new Result(Status.WAITING_FOR_BLUEPRINT, 0, 0);
        }
        CanonicalPlacementProgress progress = state.placementProgress();
        if (order.getWorkOrderType() == WorkOrderType.REMOVE
            && progress.phase() == CanonicalPlacementPhase.SOLID) {
            progress = CanonicalPlacementProgress.startRemoval();
            state.setPlacementProgress(progress);
        }
        Session session = sessions.computeIfAbsent(order, ignored -> {
            var handler = new DeadlineCreativeStructureHandler(
                level,
                order,
                blueprint,
                structureAi,
                operationBudget
            );
            return new Session(handler, new StructurePlacer(handler, "default"));
        });

        if (progress.phase() == CanonicalPlacementPhase.VERIFYING) {
            BlueprintSemanticVerifier.Result verification =
                BlueprintSemanticVerifier.verify(
                    session.placer(),
                    progress,
                    operationBudget,
                    order.getWorkOrderType() == WorkOrderType.REMOVE
                );
            state.setPlacementProgress(verification.progress());
            if (verification.status() == BlueprintSemanticVerifier.Status.FAILED) {
                ColonyAutopilotBridge.LOGGER.error(
                    "Deadline verification failed for colony {} work order {} at {}",
                    order.getColony().getID(),
                    order.getID(),
                    verification.mismatchPosition()
                );
                return new Result(Status.FAILED, verification.checked(), 0);
            }
            if (verification.status() == BlueprintSemanticVerifier.Status.RUNNING) {
                return new Result(Status.VERIFYING, verification.checked(), 0);
            }
            if (!verification.progress().canComplete()) {
                return new Result(Status.FAILED, verification.checked(), 0);
            }
            session.handler().onCompletion();
            ((StructureAiAccessor) structureAi).colonyAutoCompleteBuild();
            sessions.remove(order);
            return new Result(Status.COMPLETED, verification.checked(), 0);
        }

        CanonicalStructurePhaseRunner.Result placement =
            CanonicalStructurePhaseRunner.run(
                level,
                session.placer(),
                progress,
                operationBudget
            );
        state.setPlacementProgress(placement.progress());
        int repaired = session.handler().consumeSuccessfulPlacements();
        if (placement.status() == CanonicalStructurePhaseRunner.Status.FAILED) {
            ColonyAutopilotBridge.LOGGER.error(
                "Canonical deadline phase {} failed for colony {} work order {}: {}",
                progress.phase(),
                order.getColony().getID(),
                order.getID(),
                placement.failure()
            );
            return new Result(Status.FAILED, operationBudget, repaired);
        }
        Status status = placement.progress().phase()
            == CanonicalPlacementPhase.VERIFYING
                ? Status.VERIFYING
                : Status.RECONCILING;
        return new Result(status, operationBudget, repaired);
    }

    void retainOnly(Iterable<IBuilderWorkOrder> activeOrders) {
        Map<IBuilderWorkOrder, Boolean> active = new IdentityHashMap<>();
        activeOrders.forEach(order -> active.put(order, Boolean.TRUE));
        sessions.keySet().removeIf(order -> !active.containsKey(order));
    }

    void remove(int colonyId, int workOrderId) {
        sessions.keySet().removeIf(order ->
            order.getColony().getID() == colonyId
                && order.getID() == workOrderId
        );
    }

    enum Status {
        WAITING_FOR_BLUEPRINT,
        RECONCILING,
        VERIFYING,
        COMPLETED,
        FAILED
    }

    record Result(Status status, int checked, int repaired) {
    }

    private record Session(
        DeadlineCreativeStructureHandler handler,
        StructurePlacer placer
    ) {
    }
}

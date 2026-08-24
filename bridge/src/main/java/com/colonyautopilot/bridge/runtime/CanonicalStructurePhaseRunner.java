package com.colonyautopilot.bridge.runtime;

import com.ldtteam.structurize.placement.AbstractBlueprintIterator;
import com.ldtteam.structurize.placement.BlockPlacementResult;
import com.ldtteam.structurize.placement.StructurePhasePlacementResult;
import com.ldtteam.structurize.placement.StructurePlacer;
import com.ldtteam.structurize.blocks.ModBlocks;
import com.ldtteam.structurize.util.BlockUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.AirBlock;

final class CanonicalStructurePhaseRunner {
    private CanonicalStructurePhaseRunner() {
    }

    static Result run(
        ServerLevel level,
        StructurePlacer placer,
        CanonicalPlacementProgress progress,
        int operationBudget
    ) {
        prepareIterator(placer, progress);
        StructurePhasePlacementResult placement = execute(level, placer, progress.phase());
        BlockPlacementResult.Result result = placement.getBlockResult().getResult();
        long completedSteps = progress.completedSteps() + Math.max(1, operationBudget);

        if (result == BlockPlacementResult.Result.FAIL
            || result == BlockPlacementResult.Result.MISSING_ITEMS
            || result == BlockPlacementResult.Result.BREAK_BLOCK) {
            return new Result(
                Status.FAILED,
                progress.withIteratorPosition(
                    placement.getIteratorPos(),
                    completedSteps
                ),
                result.name()
            );
        }
        if (result == BlockPlacementResult.Result.FINISHED) {
            placer.getIterator().reset();
            return new Result(
                Status.PHASE_COMPLETE,
                progress.nextPhase(),
                null
            );
        }
        return new Result(
            Status.RUNNING,
            progress.withIteratorPosition(
                placement.getIteratorPos(),
                completedSteps
            ),
            null
        );
    }

    private static void prepareIterator(
        StructurePlacer placer,
        CanonicalPlacementProgress progress
    ) {
        var iterator = placer.getIterator();
        if (progress.iteratorPosition() != null
            && !progress.iteratorPosition().equals(iterator.getProgressPos())) {
            iterator.setProgressPos(progress.iteratorPosition());
        }
        if (progress.phase() == CanonicalPlacementPhase.ENTITIES) {
            iterator.includeEntities();
        }
        if (progress.phase() == CanonicalPlacementPhase.REMOVE
            || progress.phase() == CanonicalPlacementPhase.REMOVE_WATER) {
            iterator.setRemoving();
        }
    }

    private static StructurePhasePlacementResult execute(
        ServerLevel level,
        StructurePlacer placer,
        CanonicalPlacementPhase phase
    ) {
        BlockPos current = placer.getIterator().getProgressPos();
        return switch (phase) {
            case SOLID -> placer.executeStructureStep(
                level,
                null,
                current,
                StructurePlacer.Operation.BLOCK_PLACEMENT,
                () -> placer.getIterator().increment((info, position, handler) ->
                    !BlockUtils.canBlockFloatInAir(info.getBlockInfo().getState())
                ),
                false
            );
            case WEAK_SOLID -> placer.executeStructureStep(
                level,
                null,
                current,
                StructurePlacer.Operation.BLOCK_PLACEMENT,
                () -> placer.getIterator().increment((info, position, handler) ->
                    !BlockUtils.isWeakSolidBlock(info.getBlockInfo().getState())
                ),
                false
            );
            case CLEAR_WATER ->
                placer.clearWaterStep(level, current);
            case REMOVE_WATER -> placer.executeStructureStep(
                level,
                null,
                current,
                StructurePlacer.Operation.WATER_REMOVAL,
                () -> placer.getIterator().decrement((info, position, handler) ->
                    info.getBlockInfo().getState().getFluidState().isEmpty()
                ),
                false
            );
            case NON_SOLID -> placer.executeStructureStep(
                level,
                null,
                current,
                StructurePlacer.Operation.BLOCK_PLACEMENT,
                () -> placer.getIterator().increment((info, position, handler) ->
                    BlockUtils.isAnySolid(info.getBlockInfo().getState())
                ),
                false
            );
            case ENTITIES -> placer.executeStructureStep(
                level,
                null,
                current,
                StructurePlacer.Operation.SPAWN_ENTITY,
                () -> placer.getIterator().increment((info, position, handler) ->
                    info.getEntities().length == 0
                ),
                true
            );
            case REMOVE -> placer.executeStructureStep(
                level,
                null,
                current,
                StructurePlacer.Operation.BLOCK_REMOVAL,
                () -> placer.getIterator().decrement(
                    CanonicalStructurePhaseRunner::skipRemoval
                ),
                true
            );
            case VERIFYING -> throw new IllegalStateException(
                "verification is not a placement phase"
            );
        };
    }

    static boolean skipRemoval(
        com.ldtteam.structurize.util.BlueprintPositionInfo info,
        BlockPos worldPosition,
        com.ldtteam.structurize.placement.structure.IStructureHandler handler
    ) {
        var blueprintState = info.getBlockInfo().getState();
        var blueprintBlock = blueprintState.getBlock();
        if (blueprintBlock instanceof AirBlock
            || blueprintState.is(ModBlocks.NULL_PLACEMENT)
            || blueprintBlock == ModBlocks.blockSubstitution.get()
            || blueprintBlock == ModBlocks.blockSolidSubstitution.get()
            || blueprintBlock == ModBlocks.blockFluidSubstitution.get()
            || blueprintBlock == ModBlocks.blockTagSubstitution.get()) {
            return true;
        }
        return handler.getWorld()
            .getBlockState(worldPosition)
            .getDestroySpeed(handler.getWorld(), worldPosition) < 0;
    }

    enum Status {
        RUNNING,
        PHASE_COMPLETE,
        FAILED
    }

    record Result(
        Status status,
        CanonicalPlacementProgress progress,
        String failure
    ) {
    }
}

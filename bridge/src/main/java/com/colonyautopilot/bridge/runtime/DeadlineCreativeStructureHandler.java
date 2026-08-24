package com.colonyautopilot.bridge.runtime;

import com.ldtteam.structurize.blueprints.v1.Blueprint;
import com.ldtteam.structurize.placement.structure.CreativeStructureHandler;
import com.ldtteam.structurize.util.PlacementSettings;
import com.minecolonies.api.colony.workorders.IBuilderWorkOrder;
import com.minecolonies.api.colony.buildings.IBuilding;
import com.minecolonies.api.util.BlockPosUtil;
import com.minecolonies.core.entity.ai.workers.AbstractEntityAIStructure;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.item.ItemStack;

import java.util.List;
import java.util.function.Function;

final class DeadlineCreativeStructureHandler extends CreativeStructureHandler {
    private final int stepsPerCall;
    private final AbstractEntityAIStructure<?, ?> structureAi;
    private final IBuilding building;
    private int successfulPlacements;

    DeadlineCreativeStructureHandler(
        ServerLevel level,
        IBuilderWorkOrder order,
        Blueprint blueprint,
        AbstractEntityAIStructure<?, ?> structureAi,
        int stepsPerCall
    ) {
        super(
            level,
            order.getLocation(),
            blueprint,
            new PlacementSettings(
                order.isMirrored() ? Mirror.FRONT_BACK : Mirror.NONE,
                BlockPosUtil.getRotationFromRotations(order.getRotation())
            ),
            true
        );
        this.structureAi = structureAi;
        this.stepsPerCall = Math.max(1, stepsPerCall);
        var registered = order.getColony()
            .getServerBuildingManager()
            .getBuilding(order.getLocation());
        building = registered;
    }

    @Override
    public int getStepsPerCall() {
        return stepsPerCall;
    }

    @Override
    public BlockState getSolidBlockForPos(BlockPos position) {
        return structureAi.getSolidSubstitution(position);
    }

    @Override
    public BlockState getSolidBlockForPos(
        BlockPos position,
        Function<BlockPos, BlockState> virtualBlocks
    ) {
        return structureAi.getSolidSubstitution(position);
    }

    @Override
    public boolean replaceWithSolidBlock(BlockState state) {
        return structureAi.shallReplaceSolidSubstitutionBlock(
            state.getBlock(),
            state
        );
    }

    @Override
    public void triggerSuccess(
        BlockPos position,
        List<ItemStack> requiredItems,
        boolean placement
    ) {
        super.triggerSuccess(position, requiredItems, placement);
        successfulPlacements++;
        if (building != null) {
            building.registerBlockPosition(
                getBluePrint().getBlockState(position),
                getProgressPosInWorld(position),
                getWorld()
            );
        }
    }

    @Override
    public void triggerEntitySuccess(
        BlockPos position,
        List<ItemStack> requiredItems,
        boolean placement
    ) {
        super.triggerEntitySuccess(position, requiredItems, placement);
        successfulPlacements++;
    }

    int consumeSuccessfulPlacements() {
        int result = successfulPlacements;
        successfulPlacements = 0;
        return result;
    }
}

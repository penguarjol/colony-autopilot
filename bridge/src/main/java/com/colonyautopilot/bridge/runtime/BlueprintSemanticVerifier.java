package com.colonyautopilot.bridge.runtime;

import com.colonyautopilot.bridge.ColonyAutopilotBridge;
import com.ldtteam.structurize.placement.AbstractBlueprintIterator;
import com.ldtteam.structurize.placement.StructurePlacer;
import com.ldtteam.structurize.placement.handlers.placement.IPlacementHandler;
import com.ldtteam.structurize.placement.handlers.placement.PlacementHandlers;
import com.ldtteam.structurize.util.BlockInfo;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.util.Tuple;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.decoration.HangingEntity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.lang.reflect.InvocationTargetException;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

final class BlueprintSemanticVerifier {
    private BlueprintSemanticVerifier() {
    }

    static Result verify(
        StructurePlacer placer,
        CanonicalPlacementProgress progress,
        int operationBudget,
        boolean removal
    ) {
        var iterator = placer.getIterator();
        if (removal) {
            iterator.setRemoving();
        } else {
            iterator.includeEntities();
        }
        if (progress.iteratorPosition() != null
            && !progress.iteratorPosition().equals(iterator.getProgressPos())) {
            iterator.setProgressPos(progress.iteratorPosition());
        }

        int checked = 0;
        while (checked < Math.max(1, operationBudget)) {
            AbstractBlueprintIterator.Result iteratorResult =
                removal
                    ? iterator.decrement(
                        CanonicalStructurePhaseRunner::skipRemoval
                    )
                    : iterator.increment((info, position, handler) -> false);
            if (iteratorResult == AbstractBlueprintIterator.Result.AT_END) {
                return new Result(
                    Status.PASSED,
                    progress.verificationPassed(),
                    checked,
                    null
                );
            }
            if (iteratorResult == AbstractBlueprintIterator.Result.CONFIG_LIMIT) {
                return running(progress, iterator, checked);
            }

            checked++;
            BlockPos localPosition = iterator.getProgressPos();
            BlockInfo block = iterator
                .getBluePrintPositionInfo(localPosition)
                .getBlockInfo();
            BlockPos worldPosition = placer
                .getHandler()
                .getProgressPosInWorld(localPosition);
            if (removal) {
                if (placer.getHandler().getWorld().getBlockState(worldPosition).isAir()) {
                    continue;
                }
                return failed(progress, iterator, checked, worldPosition);
            }
            BlockState expectedState = block.getState();
            if (!VerifiableBlockState.isVerifiable(
                BuiltInRegistries.BLOCK.getKey(expectedState.getBlock()).toString(),
                properties(expectedState)
            )) {
                continue;
            }
            boolean canonicalBlockMatch = IPlacementHandler
                .doesWorldStateMatchBlueprintState(
                    block,
                    worldPosition,
                    placer.getHandler()
                );
            boolean contextualBlockMatch = matchesContextualState(
                placer,
                block,
                localPosition,
                worldPosition
            );
            boolean entityMatch = entitiesMatch(placer, localPosition);
            if ((!canonicalBlockMatch && !contextualBlockMatch) || !entityMatch) {
                ColonyAutopilotBridge.LOGGER.error(
                    "Semantic deadline mismatch at {} local {}: expected={}, actual={}, "
                        + "tile={}, entities={}, canonicalBlockMatch={}, "
                        + "contextualBlockMatch={}, entityMatch={}",
                    worldPosition,
                    localPosition,
                    block.getState(),
                    placer.getHandler().getWorld().getBlockState(worldPosition),
                    block.hasTileEntityData(),
                    placer.getIterator()
                        .getBluePrintPositionInfo(localPosition)
                        .getEntities()
                        .length,
                    canonicalBlockMatch,
                    contextualBlockMatch,
                    entityMatch
                );
                return failed(progress, iterator, checked, worldPosition);
            }
        }
        return running(progress, iterator, checked);
    }

    private static Result failed(
        CanonicalPlacementProgress progress,
        AbstractBlueprintIterator iterator,
        int checked,
        BlockPos worldPosition
    ) {
        return new Result(
            Status.FAILED,
            progress.withIteratorPosition(
                iterator.getPrevProgressPos(),
                progress.completedSteps() + checked
            ),
            checked,
            worldPosition
        );
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static boolean entitiesMatch(
        StructurePlacer placer,
        BlockPos localPosition
    ) {
        var handler = placer.getHandler();
        var tags = placer.getIterator()
            .getBluePrintPositionInfo(localPosition)
            .getEntities();
        BlockPos origin = handler.getCenterPos().subtract(
            handler.getBluePrint().getPrimaryBlockOffset()
        );
        for (var tag : tags) {
            try {
                var entityType = EntityType.by(tag);
                if (entityType.isEmpty()) {
                    return false;
                }
                Entity expected = entityType.get().create(handler.getWorld());
                if (expected == null) {
                    return false;
                }
                expected.deserializeNBT(tag);
                Vec3 position = expected.position().add(
                    origin.getX(),
                    origin.getY(),
                    origin.getZ()
                );
                if (expected instanceof HangingEntity hanging) {
                    position = position.subtract(
                        Vec3.atLowerCornerOf(
                            hanging.blockPosition().subtract(hanging.getPos())
                        )
                    );
                }
                AABB search = new AABB(
                    position.add(1, 1, 1),
                    position.add(-1, -1, -1)
                );
                var matches = handler.getWorld().getEntitiesOfClass(
                    (Class) expected.getClass(),
                    search
                );
                Vec3 expectedPosition = position;
                boolean found = matches.stream().anyMatch(entity ->
                    ((Entity) entity).position().equals(expectedPosition)
                );
                if (!found) {
                    return false;
                }
            } catch (RuntimeException exception) {
                return false;
            }
        }
        return true;
    }

    private static Result running(
        CanonicalPlacementProgress progress,
        AbstractBlueprintIterator iterator,
        int checked
    ) {
        return new Result(
            Status.RUNNING,
            progress.withIteratorPosition(
                iterator.getProgressPos(),
                progress.completedSteps() + checked
            ),
            checked,
            null
        );
    }

    private static boolean matchesContextualState(
        StructurePlacer placer,
        BlockInfo block,
        BlockPos localPosition,
        BlockPos worldPosition
    ) {
        var handler = placer.getHandler();
        var level = handler.getWorld();
        BlockState expected = block.getState();
        BlockState actual = level.getBlockState(worldPosition);
        String expectedId = BuiltInRegistries.BLOCK.getKey(expected.getBlock()).toString();
        String actualId = BuiltInRegistries.BLOCK.getKey(actual.getBlock()).toString();
        var blueprint = handler.getBluePrint();
        boolean exteriorAir = "minecraft:air".equals(expectedId)
            && "minecraft:water".equals(actualId)
            && BlueprintAirConnectivity.isExterior(
                localPosition,
                new BlockPos(
                    blueprint.getSizeX(),
                    blueprint.getSizeY(),
                    blueprint.getSizeZ()
                ),
                position -> blueprint.getBlockState(position).isAir()
            );
        if (ContextualBlockStateEquivalence.matchesExteriorWater(
            expectedId,
            actualId,
            exteriorAir
        )) {
            return true;
        }
        if (expected.equals(actual)
            && matchesContextualBlockEntity(level.getBlockEntity(worldPosition), block)) {
            return true;
        }
        String contextualProperty;
        if (ContextualBlockStateEquivalence.matches(
            expectedId,
            properties(expected),
            actualId,
            properties(actual)
        )) {
            contextualProperty = "column";
        } else if (expected.getBlock() instanceof LeavesBlock
            && ContextualBlockStateEquivalence.matchesLeaves(
                expectedId,
                properties(expected),
                actualId,
                properties(actual)
            )) {
            contextualProperty = LeavesBlock.DISTANCE.getName();
        } else {
            return false;
        }

        BlockState normalized = copyProperty(
            actual,
            expected,
            contextualProperty
        );
        IPlacementHandler placementHandler = PlacementHandlers.getHandler(
            level,
            worldPosition,
            expected
        );
        Tuple<BlockEntity, net.minecraft.nbt.CompoundTag> tileData = null;
        if (block.hasTileEntityData()) {
            BlockEntity blockEntity = level.getBlockEntity(worldPosition);
            if (blockEntity == null) {
                return false;
            }
            tileData = new Tuple<>(blockEntity, block.getTileEntityData());
        }
        return placementHandler.doesWorldStateMatchBlueprintState(
            normalized,
            expected,
            tileData,
            handler
        );
    }

    private static boolean matchesContextualBlockEntity(
        BlockEntity worldBlockEntity,
        BlockInfo blueprintBlock
    ) {
        if (worldBlockEntity == null || !blueprintBlock.hasTileEntityData()) {
            return false;
        }
        CompoundTag blueprint = blueprintBlock.getTileEntityData();
        CompoundTag world = worldBlockEntity.saveWithFullMetadata();
        return ContextualBlockEntityEquivalence.matchesMaterialTextures(
            blueprint.getString("id"),
            stringValues(blueprint.getCompound("textureData")),
            world.getString("id"),
            stringValues(world.getCompound("textureData")),
            materiallyTexturedComponents(blueprintBlock.getState().getBlock())
        );
    }

    private static Set<String> materiallyTexturedComponents(Block block) {
        try {
            Object value = block.getClass().getMethod("getComponents").invoke(block);
            if (!(value instanceof Collection<?> components)) {
                return Set.of();
            }
            Set<String> result = new HashSet<>();
            for (Object component : components) {
                Object id = component.getClass().getMethod("getId").invoke(component);
                result.add(id.toString());
            }
            return result;
        } catch (NoSuchMethodException
                 | IllegalAccessException
                 | InvocationTargetException exception) {
            return Set.of();
        }
    }

    private static Map<String, String> stringValues(CompoundTag tag) {
        Map<String, String> result = new LinkedHashMap<>();
        for (String key : tag.getAllKeys()) {
            result.put(key, tag.getString(key));
        }
        return result;
    }

    private static Map<String, String> properties(BlockState state) {
        Map<String, String> result = new LinkedHashMap<>();
        for (Property<?> property : state.getProperties()) {
            result.put(property.getName(), propertyValue(state, property));
        }
        return result;
    }

    private static <T extends Comparable<T>> String propertyValue(
        BlockState state,
        Property<T> property
    ) {
        return property.getName(state.getValue(property));
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static BlockState copyProperty(
        BlockState target,
        BlockState source,
        String propertyName
    ) {
        Property sourceProperty = source.getBlock()
            .getStateDefinition()
            .getProperty(propertyName);
        Property targetProperty = target.getBlock()
            .getStateDefinition()
            .getProperty(propertyName);
        if (sourceProperty == null || targetProperty == null) {
            return target;
        }
        Comparable value = source.getValue(sourceProperty);
        return target.setValue(targetProperty, value);
    }

    enum Status {
        RUNNING,
        PASSED,
        FAILED
    }

    record Result(
        Status status,
        CanonicalPlacementProgress progress,
        int checked,
        BlockPos mismatchPosition
    ) {
    }
}

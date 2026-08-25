package com.colonyautopilot.bridge.runtime;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;

import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.Set;
import java.util.function.Predicate;

final class BlueprintAirConnectivity {
    private static final Direction[] SEARCH_DIRECTIONS = {
        Direction.NORTH,
        Direction.SOUTH,
        Direction.WEST,
        Direction.EAST,
        Direction.UP,
        Direction.DOWN
    };

    private BlueprintAirConnectivity() {
    }

    static boolean isExterior(
        BlockPos start,
        BlockPos size,
        Predicate<BlockPos> isAir
    ) {
        if (!inside(start, size) || !isAir.test(start)) {
            return false;
        }
        ArrayDeque<BlockPos> pending = new ArrayDeque<>();
        Set<BlockPos> visited = new HashSet<>();
        pending.add(start);
        visited.add(start);

        while (!pending.isEmpty()) {
            BlockPos position = pending.removeFirst();
            if (isHorizontalBoundary(position, size)) {
                return true;
            }
            for (Direction direction : SEARCH_DIRECTIONS) {
                BlockPos neighbor = position.relative(direction);
                if (inside(neighbor, size)
                    && visited.add(neighbor)
                    && isAir.test(neighbor)) {
                    pending.addLast(neighbor);
                }
            }
        }
        return false;
    }

    private static boolean inside(BlockPos position, BlockPos size) {
        return position.getX() >= 0
            && position.getX() < size.getX()
            && position.getY() >= 0
            && position.getY() < size.getY()
            && position.getZ() >= 0
            && position.getZ() < size.getZ();
    }

    private static boolean isHorizontalBoundary(BlockPos position, BlockPos size) {
        return position.getX() == 0
            || position.getX() == size.getX() - 1
            || position.getZ() == 0
            || position.getZ() == size.getZ() - 1;
    }
}

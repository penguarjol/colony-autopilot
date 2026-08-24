package com.colonyautopilot.bridge.runtime;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

final class OfflineChunkPlan {
    static List<ChunkCoordinate> around(
        List<BlockCoordinate> anchors,
        int radius,
        int maximumChunks
    ) {
        if (radius < 0 || maximumChunks < 1) {
            throw new IllegalArgumentException("invalid offline chunk plan bounds");
        }

        Set<ChunkCoordinate> chunks = new LinkedHashSet<>();
        for (BlockCoordinate anchor : anchors) {
            int centerX = Math.floorDiv(anchor.x(), 16);
            int centerZ = Math.floorDiv(anchor.z(), 16);
            for (int x = centerX - radius; x <= centerX + radius; x++) {
                for (int z = centerZ - radius; z <= centerZ + radius; z++) {
                    chunks.add(new ChunkCoordinate(x, z));
                    if (chunks.size() > maximumChunks) {
                        return List.of();
                    }
                }
            }
        }
        return List.copyOf(chunks);
    }

    record BlockCoordinate(int x, int z) {
    }

    record ChunkCoordinate(int x, int z) {
    }

    private OfflineChunkPlan() {
    }
}

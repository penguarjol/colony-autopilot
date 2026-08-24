package com.colonyautopilot.bridge.runtime;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OfflineChunkPlanTest {
    @Test
    void includesCitizenAndWorksiteNeighborhoodsWithoutDuplicates() {
        var plan = OfflineChunkPlan.around(
            List.of(
                new OfflineChunkPlan.BlockCoordinate(109, -1),
                new OfflineChunkPlan.BlockCoordinate(133, -15)
            ),
            1,
            32
        );

        assertEquals(15, plan.size());
        assertTrue(plan.contains(new OfflineChunkPlan.ChunkCoordinate(6, -1)));
        assertTrue(plan.contains(new OfflineChunkPlan.ChunkCoordinate(8, -1)));
        assertTrue(plan.contains(new OfflineChunkPlan.ChunkCoordinate(7, -1)));
    }

    @Test
    void refusesPlansThatExceedTheChunkBudget() {
        var anchors = List.of(
            new OfflineChunkPlan.BlockCoordinate(0, 0),
            new OfflineChunkPlan.BlockCoordinate(1_000, 1_000)
        );

        assertEquals(List.of(), OfflineChunkPlan.around(anchors, 1, 17));
    }
}

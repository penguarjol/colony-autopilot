package com.colonyautopilot.bridge.runtime;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
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

    // Returning nothing when the budget is exceeded left the colony frozen: the
    // activator held a lease, reported "offline colony progression active", and
    // force-loaded zero chunks, so no citizen ticked until a player logged in.
    // Spending the whole budget is always better than spending none of it.
    @Test
    void spendsTheWholeBudgetWhenAnchorsExceedIt() {
        var anchors = List.of(
            new OfflineChunkPlan.BlockCoordinate(0, 0),
            new OfflineChunkPlan.BlockCoordinate(1_000, 1_000)
        );

        var plan = OfflineChunkPlan.around(anchors, 1, 17);

        assertEquals(17, plan.size());
        assertEquals(Set.copyOf(plan).size(), plan.size());
    }

    // Anchor order is the priority order, and the colony center is first. A
    // truncated plan that dropped the center would keep the town hall unloaded
    // and defeat the point of leasing at all.
    @Test
    void keepsTheEarliestAnchorsWhenTruncating() {
        var center = new OfflineChunkPlan.BlockCoordinate(0, 0);
        var distant = new OfflineChunkPlan.BlockCoordinate(1_000, 1_000);

        var plan = OfflineChunkPlan.around(List.of(center, distant), 1, 10);

        assertEquals(10, plan.size());
        for (int x = -1; x <= 1; x++) {
            for (int z = -1; z <= 1; z++) {
                assertTrue(
                    plan.contains(new OfflineChunkPlan.ChunkCoordinate(x, z)),
                    "expected the colony center neighbourhood to survive truncation"
                );
            }
        }
    }

    @Test
    void returnsEveryChunkWhenThePlanExactlyFitsTheBudget() {
        var plan = OfflineChunkPlan.around(
            List.of(new OfflineChunkPlan.BlockCoordinate(0, 0)),
            1,
            9
        );

        assertEquals(9, plan.size());
    }

    @Test
    void rejectsNonsensicalBounds() {
        var anchors = List.of(new OfflineChunkPlan.BlockCoordinate(0, 0));

        assertThrows(
            IllegalArgumentException.class,
            () -> OfflineChunkPlan.around(anchors, -1, 8)
        );
        assertThrows(
            IllegalArgumentException.class,
            () -> OfflineChunkPlan.around(anchors, 1, 0)
        );
    }
}

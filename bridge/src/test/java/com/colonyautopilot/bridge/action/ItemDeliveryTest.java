package com.colonyautopilot.bridge.action;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ItemDeliveryTest {
    @Test
    void requiresCapacityForTheEntireDelivery() {
        assertFalse(ItemDelivery.allInserted(1, List.of(count -> count)));
        assertTrue(ItemDelivery.allInserted(
            1,
            List.of(count -> count, count -> 0)
        ));
    }

    @Test
    void selectsTheSameSingleTargetAsBuildingForceTransfer() {
        assertEquals(
            "hut",
            ItemDelivery.selectTransferTarget(
                "hut",
                List.of("first-chest", "second-chest"),
                ignored -> true
            )
        );
        assertEquals(
            "second-chest",
            ItemDelivery.selectTransferTarget(
                null,
                List.of("full-chest", "second-chest"),
                candidate -> candidate.startsWith("second")
            )
        );
        assertNull(ItemDelivery.selectTransferTarget(
            null,
            List.of("full-chest"),
            ignored -> false
        ));
    }
}

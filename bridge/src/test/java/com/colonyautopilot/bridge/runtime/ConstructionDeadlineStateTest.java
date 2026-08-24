package com.colonyautopilot.bridge.runtime;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class ConstructionDeadlineStateTest {
    @Test
    void countsOnlyEligibleHealthyTicks() {
        var state = new ConstructionDeadlineState(
            new ConstructionDeadlineState.Key("minecraft:overworld", 1, 7),
            100,
            5_000
        );

        state.observe(100, 5_000, true, 20);
        state.observe(100, 5_000, false, 20);
        state.observe(101, 5_000, true, 20);

        assertEquals(40, state.healthyTicks());
        assertEquals(101, state.lastActionsDone());
        assertEquals(4_999, state.estimatedRemainingOperations());
    }

    @Test
    void roundTripsPersistedDeadlineState() {
        var state = new ConstructionDeadlineState(
            new ConstructionDeadlineState.Key("minecraft:overworld", 2, 9),
            44,
            12_000
        );
        state.observe(54, 12_000, true, 400);
        state.setReconciliationCursor(73);
        state.setPlacementProgress(new CanonicalPlacementProgress(
            CanonicalPlacementPhase.WEAK_SOLID,
            new BlockPos(3, 4, 5),
            73,
            false
        ));

        CompoundTag encoded = state.save();
        ConstructionDeadlineState decoded = ConstructionDeadlineState.load(encoded);

        assertEquals(state.key(), decoded.key());
        assertEquals(400, decoded.healthyTicks());
        assertEquals(11_990, decoded.estimatedRemainingOperations());
        assertEquals(73, decoded.reconciliationCursor());
        assertEquals(
            CanonicalPlacementPhase.WEAK_SOLID,
            decoded.placementProgress().phase()
        );
        assertEquals(
            new BlockPos(3, 4, 5),
            decoded.placementProgress().iteratorPosition()
        );
    }

    @Test
    void legacyStateStartsAtTheFirstCanonicalPhase() {
        CompoundTag legacy = new ConstructionDeadlineState(
            new ConstructionDeadlineState.Key("minecraft:overworld", 2, 9),
            44,
            12_000
        ).save();
        legacy.remove("placementPhase");

        ConstructionDeadlineState decoded = ConstructionDeadlineState.load(legacy);

        assertEquals(
            CanonicalPlacementPhase.SOLID,
            decoded.placementProgress().phase()
        );
    }

    @Test
    void savedDataRetainsJobsAcrossWorldReloads() {
        var data = new ConstructionDeadlineSavedData();
        var key = new ConstructionDeadlineState.Key("minecraft:overworld", 3, 11);
        var state = data.getOrCreate(key, 7, 9_000);
        state.observe(9, 9_000, true, 200);

        var loaded = ConstructionDeadlineSavedData.load(data.save(new CompoundTag()));
        var restored = loaded.get(key);

        assertNotNull(restored);
        assertEquals(200, restored.healthyTicks());
        assertEquals(8_998, restored.estimatedRemainingOperations());
    }
}

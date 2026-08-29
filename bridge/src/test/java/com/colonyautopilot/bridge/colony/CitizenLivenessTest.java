package com.colonyautopilot.bridge.colony;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CitizenLivenessTest {
    @Test
    void reportsAHealthyCitizen() {
        assertEquals("live", CitizenLiveness.diagnose(true, true, true, true));
    }

    // The failure this exists to name: MineColonies reports the entity as
    // present, so the activator refuses to respawn it, but the entity is not
    // actually in the level and therefore never ticks.
    @Test
    void namesAPresentButAbsentEntity() {
        assertEquals(
            "claimed present but not in level",
            CitizenLiveness.diagnose(true, false, true, true)
        );
    }

    // A chunk can be loaded for data access while still not ticking entities,
    // which looks identical from the outside to a frozen colony.
    @Test
    void namesALoadedChunkThatDoesNotTickEntities() {
        assertEquals(
            "in level but chunk is not entity ticking",
            CitizenLiveness.diagnose(true, true, true, false)
        );
    }

    @Test
    void namesAnUnloadedChunk() {
        assertEquals(
            "chunk not loaded",
            CitizenLiveness.diagnose(true, false, false, false)
        );
    }

    @Test
    void namesAnAbsentEntity() {
        assertEquals(
            "no entity",
            CitizenLiveness.diagnose(false, false, true, true)
        );
    }
}

package com.colonyautopilot.bridge.runtime;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OfflineCitizenSpawnPolicyTest {
    @Test
    void respawnsMissingCitizensDuringSafeOfflineProgress() {
        assertTrue(OfflineCitizenSpawnPolicy.shouldSpawn(true, false, false, true, true));
        assertFalse(OfflineCitizenSpawnPolicy.shouldSpawn(false, false, false, true, true));
        assertFalse(OfflineCitizenSpawnPolicy.shouldSpawn(true, true, false, true, true));
        assertFalse(OfflineCitizenSpawnPolicy.shouldSpawn(true, false, false, false, true));
    }

    // An entity is only ticked if it is in the level's entity tick list, and it
    // joins that list when it is added to a chunk that already ticks entities.
    // A citizen loaded before the lease applied its chunk ticket therefore sits
    // in the level, inside a now-ticking chunk, and never runs. Nothing detects
    // that from outside, so each citizen is re-added once per lease.
    @Test
    void refreshesAPresentCitizenOncePerLease() {
        assertTrue(OfflineCitizenSpawnPolicy.shouldSpawn(true, false, true, true, false));
        assertFalse(OfflineCitizenSpawnPolicy.shouldSpawn(true, false, true, true, true));
    }

    @Test
    void neverRefreshesWhileTheColonyIsUnderAttack() {
        assertFalse(OfflineCitizenSpawnPolicy.shouldSpawn(true, true, true, true, false));
    }

    @Test
    void neverRefreshesWithoutALease() {
        assertFalse(OfflineCitizenSpawnPolicy.shouldSpawn(false, false, true, true, false));
    }
}

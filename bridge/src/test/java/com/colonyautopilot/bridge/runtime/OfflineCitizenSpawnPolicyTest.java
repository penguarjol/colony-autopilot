package com.colonyautopilot.bridge.runtime;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OfflineCitizenSpawnPolicyTest {
    @Test
    void respawnsOnlyMissingCitizensDuringSafeOfflineProgress() {
        assertTrue(OfflineCitizenSpawnPolicy.shouldSpawn(true, false, false, true));
        assertFalse(OfflineCitizenSpawnPolicy.shouldSpawn(false, false, false, true));
        assertFalse(OfflineCitizenSpawnPolicy.shouldSpawn(true, true, false, true));
        assertFalse(OfflineCitizenSpawnPolicy.shouldSpawn(true, false, true, true));
        assertFalse(OfflineCitizenSpawnPolicy.shouldSpawn(true, false, false, false));
    }
}

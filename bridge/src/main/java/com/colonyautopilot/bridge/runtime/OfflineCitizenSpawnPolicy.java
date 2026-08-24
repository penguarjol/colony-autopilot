package com.colonyautopilot.bridge.runtime;

final class OfflineCitizenSpawnPolicy {
    static boolean shouldSpawn(
        boolean leaseActive,
        boolean underAttack,
        boolean entityPresent,
        boolean hasLastPosition
    ) {
        return leaseActive
            && !underAttack
            && !entityPresent
            && hasLastPosition;
    }

    private OfflineCitizenSpawnPolicy() {
    }
}

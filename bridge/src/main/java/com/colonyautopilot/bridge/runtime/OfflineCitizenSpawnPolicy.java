package com.colonyautopilot.bridge.runtime;

final class OfflineCitizenSpawnPolicy {
    /**
     * Decides whether a citizen should be spawned or re-added under an offline
     * lease.
     *
     * <p>A missing citizen is spawned. A citizen that is already present is
     * re-added once per lease: an entity only ticks if it is in the level's
     * entity tick list, which it joins when added to a chunk that already ticks
     * entities, so a citizen loaded before the lease applied its chunk ticket
     * stays in the level and never runs. No API exposes tick-list membership,
     * so the refresh is unconditional but happens once.
     */
    static boolean shouldSpawn(
        boolean leaseActive,
        boolean underAttack,
        boolean entityPresent,
        boolean hasLastPosition,
        boolean alreadyRefreshed
    ) {
        if (!leaseActive || underAttack || !hasLastPosition) {
            return false;
        }
        return !entityPresent || !alreadyRefreshed;
    }

    private OfflineCitizenSpawnPolicy() {
    }
}

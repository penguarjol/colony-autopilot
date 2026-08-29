package com.colonyautopilot.bridge.runtime;

/**
 * Describes the offline progression state for the status payload.
 *
 * <p>Holding a lease is not the same as making progress. A lease with no
 * force-loaded chunks ticks no citizens, so the two cases are reported
 * separately rather than both as "active".
 */
final class OfflineProgressReport {
    private OfflineProgressReport() {
    }

    static String describe(int leaseCount, int tickingChunks) {
        if (leaseCount <= 0) {
            return "no offline eligible colonies";
        }
        if (tickingChunks <= 0) {
            return "leased but no chunks are force-loaded";
        }
        return "offline colony progression active";
    }
}

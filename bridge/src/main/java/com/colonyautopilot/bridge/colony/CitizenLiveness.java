package com.colonyautopilot.bridge.colony;

/**
 * Names why a citizen is or is not able to tick.
 *
 * <p>"Present" in MineColonies' data model, "in the level", and "in a chunk
 * that ticks entities" are three different things, and only the last one makes
 * a citizen actually run. Collapsing them into a single boolean hid a colony
 * that was frozen for days behind a healthy-looking status.
 */
final class CitizenLiveness {
    private CitizenLiveness() {
    }

    static String diagnose(
        boolean entityPresent,
        boolean entityInLevel,
        boolean chunkLoaded,
        boolean chunkEntityTicking
    ) {
        if (!chunkLoaded) {
            return "chunk not loaded";
        }
        if (!entityPresent) {
            return "no entity";
        }
        if (!entityInLevel) {
            return "claimed present but not in level";
        }
        if (!chunkEntityTicking) {
            return "in level but chunk is not entity ticking";
        }
        return "live";
    }
}

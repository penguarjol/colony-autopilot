package com.colonyautopilot.bridge.colony;

/**
 * Reports which branch of MineColonies' colony state machine the colony is on.
 *
 * <p>Only the ACTIVE state runs work order assignment, so a colony can hold a
 * lease, load chunks and tick entities while still never building anything.
 * Mirrors {@code Colony.updateState}: a close subscriber activates outright,
 * an important player activates only past 40 claimed loaded chunks, and
 * anything less is inactive.
 */
final class ColonyActivationState {
    private static final int ACTIVATION_CHUNK_THRESHOLD = 40;

    private ColonyActivationState() {
    }

    static String describe(
        int closeSubscribers,
        int importantPlayers,
        int loadedChunks
    ) {
        if (closeSubscribers > 0
            || (loadedChunks > ACTIVATION_CHUNK_THRESHOLD && importantPlayers > 0)) {
            return "ACTIVE";
        }
        if (importantPlayers > 0) {
            return "UNLOADED";
        }
        return "INACTIVE";
    }
}

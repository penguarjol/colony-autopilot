package com.colonyautopilot.bridge.colony;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Mirrors MineColonies' own Colony.updateState so the snapshot can report which
 * branch the live colony is on. Reproducing the rule here is duplication, but
 * the alternative is guessing at the single value that decides whether work
 * orders are assigned at all.
 */
class ColonyActivationStateTest {
    @Test
    void aRealNearbyPlayerActivatesRegardlessOfChunks() {
        assertEquals("ACTIVE", ColonyActivationState.describe(1, 0, 0));
    }

    // The branch this whole investigation turns on: with only an important
    // player, MineColonies additionally requires more than 40 claimed loaded
    // chunks before it will assign work orders.
    @Test
    void anImportantPlayerNeedsMoreThanFortyLoadedChunks() {
        assertEquals("ACTIVE", ColonyActivationState.describe(0, 1, 41));
        assertEquals("UNLOADED", ColonyActivationState.describe(0, 1, 40));
        assertEquals("UNLOADED", ColonyActivationState.describe(0, 1, 32));
    }

    @Test
    void noSubscribersAtAllMeansInactive() {
        assertEquals("INACTIVE", ColonyActivationState.describe(0, 0, 95));
    }
}

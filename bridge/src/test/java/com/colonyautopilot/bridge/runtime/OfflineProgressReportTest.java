package com.colonyautopilot.bridge.runtime;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class OfflineProgressReportTest {
    @Test
    void reportsInactiveWhenNoColonyIsLeased() {
        assertEquals(
            "no offline eligible colonies",
            OfflineProgressReport.describe(0, 0)
        );
    }

    @Test
    void reportsProgressionWhenChunksAreLoaded() {
        assertEquals(
            "offline colony progression active",
            OfflineProgressReport.describe(1, 32)
        );
    }

    // A lease with no force-loaded chunks ticks nothing, so reporting it as
    // active hid a frozen colony behind a healthy-looking status for days.
    @Test
    void distinguishesALeaseThatLoadedNoChunks() {
        assertEquals(
            "leased but no chunks are force-loaded",
            OfflineProgressReport.describe(1, 0)
        );
    }
}

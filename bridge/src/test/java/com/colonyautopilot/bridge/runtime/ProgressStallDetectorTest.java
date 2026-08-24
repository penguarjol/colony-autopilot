package com.colonyautopilot.bridge.runtime;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProgressStallDetectorTest {
    @Test
    void recoversOnlyAfterSustainedLackOfProgress() {
        ProgressStallDetector detector = new ProgressStallDetector(3);

        assertFalse(detector.update("builder-1", "load:0:7", true));
        assertFalse(detector.update("builder-1", "load:0:7", true));
        assertTrue(detector.update("builder-1", "load:0:7", true));
        assertFalse(detector.update("builder-1", "load:0:7", true));
        assertFalse(detector.update("builder-1", "load:0:7", true));
        assertTrue(detector.update("builder-1", "load:0:7", true));
    }

    @Test
    void progressAndIneligibilityResetTheStallWindow() {
        ProgressStallDetector detector = new ProgressStallDetector(2);

        assertFalse(detector.update("builder-1", "load:0:7", true));
        assertFalse(detector.update("builder-1", "load:1:7", true));
        assertTrue(detector.update("builder-1", "load:1:7", true));
        assertFalse(detector.update("builder-1", "load:1:7", false));
        assertFalse(detector.update("builder-1", "load:1:7", true));
    }
}

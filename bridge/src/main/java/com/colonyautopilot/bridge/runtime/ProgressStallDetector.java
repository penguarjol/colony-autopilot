package com.colonyautopilot.bridge.runtime;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

final class ProgressStallDetector {
    private final int staleChecks;
    private final Map<String, Observation> observations = new HashMap<>();

    ProgressStallDetector(int staleChecks) {
        if (staleChecks < 1) {
            throw new IllegalArgumentException("staleChecks must be positive");
        }
        this.staleChecks = staleChecks;
    }

    boolean update(String key, String fingerprint, boolean eligible) {
        if (!eligible) {
            observations.remove(key);
            return false;
        }

        Observation previous = observations.get(key);
        int unchangedChecks = previous != null
            && Objects.equals(previous.fingerprint(), fingerprint)
                ? previous.unchangedChecks() + 1
                : 1;
        if (unchangedChecks >= staleChecks) {
            observations.put(key, new Observation(fingerprint, 0));
            return true;
        }

        observations.put(key, new Observation(fingerprint, unchangedChecks));
        return false;
    }

    private record Observation(String fingerprint, int unchangedChecks) {
    }
}

package com.colonyautopilot.bridge.runtime;

import java.util.Map;
import java.util.WeakHashMap;

public final class ConstructionBatchRegistry {
    private static final Map<Object, Integer> STEPS = new WeakHashMap<>();

    public static synchronized int stepsFor(Object workOrder) {
        return workOrder == null ? 1 : STEPS.getOrDefault(workOrder, 1);
    }

    static synchronized void setSteps(Object workOrder, int steps) {
        if (workOrder != null) {
            STEPS.put(workOrder, Math.max(1, steps));
        }
    }

    static synchronized void clear(Object workOrder) {
        if (workOrder != null) {
            STEPS.remove(workOrder);
        }
    }

    static synchronized void clearAll() {
        STEPS.clear();
    }

    private ConstructionBatchRegistry() {
    }
}

package com.colonyautopilot.bridge.action;

import java.util.Set;
import java.util.UUID;

public final class ActionPolicy {
    private static final Set<String> INITIAL_BUILDING_TYPES = Set.of(
        "minecolonies:townhall",
        "minecolonies:builder"
    );

    private ActionPolicy() {
    }

    public static boolean isColonyOwner(UUID actor, UUID owner) {
        return actor.equals(owner);
    }

    public static boolean isInitialBuild(
        String buildingType,
        int level,
        boolean built,
        boolean pendingConstruction
    ) {
        return level == 0
            && !built
            && !pendingConstruction
            && INITIAL_BUILDING_TYPES.contains(buildingType);
    }

    public static boolean isBuilderIdle(
        int level,
        boolean built,
        int assignedCitizenCount,
        boolean hasWorkOrder
    ) {
        boolean liveBuilder = level == 0 && !built || level > 0 && built;
        return liveBuilder && assignedCitizenCount == 1 && !hasWorkOrder;
    }
}

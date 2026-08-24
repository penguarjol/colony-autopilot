package com.colonyautopilot.bridge.action;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ActionPolicyTest {
    @Test
    void authorizesOnlyExactColonyOwner() {
        UUID owner = UUID.randomUUID();

        assertTrue(ActionPolicy.isColonyOwner(owner, owner));
        assertFalse(ActionPolicy.isColonyOwner(UUID.randomUUID(), owner));
    }

    @Test
    void permitsOnlyBootstrapBuildingTypesAtLevelZero() {
        assertTrue(ActionPolicy.isInitialBuild("minecolonies:townhall", 0, false, false));
        assertTrue(ActionPolicy.isInitialBuild("minecolonies:builder", 0, false, false));
        assertFalse(ActionPolicy.isInitialBuild("minecolonies:warehouse", 0, false, false));
        assertFalse(ActionPolicy.isInitialBuild("minecolonies:townhall", 0, false, true));
        assertFalse(ActionPolicy.isInitialBuild("minecolonies:townhall", 1, true, false));
    }

    @Test
    void treatsStaffedLevelZeroBuilderHutAsIdle() {
        assertTrue(ActionPolicy.isBuilderIdle(0, false, 1, false));
        assertTrue(ActionPolicy.isBuilderIdle(1, true, 1, false));
        assertFalse(ActionPolicy.isBuilderIdle(0, false, 0, false));
        assertFalse(ActionPolicy.isBuilderIdle(0, false, 1, true));
    }
}

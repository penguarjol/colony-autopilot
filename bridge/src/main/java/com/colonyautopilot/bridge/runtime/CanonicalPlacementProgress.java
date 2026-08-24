package com.colonyautopilot.bridge.runtime;

import net.minecraft.core.BlockPos;

record CanonicalPlacementProgress(
    CanonicalPlacementPhase phase,
    BlockPos iteratorPosition,
    long completedSteps,
    boolean verified
) {
    static CanonicalPlacementProgress start() {
        return new CanonicalPlacementProgress(
            CanonicalPlacementPhase.SOLID,
            null,
            0,
            false
        );
    }

    static CanonicalPlacementProgress startRemoval() {
        return new CanonicalPlacementProgress(
            CanonicalPlacementPhase.REMOVE_WATER,
            null,
            0,
            false
        );
    }

    CanonicalPlacementProgress nextPhase() {
        CanonicalPlacementPhase next = switch (phase) {
            case SOLID -> CanonicalPlacementPhase.WEAK_SOLID;
            case WEAK_SOLID -> CanonicalPlacementPhase.CLEAR_WATER;
            case CLEAR_WATER -> CanonicalPlacementPhase.NON_SOLID;
            case NON_SOLID -> CanonicalPlacementPhase.ENTITIES;
            case ENTITIES, REMOVE -> CanonicalPlacementPhase.VERIFYING;
            case REMOVE_WATER -> CanonicalPlacementPhase.REMOVE;
            case VERIFYING -> CanonicalPlacementPhase.VERIFYING;
        };
        return new CanonicalPlacementProgress(
            next,
            null,
            completedSteps,
            false
        );
    }

    CanonicalPlacementProgress withIteratorPosition(BlockPos position, long steps) {
        return new CanonicalPlacementProgress(
            phase,
            position,
            Math.max(completedSteps, steps),
            false
        );
    }

    CanonicalPlacementProgress verificationPassed() {
        if (phase != CanonicalPlacementPhase.VERIFYING) {
            throw new IllegalStateException("placement has not reached verification");
        }
        return new CanonicalPlacementProgress(phase, iteratorPosition, completedSteps, true);
    }

    boolean canComplete() {
        return phase == CanonicalPlacementPhase.VERIFYING && verified;
    }
}

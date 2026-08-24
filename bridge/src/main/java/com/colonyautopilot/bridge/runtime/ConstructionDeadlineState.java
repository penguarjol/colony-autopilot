package com.colonyautopilot.bridge.runtime;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.core.BlockPos;

final class ConstructionDeadlineState {
    private static final String DIMENSION = "dimension";
    private static final String COLONY_ID = "colonyId";
    private static final String WORK_ORDER_ID = "workOrderId";
    private static final String HEALTHY_TICKS = "healthyTicks";
    private static final String INITIAL_ACTIONS = "initialActions";
    private static final String LAST_ACTIONS = "lastActions";
    private static final String ESTIMATED_OPERATIONS = "estimatedOperations";
    private static final String RECONCILIATION_CURSOR = "reconciliationCursor";
    private static final String PLACEMENT_PHASE = "placementPhase";
    private static final String PLACEMENT_POSITION = "placementPosition";
    private static final String PLACEMENT_VERIFIED = "placementVerified";
    private static final String PAUSED = "paused";

    private final Key key;
    private final int initialActionsDone;
    private long estimatedOperations;
    private long healthyTicks;
    private int lastActionsDone;
    private long reconciliationCursor;
    private CanonicalPlacementProgress placementProgress;
    private boolean paused;

    ConstructionDeadlineState(
        Key key,
        int initialActionsDone,
        long estimatedOperations
    ) {
        this(
            key,
            initialActionsDone,
            initialActionsDone,
            estimatedOperations,
            0,
            0,
            CanonicalPlacementProgress.start(),
            false
        );
    }

    private ConstructionDeadlineState(
        Key key,
        int initialActionsDone,
        int lastActionsDone,
        long estimatedOperations,
        long healthyTicks,
        long reconciliationCursor,
        CanonicalPlacementProgress placementProgress,
        boolean paused
    ) {
        this.key = key;
        this.initialActionsDone = initialActionsDone;
        this.lastActionsDone = lastActionsDone;
        this.estimatedOperations = Math.max(1, estimatedOperations);
        this.healthyTicks = Math.max(0, healthyTicks);
        this.reconciliationCursor = Math.max(0L, reconciliationCursor);
        this.placementProgress = placementProgress;
        this.paused = paused;
    }

    void observe(
        int actionsDone,
        long currentEstimate,
        boolean eligibleAndHealthy,
        int elapsedTicks
    ) {
        lastActionsDone = Math.max(lastActionsDone, actionsDone);
        estimatedOperations = Math.max(estimatedOperations, currentEstimate);
        if (eligibleAndHealthy) {
            healthyTicks += Math.max(0, elapsedTicks);
        }
    }

    long estimatedRemainingOperations() {
        long completed = Math.max(0, lastActionsDone - initialActionsDone);
        return Math.max(0, estimatedOperations - completed);
    }

    CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.putString(DIMENSION, key.dimension());
        tag.putInt(COLONY_ID, key.colonyId());
        tag.putInt(WORK_ORDER_ID, key.workOrderId());
        tag.putLong(HEALTHY_TICKS, healthyTicks);
        tag.putInt(INITIAL_ACTIONS, initialActionsDone);
        tag.putInt(LAST_ACTIONS, lastActionsDone);
        tag.putLong(ESTIMATED_OPERATIONS, estimatedOperations);
        tag.putLong(RECONCILIATION_CURSOR, reconciliationCursor);
        tag.putString(PLACEMENT_PHASE, placementProgress.phase().name());
        if (placementProgress.iteratorPosition() != null) {
            tag.putLong(
                PLACEMENT_POSITION,
                placementProgress.iteratorPosition().asLong()
            );
        }
        tag.putBoolean(PLACEMENT_VERIFIED, placementProgress.verified());
        tag.putBoolean(PAUSED, paused);
        return tag;
    }

    static ConstructionDeadlineState load(CompoundTag tag) {
        CanonicalPlacementPhase phase = tag.contains(PLACEMENT_PHASE)
            ? placementPhase(tag.getString(PLACEMENT_PHASE))
            : CanonicalPlacementPhase.SOLID;
        BlockPos position = tag.contains(PLACEMENT_POSITION)
            ? BlockPos.of(tag.getLong(PLACEMENT_POSITION))
            : null;
        long cursor = tag.getLong(RECONCILIATION_CURSOR);
        return new ConstructionDeadlineState(
            new Key(
                tag.getString(DIMENSION),
                tag.getInt(COLONY_ID),
                tag.getInt(WORK_ORDER_ID)
            ),
            tag.getInt(INITIAL_ACTIONS),
            tag.getInt(LAST_ACTIONS),
            tag.getLong(ESTIMATED_OPERATIONS),
            tag.getLong(HEALTHY_TICKS),
            cursor,
            new CanonicalPlacementProgress(
                phase,
                position,
                cursor,
                tag.getBoolean(PLACEMENT_VERIFIED)
            ),
            tag.getBoolean(PAUSED)
        );
    }

    Key key() {
        return key;
    }

    long healthyTicks() {
        return healthyTicks;
    }

    int lastActionsDone() {
        return lastActionsDone;
    }

    long reconciliationCursor() {
        return reconciliationCursor;
    }

    void setReconciliationCursor(long reconciliationCursor) {
        this.reconciliationCursor = Math.max(0L, reconciliationCursor);
        placementProgress = placementProgress.withIteratorPosition(
            placementProgress.iteratorPosition(),
            this.reconciliationCursor
        );
    }

    CanonicalPlacementProgress placementProgress() {
        return placementProgress;
    }

    void setPlacementProgress(CanonicalPlacementProgress placementProgress) {
        this.placementProgress = placementProgress;
        reconciliationCursor = Math.max(
            reconciliationCursor,
            placementProgress.completedSteps()
        );
    }

    boolean paused() {
        return paused;
    }

    void setPaused(boolean paused) {
        this.paused = paused;
    }

    record Key(String dimension, int colonyId, int workOrderId) {
    }

    private static CanonicalPlacementPhase placementPhase(String value) {
        try {
            return CanonicalPlacementPhase.valueOf(value);
        } catch (IllegalArgumentException exception) {
            return CanonicalPlacementPhase.SOLID;
        }
    }
}

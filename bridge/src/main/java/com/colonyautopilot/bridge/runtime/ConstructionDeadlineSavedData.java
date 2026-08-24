package com.colonyautopilot.bridge.runtime;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.HashMap;
import java.util.Map;

final class ConstructionDeadlineSavedData extends SavedData {
    static final String DATA_NAME = "colonyautobridge_construction_deadlines";

    private static final String JOBS = "jobs";

    private final Map<
        ConstructionDeadlineState.Key,
        ConstructionDeadlineState
    > states = new HashMap<>();

    ConstructionDeadlineState getOrCreate(
        ConstructionDeadlineState.Key key,
        int actionsDone,
        long estimatedOperations
    ) {
        ConstructionDeadlineState existing = states.get(key);
        if (existing != null) {
            return existing;
        }
        ConstructionDeadlineState created = new ConstructionDeadlineState(
            key,
            actionsDone,
            estimatedOperations
        );
        states.put(key, created);
        setDirty();
        return created;
    }

    ConstructionDeadlineState get(ConstructionDeadlineState.Key key) {
        return states.get(key);
    }

    boolean remove(ConstructionDeadlineState.Key key) {
        if (states.remove(key) != null) {
            setDirty();
            return true;
        }
        return false;
    }

    void changed() {
        setDirty();
    }

    @Override
    public CompoundTag save(CompoundTag root) {
        ListTag jobs = new ListTag();
        states.values().stream()
            .sorted((left, right) -> left.key().toString().compareTo(right.key().toString()))
            .map(ConstructionDeadlineState::save)
            .forEach(jobs::add);
        root.put(JOBS, jobs);
        return root;
    }

    static ConstructionDeadlineSavedData load(CompoundTag root) {
        ConstructionDeadlineSavedData data = new ConstructionDeadlineSavedData();
        ListTag jobs = root.getList(JOBS, Tag.TAG_COMPOUND);
        for (int index = 0; index < jobs.size(); index++) {
            ConstructionDeadlineState state =
                ConstructionDeadlineState.load(jobs.getCompound(index));
            data.states.put(state.key(), state);
        }
        return data;
    }

}

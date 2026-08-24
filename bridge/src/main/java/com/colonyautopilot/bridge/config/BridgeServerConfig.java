package com.colonyautopilot.bridge.config;

import net.minecraftforge.common.ForgeConfigSpec;

public final class BridgeServerConfig {
    public enum Mode {
        OBSERVER,
        ACTIVE
    }

    public static final ForgeConfigSpec SPEC;
    public static final ForgeConfigSpec.BooleanValue ENABLED;
    public static final ForgeConfigSpec.EnumValue<Mode> MODE;
    public static final ForgeConfigSpec.IntValue DAILY_ACTION_BUDGET;
    public static final ForgeConfigSpec.IntValue REQUIRED_PERMISSION_LEVEL;
    public static final ForgeConfigSpec.IntValue IPC_POLL_INTERVAL_TICKS;
    public static final ForgeConfigSpec.IntValue MAX_ACTIONS_PER_TICK;
    public static final ForgeConfigSpec.LongValue REQUEST_MAX_AGE_MILLIS;
    public static final ForgeConfigSpec.BooleanValue OFFLINE_PROGRESS_ENABLED;
    public static final ForgeConfigSpec.DoubleValue OFFLINE_MIN_TPS;
    public static final ForgeConfigSpec.DoubleValue OFFLINE_MAX_TICK_MILLIS;
    public static final ForgeConfigSpec.BooleanValue OFFLINE_STALL_RECOVERY_ENABLED;
    public static final ForgeConfigSpec.IntValue OFFLINE_STALL_MINUTES;
    public static final ForgeConfigSpec.IntValue BUILDER_SPEED_MULTIPLIER;
    public static final ForgeConfigSpec.IntValue OFFLINE_CHUNK_RADIUS;
    public static final ForgeConfigSpec.IntValue OFFLINE_MAX_TICKING_CHUNKS;
    public static final ForgeConfigSpec.BooleanValue CONSTRUCTION_DEADLINE_ENABLED;
    public static final ForgeConfigSpec.IntValue CONSTRUCTION_DEADLINE_MINUTES;
    public static final ForgeConfigSpec.IntValue CONSTRUCTION_RECONCILIATION_LEAD_MINUTES;
    public static final ForgeConfigSpec.IntValue CONSTRUCTION_ACCELERATED_STEPS;
    public static final ForgeConfigSpec.IntValue CONSTRUCTION_RECONCILIATION_STEPS;

    static {
        ForgeConfigSpec.Builder builder = new ForgeConfigSpec.Builder();
        builder.comment("Colony Autopilot Bridge server settings.").push("bridge");
        ENABLED = builder
            .comment("Master switch. False keeps the bridge observer-only.")
            .define("enabled", false);
        MODE = builder
            .comment("OBSERVER permits snapshots and status only. ACTIVE permits validated actions.")
            .defineEnum("mode", Mode.OBSERVER);
        DAILY_ACTION_BUDGET = builder
            .comment("Maximum mutating actions per Minecraft day. Zero disables mutations.")
            .defineInRange("dailyActionBudget", 0, 0, 10_000);
        REQUIRED_PERMISSION_LEVEL = builder
            .comment("Permission level required for commands and the online actor named in IPC actions.")
            .defineInRange("requiredPermissionLevel", 3, 0, 4);
        IPC_POLL_INTERVAL_TICKS = builder
            .comment("Tick interval between file-spool polls.")
            .defineInRange("ipcPollIntervalTicks", 20, 1, 1_200);
        MAX_ACTIONS_PER_TICK = builder
            .comment("Maximum IPC requests handled in one server tick.")
            .defineInRange("maxActionsPerTick", 1, 1, 100);
        REQUEST_MAX_AGE_MILLIS = builder
            .comment("Maximum absolute clock skew or age accepted for IPC requests.")
            .defineInRange("requestMaxAgeMillis", 30_000L, 1_000L, 300_000L);
        OFFLINE_PROGRESS_ENABLED = builder
            .comment("Keep MineColonies active without online players while health gates pass.")
            .define("offlineProgressEnabled", false);
        OFFLINE_MIN_TPS = builder
            .comment("Release offline colony activity below this TPS.")
            .defineInRange("offlineMinTps", 19.5D, 1.0D, 20.0D);
        OFFLINE_MAX_TICK_MILLIS = builder
            .comment("Release offline colony activity above this average tick time.")
            .defineInRange("offlineMaxTickMillis", 35.0D, 1.0D, 1_000.0D);
        OFFLINE_STALL_RECOVERY_ENABLED = builder
            .comment("Retry a builder blueprint load after sustained offline inactivity.")
            .define("offlineStallRecoveryEnabled", false);
        OFFLINE_STALL_MINUTES = builder
            .comment("Unchanged LOAD_STRUCTURE minutes before retrying the blueprint load.")
            .defineInRange("offlineStallMinutes", 5, 1, 60);
        BUILDER_SPEED_MULTIPLIER = builder
            .comment("Builder AI speed multiplier while Autopilot and health gates are active.")
            .defineInRange("builderSpeedMultiplier", 1, 1, 10);
        OFFLINE_CHUNK_RADIUS = builder
            .comment("Entity-ticking chunk radius around offline citizens and worksites.")
            .defineInRange("offlineChunkRadius", 1, 0, 2);
        OFFLINE_MAX_TICKING_CHUNKS = builder
            .comment("Maximum Autopilot-owned entity-ticking chunks per offline colony.")
            .defineInRange("offlineMaxTickingChunks", 32, 1, 64);
        CONSTRUCTION_DEADLINE_ENABLED = builder
            .comment("Enforce a healthy-runtime deadline for claimed builder work orders.")
            .define("constructionDeadlineEnabled", true);
        CONSTRUCTION_DEADLINE_MINUTES = builder
            .comment("Healthy runtime allowed for clearing and completing one build.")
            .defineInRange("constructionDeadlineMinutes", 60, 5, 360);
        CONSTRUCTION_RECONCILIATION_LEAD_MINUTES = builder
            .comment("Minutes before the deadline when visible adaptive batching begins.")
            .defineInRange("constructionReconciliationLeadMinutes", 15, 1, 60);
        CONSTRUCTION_ACCELERATED_STEPS = builder
            .comment("Maximum visible MineColonies block operations per builder step.")
            .defineInRange("constructionAcceleratedSteps", 4, 1, 16);
        CONSTRUCTION_RECONCILIATION_STEPS = builder
            .comment("Maximum operations per builder step during deadline reconciliation.")
            .defineInRange("constructionReconciliationSteps", 64, 4, 512);
        builder.pop();
        SPEC = builder.build();
    }

    private BridgeServerConfig() {
    }
}

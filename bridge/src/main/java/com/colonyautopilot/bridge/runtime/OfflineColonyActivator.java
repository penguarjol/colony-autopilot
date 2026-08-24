package com.colonyautopilot.bridge.runtime;

import com.colonyautopilot.bridge.ColonyAutopilotBridge;
import com.colonyautopilot.bridge.config.BridgeServerConfig;
import com.minecolonies.api.colony.IColony;
import com.minecolonies.api.colony.IColonyManager;
import com.mojang.authlib.GameProfile;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraftforge.common.util.FakePlayer;
import net.minecraftforge.common.util.FakePlayerFactory;
import net.minecraftforge.common.world.ForgeChunkManager;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public final class OfflineColonyActivator implements AutoCloseable {
    private static final int CHECK_INTERVAL_TICKS = 100;
    private static final int RECOVERY_CHECKS = 12;

    private final MinecraftServer server;
    private final BridgeState bridgeState;
    private final ConstructionDeadlineController constructionDeadlines;
    private final TickGate tickGate = new TickGate();
    private final OfflineProgressGate healthGate =
        new OfflineProgressGate(RECOVERY_CHECKS);
    private final BuilderStallRecovery stallRecovery;
    private final Map<ColonyKey, Lease> leases = new HashMap<>();

    private String reason = "waiting for health checks";

    public OfflineColonyActivator(
        MinecraftServer server,
        BridgeState bridgeState,
        ConstructionDeadlineController constructionDeadlines
    ) {
        this.server = server;
        this.bridgeState = bridgeState;
        this.constructionDeadlines = constructionDeadlines;
        stallRecovery = new BuilderStallRecovery();
    }

    public void tick() {
        if (!tickGate.tryAcquire(server.getTickCount(), CHECK_INTERVAL_TICKS)) {
            return;
        }

        boolean requested = BridgeServerConfig.ENABLED.get()
            && BridgeServerConfig.MODE.get() == BridgeServerConfig.Mode.ACTIVE
            && BridgeServerConfig.OFFLINE_PROGRESS_ENABLED.get()
            && !bridgeState.isPaused(server.getTickCount());
        float tickMillis = server.getAverageTickTime();
        double tps = tickMillis <= 0.0F
            ? 20.0
            : Math.min(20.0, 1_000.0 / tickMillis);
        boolean healthy = tps >= BridgeServerConfig.OFFLINE_MIN_TPS.get()
            && tickMillis <= BridgeServerConfig.OFFLINE_MAX_TICK_MILLIS.get();

        if (!healthGate.update(requested, healthy)) {
            releaseAll();
            reason = !requested
                ? "disabled, observer-only, or paused"
                : "waiting for sustained healthy TPS and tick time";
            return;
        }

        reconcileLeases();
        reason = leases.isEmpty()
            ? "no offline eligible colonies"
            : "offline colony progression active";
    }

    public Status status() {
        return new Status(
            BridgeServerConfig.OFFLINE_PROGRESS_ENABLED.get(),
            !leases.isEmpty(),
            leases.size(),
            reason,
            BridgeServerConfig.OFFLINE_STALL_RECOVERY_ENABLED.get(),
            stallRecovery.recoveryCount(),
            stallRecovery.lastRecovery(),
            leases.values().stream().mapToInt(lease -> lease.chunks().size()).sum()
        );
    }

    @Override
    public void close() {
        releaseAll();
    }

    private void reconcileLeases() {
        Map<ColonyKey, IColony> eligible = new HashMap<>();
        for (IColony colony : IColonyManager.getInstance().getAllColonies()) {
            if (!(colony.getWorld() instanceof ServerLevel level)
                || colony.isColonyUnderAttack()) {
                continue;
            }
            UUID owner = colony.getPermissions().getOwner();
            if (owner == null) {
                continue;
            }
            ColonyKey key = new ColonyKey(level.dimension(), colony.getID());
            if (server.getPlayerList().getPlayer(owner) != null) {
                release(key);
                continue;
            }
            eligible.put(key, colony);
            Lease lease = leases.computeIfAbsent(
                key,
                ignored -> acquire(colony, level, owner)
            );
            colony.getPackageManager()
                .getImportantColonyPlayers()
                .add(lease.player());
            updateTickingChunks(colony, lease);
            colony.getCitizenManager().getCitizens().forEach(citizen -> {
                BlockPos lastPosition = citizen.getLastPosition();
                if (OfflineCitizenSpawnPolicy.shouldSpawn(
                    true,
                    colony.isColonyUnderAttack(),
                    citizen.getEntity().isPresent(),
                    lastPosition != null
                ) && level.hasChunkAt(lastPosition)) {
                    colony.getCitizenManager().spawnOrCreateCitizen(
                        citizen,
                        level,
                        lastPosition
                    );
                }
            });
            if (BridgeServerConfig.OFFLINE_STALL_RECOVERY_ENABLED.get()) {
                stallRecovery.tick(
                    colony,
                    level,
                    workOrderId -> constructionDeadlines.isControlling(
                        colony.getID(),
                        workOrderId
                    )
                );
            }
        }

        for (ColonyKey key : leases.keySet().toArray(ColonyKey[]::new)) {
            if (!eligible.containsKey(key)) {
                release(key);
            }
        }
    }

    private Lease acquire(IColony colony, ServerLevel level, UUID owner) {
        FakePlayer player = FakePlayerFactory.get(
            level,
            new GameProfile(owner, "ColonyAutoLease")
        );
        colony.getPackageManager().getImportantColonyPlayers().add(player);
        return new Lease(colony, level, owner, player, new HashSet<>());
    }

    private void release(ColonyKey key) {
        Lease lease = leases.remove(key);
        if (lease != null) {
            for (OfflineChunkPlan.ChunkCoordinate chunk : lease.chunks()) {
                ForgeChunkManager.forceChunk(
                    lease.level(),
                    ColonyAutopilotBridge.MOD_ID,
                    lease.owner(),
                    chunk.x(),
                    chunk.z(),
                    false,
                    true
                );
            }
            lease.chunks().clear();
            lease.colony()
                .getPackageManager()
                .getImportantColonyPlayers()
                .remove(lease.player());
        }
    }

    private void releaseAll() {
        for (ColonyKey key : leases.keySet().toArray(ColonyKey[]::new)) {
            release(key);
        }
    }

    private record ColonyKey(ResourceKey<Level> dimension, int colonyId) {
    }

    private record Lease(
        IColony colony,
        ServerLevel level,
        UUID owner,
        FakePlayer player,
        Set<OfflineChunkPlan.ChunkCoordinate> chunks
    ) {
    }

    public record Status(
        boolean enabled,
        boolean active,
        int leases,
        String reason,
        boolean stallRecoveryEnabled,
        int stallRecoveries,
        String lastStallRecovery,
        int tickingChunks
    ) {
    }

    private void updateTickingChunks(IColony colony, Lease lease) {
        List<OfflineChunkPlan.BlockCoordinate> anchors = new ArrayList<>();
        anchors.add(coordinate(colony.getCenter()));
        colony.getCitizenManager().getCitizens().forEach(citizen -> {
            if (citizen.getLastPosition() != null) {
                anchors.add(coordinate(citizen.getLastPosition()));
            }
            if (citizen.getWorkBuilding() != null) {
                anchors.add(coordinate(citizen.getWorkBuilding().getPosition()));
            }
        });
        Set<OfflineChunkPlan.ChunkCoordinate> desired = Set.copyOf(
            OfflineChunkPlan.around(
                anchors,
                BridgeServerConfig.OFFLINE_CHUNK_RADIUS.get(),
                BridgeServerConfig.OFFLINE_MAX_TICKING_CHUNKS.get()
            )
        );
        if (desired.isEmpty()) {
            return;
        }

        for (OfflineChunkPlan.ChunkCoordinate chunk : desired) {
            if (!lease.chunks().contains(chunk)
                && ForgeChunkManager.forceChunk(
                    lease.level(),
                    ColonyAutopilotBridge.MOD_ID,
                    lease.owner(),
                    chunk.x(),
                    chunk.z(),
                    true,
                    true
                )) {
                lease.chunks().add(chunk);
            }
        }
        for (OfflineChunkPlan.ChunkCoordinate chunk :
            lease.chunks().toArray(OfflineChunkPlan.ChunkCoordinate[]::new)) {
            if (!desired.contains(chunk)) {
                ForgeChunkManager.forceChunk(
                    lease.level(),
                    ColonyAutopilotBridge.MOD_ID,
                    lease.owner(),
                    chunk.x(),
                    chunk.z(),
                    false,
                    true
                );
                lease.chunks().remove(chunk);
            }
        }
    }

    private static OfflineChunkPlan.BlockCoordinate coordinate(BlockPos position) {
        return new OfflineChunkPlan.BlockCoordinate(position.getX(), position.getZ());
    }
}

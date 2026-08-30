package com.colonyautopilot.bridge.action;

import com.mojang.authlib.GameProfile;
import com.colonyautopilot.bridge.colony.ColonySnapshotService;
import com.colonyautopilot.bridge.config.BridgeServerConfig;
import com.colonyautopilot.bridge.ipc.BridgeJsonCodec;
import com.colonyautopilot.bridge.ipc.BridgeProtocol.AssignBuilderAction;
import com.colonyautopilot.bridge.ipc.BridgeProtocol.BlockPosition;
import com.colonyautopilot.bridge.ipc.BridgeProtocol.GrantAction;
import com.colonyautopilot.bridge.ipc.BridgeProtocol.PauseAction;
import com.colonyautopilot.bridge.ipc.BridgeProtocol.Request;
import com.colonyautopilot.bridge.ipc.BridgeProtocol.Response;
import com.colonyautopilot.bridge.ipc.BridgeProtocol.SnapshotAction;
import com.colonyautopilot.bridge.ipc.BridgeProtocol.UpgradeAction;
import com.colonyautopilot.bridge.runtime.BridgeMetrics;
import com.colonyautopilot.bridge.runtime.BridgeState;
import com.colonyautopilot.bridge.runtime.ConstructionDeadlineController;
import com.colonyautopilot.bridge.runtime.OfflineColonyActivator;
import com.minecolonies.api.colony.IColony;
import com.minecolonies.api.colony.IColonyManager;
import com.minecolonies.api.colony.buildings.IBuilding;
import com.minecolonies.api.colony.requestsystem.request.IRequest;
import com.minecolonies.api.colony.requestsystem.requestable.IDeliverable;
import com.minecolonies.api.colony.requestsystem.token.StandardToken;
import com.minecolonies.api.colony.workorders.IBuilderWorkOrder;
import com.minecolonies.core.colony.buildings.workerbuildings.BuildingBuilder;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraftforge.common.util.FakePlayerFactory;

import java.time.Clock;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

public final class BridgeActionService implements BridgeActionHandler {
    private static final int REPLAY_CACHE_SIZE = 4_096;

    private final MinecraftServer server;
    private final BridgeState state;
    private final BridgeMetrics metrics;
    private final BridgeJsonCodec codec;
    private final ColonySnapshotService snapshots;
    private final ActionValidator validator;
    private final OfflineColonyActivator offlineProgress;
    private final ConstructionDeadlineController constructionDeadlines;
    private final Map<String, CachedResponse> responses = new LinkedHashMap<>();
    private final Map<UUID, Long> nonces = new LinkedHashMap<>();

    public BridgeActionService(
        MinecraftServer server,
        BridgeState state,
        BridgeMetrics metrics,
        BridgeJsonCodec codec,
        OfflineColonyActivator offlineProgress,
        ConstructionDeadlineController constructionDeadlines
    ) {
        this.server = server;
        this.state = state;
        this.metrics = metrics;
        this.codec = codec;
        this.offlineProgress = offlineProgress;
        this.constructionDeadlines = constructionDeadlines;
        snapshots = new ColonySnapshotService(constructionDeadlines::status);
        validator = new ActionValidator(Clock.systemUTC());
    }

    @Override
    public Response handle(Request request) {
        requireServerThread();
        CachedResponse cached = responses.get(request.requestId());
        if (cached != null) {
            if (cached.nonce().equals(request.nonce())) {
                return cached.response();
            }
            return Response.rejected(request, "REQUEST_ID_CONFLICT", "requestId was already used with another nonce");
        }

        Optional<ActionValidator.Rejection> invalid =
            validator.validateEnvelope(request, BridgeServerConfig.REQUEST_MAX_AGE_MILLIS.get());
        if (invalid.isPresent()) {
            return remember(request, Response.rejected(request, invalid.get().code(), invalid.get().message()));
        }
        purgeExpiredNonces();
        if (nonces.containsKey(request.nonce())) {
            return remember(request, Response.rejected(request, "NONCE_REPLAY", "nonce was already used"));
        }
        nonces.put(request.nonce(), System.currentTimeMillis());
        trim(nonces);

        Response response = switch (request.action().kind()) {
            case STATUS -> status(request);
            case SNAPSHOT -> snapshot(request);
            case ASSIGN_BUILDER -> assignBuilder(request);
            case REQUEST_UPGRADE -> upgrade(request);
            case GRANT -> grant(request);
            case PAUSE -> pause(request);
            case PLACE_BUILDING -> Response.rejected(
                request,
                "ACTION_DISABLED",
                "place_building is intentionally disabled; the bridge never places new structures"
            );
        };
        return remember(request, response);
    }

    private Response status(Request request) {
        ServerLevel overworld = server.overworld();
        long gameTime = overworld.getGameTime();
        float tickMillis = server.getAverageTickTime();
        OfflineColonyActivator.Status offline = offlineProgress.status();
        StatusPayload payload = new StatusPayload(
            BridgeServerConfig.ENABLED.get(),
            BridgeServerConfig.MODE.get().name(),
            state.isPaused(server.getTickCount()),
            state.pausedUntilTick(),
            BridgeServerConfig.DAILY_ACTION_BUDGET.get(),
            state.actionsThisDay(gameTime),
            StatusMetrics.fromAverageTickMillis(tickMillis, metrics.snapshot()),
            BridgeServerConfig.BUILDER_SPEED_MULTIPLIER.get(),
            offline.enabled(),
            offline.active(),
            offline.leases(),
            offline.reason(),
            offline.stallRecoveryEnabled(),
            offline.stallRecoveries(),
            offline.lastStallRecovery(),
            offline.tickingChunks(),
            BridgeServerConfig.CONSTRUCTION_DEADLINE_ENABLED.get(),
            BridgeServerConfig.CONSTRUCTION_DEADLINE_MINUTES.get(),
            constructionDeadlines.status()
        );
        return Response.completed(request, codec.payload(payload));
    }

    private Response snapshot(Request request) {
        SnapshotAction action = (SnapshotAction) request.action();
        return Response.completed(
            request,
            codec.payload(snapshots.read(server, action.colonyId(), action.dimension()))
        );
    }

    private Response upgrade(Request request) {
        Response gate = mutationGate(request);
        if (gate != null) {
            return gate;
        }

        UpgradeAction action = (UpgradeAction) request.action();
        ServerLevel level = resolveLevel(action.dimension());
        if (level == null) {
            return Response.rejected(request, "UNKNOWN_DIMENSION", "target dimension is not loaded");
        }
        IColony colony = IColonyManager.getInstance().getColonyByWorld(action.colonyId(), level);
        if (colony == null) {
            return Response.rejected(request, "UNKNOWN_COLONY", "target colony was not found in the loaded dimension");
        }

        BlockPos buildingPosition = toBlockPos(action.buildingPos());
        BlockPos builderPosition = toBlockPos(action.builderPos());
        if (!level.hasChunkAt(buildingPosition) || !level.hasChunkAt(builderPosition)) {
            return Response.rejected(request, "CHUNK_NOT_LOADED", "bridge actions never force-load target chunks");
        }
        IBuilding building = colony.getServerBuildingManager().getBuilding(buildingPosition);
        IBuilding rawBuilder = colony.getServerBuildingManager().getBuilding(builderPosition);
        if (building == null || !(rawBuilder instanceof BuildingBuilder builder)) {
            return Response.rejected(
                request,
                "UNKNOWN_BUILDING",
                "building must exist and builder_pos must identify a structure builder in the target colony"
            );
        }
        if (!isAvailableBuilder(builder)) {
            return Response.rejected(request, "BUILDER_BUSY", "builder must be built, staffed, and idle");
        }
        String buildingType = building.getBuildingType().getRegistryName().toString();
        boolean initialBuild = ActionPolicy.isInitialBuild(
            buildingType,
            building.getBuildingLevel(),
            building.isBuilt(),
            building.isPendingConstruction()
        );
        boolean existingUpgrade = building.isBuilt()
            && !building.isPendingConstruction()
            && building.getBuildingLevel() < building.getMaxBuildingLevel();
        if (!initialBuild && !existingUpgrade) {
            return Response.rejected(
                request,
                "NOT_UPGRADABLE",
                "target must be an idle existing building or a level-0 Town Hall or Builder Hut"
            );
        }

        ServerPlayer actor = actionPlayer(action.actorUuid(), colony, level);
        if (actor == null) {
            return unauthorizedColonyOwner(request);
        }
        Response budget = consumeBudget(request, level);
        if (budget != null) {
            return budget;
        }

        building.requestUpgrade(actor, builderPosition);
        metrics.recordAction();
        return Response.completed(request, codec.payload(new UpgradePayload(
            action.colonyId(),
            action.dimension(),
            action.buildingPos(),
            action.builderPos()
        )));
    }

    private Response assignBuilder(Request request) {
        Response gate = mutationGate(request);
        if (gate != null) {
            return gate;
        }
        AssignBuilderAction action = (AssignBuilderAction) request.action();
        ServerLevel level = resolveLevel(action.dimension());
        IColony colony = resolveColony(level, action.colonyId());
        if (colony == null) {
            return Response.rejected(request, "UNKNOWN_COLONY", "target colony or loaded dimension was not found");
        }
        BlockPos builderPosition = toBlockPos(action.builderPos());
        if (!level.hasChunkAt(builderPosition)) {
            return Response.rejected(request, "CHUNK_NOT_LOADED", "bridge actions never force-load target chunks");
        }
        IBuilding rawBuilder = colony.getServerBuildingManager().getBuilding(builderPosition);
        if (!(rawBuilder instanceof BuildingBuilder builder)) {
            return Response.rejected(request, "NOT_A_BUILDER", "builder_pos must identify an existing structure builder");
        }
        IBuilderWorkOrder workOrder =
            colony.getWorkManager().getWorkOrder(action.workOrderId(), IBuilderWorkOrder.class);
        if (workOrder == null || workOrder.isClaimed()) {
            return Response.rejected(request, "WORK_ORDER_UNAVAILABLE", "work order must exist, be a builder order, and be unclaimed");
        }
        if (!isAvailableBuilder(builder)) {
            return Response.rejected(request, "BUILDER_BUSY", "builder must be built, staffed, and idle");
        }
        if (!workOrder.canBuildIgnoringDistance(builder, builderPosition, builder.getBuildingLevel())) {
            return Response.rejected(request, "BUILDER_INCOMPATIBLE", "builder cannot accept this work order");
        }
        if (!isColonyOwner(action.actorUuid(), colony)) {
            return unauthorizedColonyOwner(request);
        }
        Response budget = consumeBudget(request, level);
        if (budget != null) {
            return budget;
        }

        builder.setWorkOrder(workOrder);
        workOrder.setClaimedBy(builderPosition);
        colony.getWorkManager().setDirty(true);
        builder.markDirty();
        metrics.recordAction();
        return Response.completed(request, codec.payload(new BuilderAssignmentPayload(
            action.colonyId(),
            action.workOrderId(),
            action.builderPos()
        )));
    }

    private Response grant(Request request) {
        Response gate = mutationGate(request);
        if (gate != null) {
            return gate;
        }
        GrantAction action = (GrantAction) request.action();
        ServerLevel level = resolveLevel(action.dimension());
        IColony colony = resolveColony(level, action.colonyId());
        if (colony == null) {
            return Response.rejected(request, "UNKNOWN_COLONY", "target colony or loaded dimension was not found");
        }
        BlockPos buildingPosition = toBlockPos(action.buildingPos());
        if (!level.hasChunkAt(buildingPosition)) {
            return Response.rejected(request, "CHUNK_NOT_LOADED", "bridge actions never force-load target chunks");
        }
        IBuilding building = colony.getServerBuildingManager().getBuilding(buildingPosition);
        if (building == null) {
            return Response.rejected(request, "UNKNOWN_BUILDING", "building_pos must identify an existing colony building");
        }
        StandardToken token = new StandardToken(action.minecoloniesRequestId());
        IRequest<?> existingRequest;
        try {
            existingRequest = colony.getRequestManager().getRequestForToken(token);
        } catch (IllegalArgumentException exception) {
            existingRequest = null;
        }
        if (existingRequest == null
            || !existingRequest.getRequester().getLocation().getDimension().equals(level.dimension())
            || !existingRequest.getRequester().getLocation().getInDimensionLocation().equals(buildingPosition)) {
            return Response.rejected(
                request,
                "REQUEST_BUILDING_MISMATCH",
                "MineColonies request must exist and belong to building_pos"
            );
        }
        IDeliverable deliverable = existingRequest.getRequestOfType(IDeliverable.class).orElse(null);
        ResourceLocation itemId = ResourceLocation.tryParse(action.item());
        Optional<Item> item = itemId == null ? Optional.empty() : BuiltInRegistries.ITEM.getOptional(itemId);
        if (deliverable == null || item.isEmpty()) {
            return Response.rejected(request, "INVALID_DELIVERY", "request is not deliverable or item is unknown");
        }
        ItemStack stack = new ItemStack(item.get(), action.quantity());
        if (!deliverable.matches(stack)
            || action.quantity() < deliverable.getMinimumCount()
            || action.quantity() > deliverable.getCount()) {
            return Response.rejected(
                request,
                "DELIVERY_MISMATCH",
                "item and quantity must satisfy the existing MineColonies request"
            );
        }
        if (!isColonyOwner(action.actorUuid(), colony)) {
            return unauthorizedColonyOwner(request);
        }
        if (!ItemDelivery.canForceTransferEntireStack(building, level, stack)) {
            return Response.rejected(
                request,
                "DELIVERY_STORAGE_FULL",
                "target building must have capacity for the entire delivery"
            );
        }
        Response budget = consumeBudget(request, level);
        if (budget != null) {
            return budget;
        }

        // Everything past this point has already paid for the action, so any
        // failure here has to hand the allowance back.
        ItemStack remainder = building.forceTransferStack(stack.copy(), level);
        if (!remainder.isEmpty()) {
            state.refundBudget(level.getGameTime());
            return Response.rejected(
                request,
                "DELIVERY_INSERT_FAILED",
                "target building did not accept the entire delivery"
            );
        }
        try {
            colony.getRequestManager().overruleRequest(token, stack);
        } catch (IllegalArgumentException exception) {
            // The token was valid when the snapshot was taken and is not any
            // longer: MineColonies resolved or replaced the request during the
            // round trip. That is a stale read, not a spent action.
            state.refundBudget(level.getGameTime());
            return Response.rejected(
                request,
                "REQUEST_NO_LONGER_OPEN",
                "MineColonies request was resolved or replaced before the grant landed"
            );
        }
        metrics.recordAction();
        return Response.completed(request, codec.payload(new GrantPayload(
            action.colonyId(),
            action.buildingPos(),
            action.minecoloniesRequestId(),
            action.item(),
            action.quantity()
        )));
    }

    private Response pause(Request request) {
        PauseAction action = (PauseAction) request.action();
        if (onlineOperator(action.actorUuid()) == null) {
            return unauthorizedGlobalOperator(request);
        }
        if (action.ticks() == 0) {
            state.resume();
        } else {
            state.pause(server.getTickCount(), action.ticks());
        }
        return Response.completed(request, codec.payload(new PausePayload(
            action.ticks(),
            state.pausedUntilTick()
        )));
    }

    private Response mutationGate(Request request) {
        if (!BridgeServerConfig.ENABLED.get() || BridgeServerConfig.MODE.get() != BridgeServerConfig.Mode.ACTIVE) {
            return Response.rejected(request, "OBSERVER_ONLY", "mutations require enabled=true and mode=ACTIVE");
        }
        if (state.isPaused(server.getTickCount())) {
            return Response.rejected(request, "PAUSED", "bridge actions are paused");
        }
        return null;
    }

    private Response consumeBudget(Request request, ServerLevel level) {
        if (!state.tryConsumeBudget(level.getGameTime(), BridgeServerConfig.DAILY_ACTION_BUDGET.get())) {
            return Response.rejected(request, "BUDGET_EXHAUSTED", "daily action budget is zero or exhausted");
        }
        return null;
    }

    private ServerPlayer onlineOperator(UUID actorUuid) {
        ServerPlayer actor = server.getPlayerList().getPlayer(actorUuid);
        return actor != null && actor.hasPermissions(BridgeServerConfig.REQUIRED_PERMISSION_LEVEL.get())
            ? actor
            : null;
    }

    private static boolean isColonyOwner(UUID actorUuid, IColony colony) {
        return ActionPolicy.isColonyOwner(actorUuid, colony.getPermissions().getOwner());
    }

    private ServerPlayer actionPlayer(UUID actorUuid, IColony colony, ServerLevel level) {
        ServerPlayer online = server.getPlayerList().getPlayer(actorUuid);
        if (!ActionPolicy.isColonyOwner(actorUuid, colony.getPermissions().getOwner())) {
            return null;
        }
        if (online != null) {
            return online;
        }
        return FakePlayerFactory.get(level, new GameProfile(actorUuid, "ColonyAutopilotOwner"));
    }

    private static Response unauthorizedColonyOwner(Request request) {
        return Response.rejected(
            request,
            "UNAUTHORIZED_ACTOR",
            "actor_uuid must exactly match the target colony owner"
        );
    }

    private static Response unauthorizedGlobalOperator(Request request) {
        return Response.rejected(
            request,
            "UNAUTHORIZED_ACTOR",
            "global pause and resume require an online operator with the configured permission level"
        );
    }

    private IColony resolveColony(ServerLevel level, int colonyId) {
        return level == null ? null : IColonyManager.getInstance().getColonyByWorld(colonyId, level);
    }

    private ServerLevel resolveLevel(String dimension) {
        ResourceLocation location = ResourceLocation.tryParse(dimension);
        if (location == null) {
            return null;
        }
        ResourceKey<Level> key = ResourceKey.create(Registries.DIMENSION, location);
        return server.getLevel(key);
    }

    private Response remember(Request request, Response response) {
        responses.put(request.requestId(), new CachedResponse(request.nonce(), response));
        trim(responses);
        return response;
    }

    private void purgeExpiredNonces() {
        long cutoff = System.currentTimeMillis() - BridgeServerConfig.REQUEST_MAX_AGE_MILLIS.get();
        nonces.entrySet().removeIf(entry -> entry.getValue() < cutoff);
    }

    private static <K, V> void trim(Map<K, V> map) {
        while (map.size() > REPLAY_CACHE_SIZE) {
            Iterator<K> iterator = map.keySet().iterator();
            iterator.next();
            iterator.remove();
        }
    }

    private static BlockPos toBlockPos(BlockPosition position) {
        return new BlockPos(position.x(), position.y(), position.z());
    }

    private static boolean isAvailableBuilder(BuildingBuilder builder) {
        return ActionPolicy.isBuilderIdle(
            builder.getBuildingLevel(),
            builder.isBuilt(),
            builder.getAllAssignedCitizen().size(),
            builder.hasWorkOrder()
        );
    }

    private void requireServerThread() {
        if (!server.isSameThread()) {
            throw new IllegalStateException("Bridge actions must execute on the Minecraft server thread");
        }
    }

    private record CachedResponse(UUID nonce, Response response) {
    }

    private record UpgradePayload(
        int colonyId,
        String dimension,
        BlockPosition building,
        BlockPosition builder
    ) {
    }

    private record BuilderAssignmentPayload(
        int colonyId,
        int workOrderId,
        BlockPosition builder
    ) {
    }

    private record GrantPayload(
        int colonyId,
        BlockPosition building,
        UUID minecoloniesRequestId,
        String item,
        int granted
    ) {
    }

    private record PausePayload(int ticks, long pausedUntilTick) {
    }

    public record StatusMetrics(
        double tps,
        double tickMs,
        long requests,
        long rejected,
        long actions,
        long failures,
        long lastPollNanos,
        long maximumPollNanos
    ) {
        public static StatusMetrics fromAverageTickMillis(
            float averageTickMillis,
            BridgeMetrics.Snapshot ipc
        ) {
            double tps = averageTickMillis <= 0.0F
                ? 20.0
                : Math.min(20.0, 1_000.0 / averageTickMillis);
            return new StatusMetrics(
                tps,
                averageTickMillis,
                ipc.requests(),
                ipc.rejected(),
                ipc.actions(),
                ipc.failures(),
                ipc.lastPollNanos(),
                ipc.maximumPollNanos()
            );
        }
    }

    public record StatusPayload(
        boolean enabled,
        String mode,
        boolean paused,
        long pausedUntilTick,
        int dailyBudget,
        int actionsThisDay,
        StatusMetrics metrics,
        int builderSpeedMultiplier,
        boolean offlineProgressEnabled,
        boolean offlineProgressActive,
        int offlineProgressLeases,
        String offlineProgressReason,
        boolean offlineStallRecoveryEnabled,
        int offlineStallRecoveries,
        String offlineLastStallRecovery,
        int offlineTickingChunks,
        boolean constructionDeadlineEnabled,
        int constructionDeadlineMinutes,
        java.util.List<ConstructionDeadlineController.Status> constructionJobs
    ) {
    }
}

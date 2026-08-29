package com.colonyautopilot.bridge.colony;

import com.colonyautopilot.bridge.action.ActionPolicy;
import com.colonyautopilot.bridge.runtime.ConstructionDeadlineController;
import com.minecolonies.api.colony.IColony;
import com.minecolonies.api.colony.IColonyManager;
import com.minecolonies.api.colony.ICitizenData;
import com.minecolonies.api.colony.buildings.IBuilding;
import com.minecolonies.api.colony.requestsystem.request.IRequest;
import com.minecolonies.api.colony.requestsystem.requestable.IDeliverable;
import com.minecolonies.api.colony.requestsystem.token.IToken;
import com.minecolonies.api.colony.workorders.IBuilderWorkOrder;
import com.minecolonies.api.colony.workorders.IServerWorkOrder;
import com.minecolonies.core.colony.buildings.workerbuildings.BuildingBuilder;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Supplier;

public final class ColonySnapshotService {
    private final Supplier<List<ConstructionDeadlineController.Status>>
        constructionStatuses;

    public ColonySnapshotService(
        Supplier<List<ConstructionDeadlineController.Status>> constructionStatuses
    ) {
        this.constructionStatuses = constructionStatuses;
    }

    public Snapshot read(MinecraftServer server, Integer targetColonyId, String targetDimension) {
        requireServerThread(server);
        List<ColonySnapshot> colonies = new ArrayList<>();
        for (ServerLevel level : server.getAllLevels()) {
            String dimension = level.dimension().location().toString();
            if (targetDimension != null && !targetDimension.equals(dimension)) {
                continue;
            }
            for (IColony colony : IColonyManager.getInstance().getColonies(level)) {
                if (targetColonyId != null && colony.getID() != targetColonyId) {
                    continue;
                }
                colonies.add(snapshotColony(server, level, colony));
            }
        }
        return new Snapshot(server.getTickCount(), List.copyOf(colonies));
    }

    private ColonySnapshot snapshotColony(MinecraftServer server, ServerLevel level, IColony colony) {
        List<BuildingSnapshot> loadedBuildings = new ArrayList<>();
        List<IBuilderWorkOrder> builderWorkOrders =
            colony.getWorkManager().getWorkOrdersOfType(IBuilderWorkOrder.class);
        int totalBuildings = colony.getServerBuildingManager().getBuildings().size();
        for (IBuilding building : colony.getServerBuildingManager().getBuildings().values()) {
            BlockPos position = building.getPosition();
            if (!level.hasChunkAt(position)) {
                continue;
            }
            String buildingType = building.getBuildingType().getRegistryName().toString();
            boolean isBuilder = building instanceof BuildingBuilder
                && "minecolonies:builder".equals(buildingType);
            boolean builderIdle = isBuilder && ActionPolicy.isBuilderIdle(
                building.getBuildingLevel(),
                building.isBuilt(),
                building.getAllAssignedCitizen().size(),
                ((BuildingBuilder) building).hasWorkOrder()
            );
            List<Integer> compatibleWorkOrderIds =
                builderIdle && building instanceof BuildingBuilder builder
                    ? builderWorkOrders.stream()
                        .filter(order -> !order.isClaimed())
                        .filter(order -> order.canBuildIgnoringDistance(
                            builder,
                            position,
                            building.getBuildingLevel()
                        ))
                        .map(IBuilderWorkOrder::getID)
                        .sorted()
                        .toList()
                    : List.of();
            loadedBuildings.add(new BuildingSnapshot(
                Position.from(position),
                building.getBuildingDisplayName(),
                buildingType,
                isBuilder,
                builderIdle,
                compatibleWorkOrderIds,
                building.getBuildingLevel(),
                building.getMaxBuildingLevel(),
                building.isBuilt(),
                building.isPendingConstruction(),
                snapshotRequests(colony, building)
            ));
        }
        List<WorkOrderSnapshot> workOrders = colony.getWorkManager().getWorkOrders().values().stream()
            .map(order -> snapshotWorkOrder(
                order,
                level.dimension().location().toString(),
                colony.getID()
            ))
            .toList();
        List<CitizenSnapshot> citizens = colony.getCitizenManager()
            .getCitizens()
            .stream()
            .map(citizen -> snapshotCitizen(citizen, level))
            .toList();
        return new ColonySnapshot(
            colony.getID(),
            colony.getName(),
            level.dimension().location().toString(),
            colony.getPermissions().getOwner().toString(),
            snapshotOwnerEvidence(server, colony.getPermissions().getOwner()),
            Position.from(colony.getCenter()),
            colony.isActive(),
            colony.isColonyUnderAttack(),
            colony.getOverallHappiness(),
            colony.getLoadedChunkCount(),
            totalBuildings,
            List.copyOf(loadedBuildings),
            workOrders,
            citizens
        );
    }

    private static OwnerEvidence snapshotOwnerEvidence(MinecraftServer server, java.util.UUID ownerId) {
        ServerPlayer owner = server.getPlayerList().getPlayer(ownerId);
        if (owner == null) {
            return null;
        }
        Map<String, Integer> inventory = new TreeMap<>();
        for (int slot = 0; slot < owner.getInventory().getContainerSize(); slot++) {
            ItemStack stack = owner.getInventory().getItem(slot);
            if (!stack.isEmpty()) {
                inventory.merge(
                    BuiltInRegistries.ITEM.getKey(stack.getItem()).toString(),
                    stack.getCount(),
                    Integer::sum
                );
            }
        }
        return new OwnerEvidence(
            Map.of(),
            List.of(),
            Map.copyOf(inventory),
            List.of(owner.serverLevel().dimension().location().toString())
        );
    }

    private static List<RequestSnapshot> snapshotRequests(IColony colony, IBuilding building) {
        Set<IToken<?>> tokens = new LinkedHashSet<>();
        building.getOpenRequestsByRequestableType().values().forEach(tokens::addAll);
        List<RequestSnapshot> requests = new ArrayList<>();
        for (IToken<?> token : tokens) {
            IRequest<?> request;
            try {
                request = colony.getRequestManager().getRequestForToken(token);
            } catch (IllegalArgumentException exception) {
                continue;
            }
            if (request == null) {
                continue;
            }
            IDeliverable deliverable = request.getRequestOfType(IDeliverable.class).orElse(null);
            Integer citizenId = building.getCitizenForRequest(token)
                .map(ICitizenData::getId)
                .orElse(null);
            List<ItemSnapshot> displayItems = request.getDisplayStacks().stream()
                .filter(stack -> !stack.isEmpty())
                .map(ColonySnapshotService::snapshotItem)
                .toList();
            requests.add(new RequestSnapshot(
                token.getIdentifier().toString(),
                request.getState().name(),
                request.getShortDisplayString().getString(),
                deliverable == null ? null : deliverable.getMinimumCount(),
                deliverable == null ? null : deliverable.getCount(),
                displayItems,
                citizenId,
                citizenId == null ? "building" : "citizen",
                deliverable != null
            ));
        }
        return List.copyOf(requests);
    }

    private static ItemSnapshot snapshotItem(ItemStack stack) {
        return new ItemSnapshot(
            BuiltInRegistries.ITEM.getKey(stack.getItem()).toString(),
            stack.getCount()
        );
    }

    private WorkOrderSnapshot snapshotWorkOrder(
        IServerWorkOrder order,
        String dimension,
        int colonyId
    ) {
        ConstructionSnapshot construction = constructionStatuses.get().stream()
            .filter(status -> status.dimension().equals(dimension))
            .filter(status -> status.colonyId() == colonyId)
            .filter(status -> status.workOrderId() == order.getID())
            .findFirst()
            .map(ConstructionSnapshot::from)
            .orElse(null);
        return new WorkOrderSnapshot(
            order.getID(),
            order.getWorkOrderType().name(),
            Position.from(order.getLocation()),
            order.getTargetLevel(),
            order.isClaimed(),
            Position.from(order.getClaimedBy()),
            construction
        );
    }

    private static CitizenSnapshot snapshotCitizen(
        ICitizenData citizen,
        ServerLevel level
    ) {
        var job = citizen.getJob();
        var workerAi = job == null ? null : job.getWorkerAI();
        IBuilding workBuilding = citizen.getWorkBuilding();
        boolean entityPresent = citizen.getEntity().isPresent();
        boolean entityInLevel = citizen.getEntity()
            .filter(entity -> entity.isAlive()
                && level.getEntity(entity.getUUID()) == entity)
            .isPresent();
        BlockPos position = citizen.getEntity()
            .map(entity -> entity.blockPosition())
            .orElseGet(citizen::getLastPosition);
        boolean chunkLoaded = position != null && level.hasChunkAt(position);
        boolean chunkEntityTicking = chunkLoaded
            && level.isPositionEntityTicking(position);
        return new CitizenSnapshot(
            citizen.getId(),
            citizen.getName(),
            job == null ? null : job.getJobRegistryEntry().getKey().toString(),
            workerAi == null ? null : workerAi.getState().toString(),
            citizen.getJobStatus().name(),
            job != null && job.isIdling(),
            job == null ? 0 : job.getActionsDone(),
            citizen.isPaused(),
            citizen.isWorking(),
            citizen.isAsleep(),
            entityPresent,
            nullablePosition(citizen.getLastPosition()),
            workBuilding == null
                ? null
                : Position.from(workBuilding.getPosition()),
            citizen.getSaturation(),
            entityInLevel,
            chunkLoaded,
            chunkEntityTicking,
            CitizenLiveness.diagnose(
                entityPresent,
                entityInLevel,
                chunkLoaded,
                chunkEntityTicking
            )
        );
    }

    private static Position nullablePosition(BlockPos position) {
        return position == null ? null : Position.from(position);
    }

    private static void requireServerThread(MinecraftServer server) {
        if (!server.isSameThread()) {
            throw new IllegalStateException("MineColonies snapshots must be read on the server thread");
        }
    }

    public record Snapshot(long serverTick, List<ColonySnapshot> colonies) {
    }

    public record ColonySnapshot(
        int id,
        String name,
        String dimension,
        String ownerUuid,
        OwnerEvidence ownerEvidence,
        Position center,
        boolean active,
        boolean underAttack,
        double happiness,
        int loadedChunkCount,
        int totalBuildingCount,
        List<BuildingSnapshot> loadedBuildings,
        List<WorkOrderSnapshot> workOrders,
        List<CitizenSnapshot> citizens
    ) {
    }

    public record BuildingSnapshot(
        Position position,
        String name,
        String buildingType,
        boolean isBuilder,
        boolean builderIdle,
        List<Integer> compatibleWorkOrderIds,
        int level,
        int maximumLevel,
        boolean built,
        boolean pendingConstruction,
        List<RequestSnapshot> openRequests
    ) {
    }

    public record RequestSnapshot(
        String requestId,
        String state,
        String description,
        Integer minimumCount,
        Integer requestedCount,
        List<ItemSnapshot> displayItems,
        Integer citizenId,
        String requestOrigin,
        boolean deliverable
    ) {
    }

    public record ItemSnapshot(String item, int count) {
    }

    public record OwnerEvidence(
        Map<String, Integer> stats,
        List<String> advancements,
        Map<String, Integer> inventory,
        List<String> dimensions
    ) {
    }

    public record WorkOrderSnapshot(
        int id,
        String type,
        Position location,
        int targetLevel,
        boolean claimed,
        Position claimedBy,
        ConstructionSnapshot construction
    ) {
    }

    public record ConstructionSnapshot(
        long healthySeconds,
        long deadlineSeconds,
        long estimatedRemainingOperations,
        String phase,
        String canonicalPhase,
        int stepsPerCall,
        long reconciliationCursor,
        int lastChecked,
        int lastRepaired,
        boolean paused
    ) {
        private static ConstructionSnapshot from(
            ConstructionDeadlineController.Status status
        ) {
            return new ConstructionSnapshot(
                status.healthySeconds(),
                status.deadlineSeconds(),
                status.estimatedRemainingOperations(),
                status.phase(),
                status.canonicalPhase(),
                status.stepsPerCall(),
                status.reconciliationCursor(),
                status.lastChecked(),
                status.lastRepaired(),
                status.paused()
            );
        }
    }

    public record CitizenSnapshot(
        int id,
        String name,
        String job,
        String aiState,
        String jobStatus,
        boolean jobIdling,
        int actionsDone,
        boolean paused,
        boolean working,
        boolean asleep,
        boolean entityPresent,
        Position lastPosition,
        Position workBuilding,
        double saturation,
        boolean entityInLevel,
        boolean chunkLoaded,
        boolean chunkEntityTicking,
        String liveness
    ) {
    }

    public record Position(int x, int y, int z) {
        static Position from(BlockPos position) {
            return new Position(position.getX(), position.getY(), position.getZ());
        }
    }
}

package com.colonyautopilot.bridge.action;

import com.colonyautopilot.bridge.ipc.BridgeProtocol.Action;
import com.colonyautopilot.bridge.ipc.BridgeProtocol.AssignBuilderAction;
import com.colonyautopilot.bridge.ipc.BridgeProtocol.GrantAction;
import com.colonyautopilot.bridge.ipc.BridgeProtocol.PauseAction;
import com.colonyautopilot.bridge.ipc.BridgeProtocol.PlaceBuildingAction;
import com.colonyautopilot.bridge.ipc.BridgeProtocol.Request;
import com.colonyautopilot.bridge.ipc.BridgeProtocol.SnapshotAction;
import com.colonyautopilot.bridge.ipc.BridgeProtocol.UpgradeAction;

import java.time.Clock;
import java.util.Optional;

public final class ActionValidator {
    private static final int MAX_PAUSE_TICKS = 72_000;
    private final Clock clock;

    public ActionValidator(Clock clock) {
        this.clock = clock;
    }

    public Optional<Rejection> validateEnvelope(Request request, long maximumAgeMillis) {
        if (!com.colonyautopilot.bridge.ipc.BridgeProtocol.REQUEST_TYPE.equals(request.type())) {
            return Optional.of(new Rejection("UNSUPPORTED_PROTOCOL", "unsupported request type"));
        }
        if (!request.requestId().equals(request.idempotencyKey())) {
            return Optional.of(new Rejection("INVALID_IDEMPOTENCY_KEY", "request_id must equal idempotency_key"));
        }
        long age;
        try {
            age = Math.abs(Math.subtractExact(clock.millis(), request.issuedAtEpochMillis()));
        } catch (ArithmeticException exception) {
            return Optional.of(new Rejection("STALE_REQUEST", "issuedAtEpochMillis is outside the accepted range"));
        }
        if (age > maximumAgeMillis) {
            return Optional.of(new Rejection("STALE_REQUEST", "request timestamp is outside the accepted window"));
        }
        if (request.rationale() == null || request.rationale().isBlank() || request.rationale().length() > 1_024) {
            return Optional.of(new Rejection("INVALID_RATIONALE", "rationale must contain 1 to 1024 characters"));
        }
        if (request.action() == null) {
            return Optional.of(new Rejection("INVALID_ACTION", "action is required"));
        }
        return validateAction(request.action());
    }

    public Optional<Rejection> validateAction(Action action) {
        if (action instanceof SnapshotAction snapshot) {
            if ((snapshot.colonyId() == null) != (snapshot.dimension() == null)) {
                return Optional.of(new Rejection(
                    "INVALID_TARGET",
                    "snapshot colony_id and dimension must either both be present or both be absent"
                ));
            }
            if (snapshot.colonyId() != null && snapshot.colonyId() < 1) {
                return Optional.of(new Rejection("INVALID_TARGET", "colony_id must be positive"));
            }
            return Optional.empty();
        }
        if (action instanceof UpgradeAction upgrade) {
            if (!validColony(upgrade.colonyId(), upgrade.dimension())
                || upgrade.buildingPos() == null
                || upgrade.builderPos() == null
                || upgrade.actorUuid() == null) {
                return Optional.of(new Rejection("INVALID_TARGET", "request_upgrade target is incomplete"));
            }
            return Optional.empty();
        }
        if (action instanceof AssignBuilderAction assign) {
            if (!validColony(assign.colonyId(), assign.dimension())
                || assign.workOrderId() < 1
                || assign.builderPos() == null
                || assign.actorUuid() == null) {
                return Optional.of(new Rejection("INVALID_TARGET", "assign_builder target is incomplete"));
            }
            return Optional.empty();
        }
        if (action instanceof GrantAction grant) {
            if (!validColony(grant.colonyId(), grant.dimension())
                || grant.buildingPos() == null
                || grant.minecoloniesRequestId() == null
                || blank(grant.item())
                || grant.quantity() < 1
                || grant.quantity() > 64
                || grant.actorUuid() == null) {
                return Optional.of(new Rejection("INVALID_TARGET", "grant target is incomplete or quantity is outside 1..64"));
            }
            return Optional.empty();
        }
        if (action instanceof PauseAction pause) {
            if (pause.ticks() < 0 || pause.ticks() > MAX_PAUSE_TICKS || pause.actorUuid() == null) {
                return Optional.of(new Rejection("INVALID_PAUSE", "pause ticks must be between 0 and 72000"));
            }
            return Optional.empty();
        }
        if (action instanceof PlaceBuildingAction place && (
            !validColony(place.colonyId(), place.dimension())
                || blank(place.buildingType())
                || place.position() == null
                || place.actorUuid() == null
        )) {
            return Optional.of(new Rejection("INVALID_TARGET", "place_building target is incomplete"));
        }
        return Optional.empty();
    }

    private static boolean validColony(int colonyId, String dimension) {
        return colonyId > 0 && !blank(dimension);
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    public record Rejection(String code, String message) {
    }
}

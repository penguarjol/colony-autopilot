package com.colonyautopilot.bridge.ipc;

import com.google.gson.JsonElement;
import com.google.gson.annotations.SerializedName;

import java.util.UUID;

public final class BridgeProtocol {
    public static final String REQUEST_TYPE = "controller.action.v1";
    public static final String RESPONSE_TYPE = "bridge.result.v1";

    private BridgeProtocol() {
    }

    public enum ActionKind {
        STATUS("status"),
        SNAPSHOT("snapshot"),
        ASSIGN_BUILDER("assign_builder"),
        REQUEST_UPGRADE("request_upgrade"),
        GRANT("grant"),
        PAUSE("pause"),
        PLACE_BUILDING("place_building");

        private final String wireName;

        ActionKind(String wireName) {
            this.wireName = wireName;
        }

        public String wireName() {
            return wireName;
        }

        public static ActionKind fromWireName(String value) {
            for (ActionKind kind : values()) {
                if (kind.wireName.equals(value)) {
                    return kind;
                }
            }
            throw new IllegalArgumentException("Unsupported action: " + value);
        }
    }

    public enum ResponseStatus {
        @SerializedName("completed")
        COMPLETED,
        @SerializedName("rejected")
        REJECTED,
        @SerializedName("failed")
        FAILED
    }

    public record BlockPosition(int x, int y, int z) {
    }

    public sealed interface Action permits
        StatusAction,
        SnapshotAction,
        AssignBuilderAction,
        UpgradeAction,
        GrantAction,
        PauseAction,
        PlaceBuildingAction {
        ActionKind kind();
    }

    public record StatusAction() implements Action {
        @Override
        public ActionKind kind() {
            return ActionKind.STATUS;
        }
    }

    public record SnapshotAction(Integer colonyId, String dimension) implements Action {
        @Override
        public ActionKind kind() {
            return ActionKind.SNAPSHOT;
        }
    }

    public record AssignBuilderAction(
        int colonyId,
        String dimension,
        int workOrderId,
        BlockPosition builderPos,
        UUID actorUuid
    ) implements Action {
        @Override
        public ActionKind kind() {
            return ActionKind.ASSIGN_BUILDER;
        }
    }

    public record UpgradeAction(
        int colonyId,
        String dimension,
        BlockPosition buildingPos,
        BlockPosition builderPos,
        UUID actorUuid
    ) implements Action {
        @Override
        public ActionKind kind() {
            return ActionKind.REQUEST_UPGRADE;
        }
    }

    public record GrantAction(
        int colonyId,
        String dimension,
        BlockPosition buildingPos,
        UUID minecoloniesRequestId,
        String item,
        int quantity,
        UUID actorUuid
    ) implements Action {
        @Override
        public ActionKind kind() {
            return ActionKind.GRANT;
        }
    }

    public record PauseAction(int ticks, UUID actorUuid) implements Action {
        @Override
        public ActionKind kind() {
            return ActionKind.PAUSE;
        }
    }

    public record PlaceBuildingAction(
        int colonyId,
        String dimension,
        String buildingType,
        BlockPosition position,
        UUID actorUuid
    ) implements Action {
        @Override
        public ActionKind kind() {
            return ActionKind.PLACE_BUILDING;
        }
    }

    public record Request(
        String type,
        String requestId,
        String idempotencyKey,
        UUID nonce,
        long issuedAtEpochMillis,
        Action action,
        String rationale
    ) {
    }

    public record Response(
        String type,
        String requestId,
        ResponseStatus status,
        JsonElement payload
    ) {
        public static Response completed(Request request, JsonElement payload) {
            return new Response(RESPONSE_TYPE, request.requestId(), ResponseStatus.COMPLETED, payload);
        }

        public static Response rejected(Request request, String code, String message) {
            return new Response(RESPONSE_TYPE, request.requestId(), ResponseStatus.REJECTED, errorPayload(code, message));
        }

        public static Response failed(Request request, String code, String message) {
            return new Response(RESPONSE_TYPE, request.requestId(), ResponseStatus.FAILED, errorPayload(code, message));
        }

        private static JsonElement errorPayload(String code, String message) {
            com.google.gson.JsonObject payload = new com.google.gson.JsonObject();
            payload.addProperty("code", code);
            payload.addProperty("message", message);
            return payload;
        }
    }
}

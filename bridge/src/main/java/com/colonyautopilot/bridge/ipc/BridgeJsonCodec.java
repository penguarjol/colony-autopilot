package com.colonyautopilot.bridge.ipc;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.FieldNamingPolicy;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;

import java.util.Set;
import java.util.UUID;

import static com.colonyautopilot.bridge.ipc.BridgeProtocol.Action;
import static com.colonyautopilot.bridge.ipc.BridgeProtocol.ActionKind;
import static com.colonyautopilot.bridge.ipc.BridgeProtocol.AssignBuilderAction;
import static com.colonyautopilot.bridge.ipc.BridgeProtocol.BlockPosition;
import static com.colonyautopilot.bridge.ipc.BridgeProtocol.GrantAction;
import static com.colonyautopilot.bridge.ipc.BridgeProtocol.PauseAction;
import static com.colonyautopilot.bridge.ipc.BridgeProtocol.PlaceBuildingAction;
import static com.colonyautopilot.bridge.ipc.BridgeProtocol.Request;
import static com.colonyautopilot.bridge.ipc.BridgeProtocol.Response;
import static com.colonyautopilot.bridge.ipc.BridgeProtocol.SnapshotAction;
import static com.colonyautopilot.bridge.ipc.BridgeProtocol.StatusAction;
import static com.colonyautopilot.bridge.ipc.BridgeProtocol.UpgradeAction;

public final class BridgeJsonCodec {
    private static final Set<String> REQUEST_FIELDS =
        Set.of(
            "type",
            "request_id",
            "idempotency_key",
            "nonce",
            "issued_at_epoch_ms",
            "action",
            "payload",
            "rationale"
        );
    private static final Set<String> TARGET_FIELDS =
        Set.of("colony_id", "dimension");
    private static final Set<String> ASSIGN_BUILDER_FIELDS =
        Set.of("colony_id", "dimension", "work_order_id", "builder_pos", "actor_uuid");
    private static final Set<String> UPGRADE_FIELDS =
        Set.of("colony_id", "dimension", "building_pos", "builder_pos", "actor_uuid");
    private static final Set<String> GRANT_FIELDS =
        Set.of(
            "colony_id",
            "dimension",
            "building_pos",
            "minecolonies_request_id",
            "item",
            "quantity",
            "actor_uuid"
        );
    private static final Set<String> PAUSE_FIELDS = Set.of("ticks", "actor_uuid");
    private static final Set<String> PLACE_BUILDING_FIELDS =
        Set.of("colony_id", "dimension", "building_type", "position", "actor_uuid");
    private static final Set<String> POSITION_FIELDS = Set.of("x", "y", "z");
    private static final Gson GSON = new GsonBuilder()
        .setFieldNamingPolicy(FieldNamingPolicy.LOWER_CASE_WITH_UNDERSCORES)
        .disableHtmlEscaping()
        .create();

    public Request decodeRequest(String json) {
        JsonObject root = object(JsonParser.parseString(json), "request");
        requireExactFields(root, REQUEST_FIELDS, "request");
        String type = string(root, "type");
        if (!BridgeProtocol.REQUEST_TYPE.equals(type)) {
            throw new JsonParseException("Unsupported request type");
        }
        String requestId = string(root, "request_id");
        String idempotencyKey = string(root, "idempotency_key");
        if (!requestId.equals(idempotencyKey)) {
            throw new JsonParseException("request_id must equal idempotency_key");
        }
        if (!requestId.matches("[A-Za-z0-9._-]{1,128}")) {
            throw new JsonParseException("request_id is not spool-safe");
        }
        ActionKind kind;
        try {
            kind = ActionKind.fromWireName(string(root, "action"));
        } catch (IllegalArgumentException exception) {
            throw new JsonParseException(exception.getMessage(), exception);
        }
        JsonObject payload = object(required(root, "payload"), "payload");

        return new Request(
            type,
            requestId,
            idempotencyKey,
            uuid(root, "nonce"),
            longValue(root, "issued_at_epoch_ms"),
            decodeAction(kind, payload),
            string(root, "rationale")
        );
    }

    public String encodeResponse(Response response) {
        return GSON.toJson(response);
    }

    public JsonElement payload(Object value) {
        return GSON.toJsonTree(value);
    }

    private static Action decodeAction(ActionKind kind, JsonObject payload) {
        return switch (kind) {
            case STATUS -> {
                requireExactFields(payload, Set.of(), "status payload");
                yield new StatusAction();
            }
            case SNAPSHOT -> {
                requireAllowedFields(payload, TARGET_FIELDS, "snapshot payload");
                yield new SnapshotAction(
                    optionalInteger(payload, "colony_id"),
                    optionalString(payload, "dimension")
                );
            }
            case ASSIGN_BUILDER -> {
                requireExactFields(payload, ASSIGN_BUILDER_FIELDS, "assign_builder payload");
                yield new AssignBuilderAction(
                    integer(payload, "colony_id"),
                    string(payload, "dimension"),
                    integer(payload, "work_order_id"),
                    position(payload, "builder_pos"),
                    uuid(payload, "actor_uuid")
                );
            }
            case REQUEST_UPGRADE -> {
                requireExactFields(payload, UPGRADE_FIELDS, "request_upgrade payload");
                yield new UpgradeAction(
                    integer(payload, "colony_id"),
                    string(payload, "dimension"),
                    position(payload, "building_pos"),
                    position(payload, "builder_pos"),
                    uuid(payload, "actor_uuid")
                );
            }
            case GRANT -> {
                requireExactFields(payload, GRANT_FIELDS, "grant payload");
                yield new GrantAction(
                    integer(payload, "colony_id"),
                    string(payload, "dimension"),
                    position(payload, "building_pos"),
                    uuid(payload, "minecolonies_request_id"),
                    string(payload, "item"),
                    integer(payload, "quantity"),
                    uuid(payload, "actor_uuid")
                );
            }
            case PAUSE -> {
                requireExactFields(payload, PAUSE_FIELDS, "pause payload");
                yield new PauseAction(integer(payload, "ticks"), uuid(payload, "actor_uuid"));
            }
            case PLACE_BUILDING -> {
                requireExactFields(payload, PLACE_BUILDING_FIELDS, "place_building payload");
                yield new PlaceBuildingAction(
                    integer(payload, "colony_id"),
                    string(payload, "dimension"),
                    string(payload, "building_type"),
                    position(payload, "position"),
                    uuid(payload, "actor_uuid")
                );
            }
        };
    }

    private static BlockPosition position(JsonObject object, String field) {
        JsonObject position = object(required(object, field), field);
        requireExactFields(position, POSITION_FIELDS, field);
        return new BlockPosition(integer(position, "x"), integer(position, "y"), integer(position, "z"));
    }

    private static BlockPosition optionalPosition(JsonObject object, String field) {
        if (!object.has(field) || object.get(field).isJsonNull()) {
            return null;
        }
        return position(object, field);
    }

    private static JsonElement required(JsonObject object, String field) {
        if (!object.has(field) || object.get(field).isJsonNull()) {
            throw new JsonParseException("Missing required field: " + field);
        }
        return object.get(field);
    }

    private static JsonObject object(JsonElement element, String name) {
        if (!element.isJsonObject()) {
            throw new JsonParseException(name + " must be an object");
        }
        return element.getAsJsonObject();
    }

    private static int integer(JsonObject object, String field) {
        try {
            return required(object, field).getAsInt();
        } catch (RuntimeException exception) {
            throw new JsonParseException(field + " must be an integer", exception);
        }
    }

    private static Integer optionalInteger(JsonObject object, String field) {
        return object.has(field) && !object.get(field).isJsonNull() ? integer(object, field) : null;
    }

    private static long longValue(JsonObject object, String field) {
        try {
            return required(object, field).getAsLong();
        } catch (RuntimeException exception) {
            throw new JsonParseException(field + " must be an integer", exception);
        }
    }

    private static String string(JsonObject object, String field) {
        String value = optionalString(object, field);
        if (value == null) {
            throw new JsonParseException("Missing required field: " + field);
        }
        return value;
    }

    private static String optionalString(JsonObject object, String field) {
        if (!object.has(field) || object.get(field).isJsonNull()) {
            return null;
        }
        try {
            JsonElement value = object.get(field);
            if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) {
                throw new IllegalStateException();
            }
            return value.getAsString();
        } catch (RuntimeException exception) {
            throw new JsonParseException(field + " must be a string", exception);
        }
    }

    private static UUID uuid(JsonObject object, String field) {
        String value = string(object, field);
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException exception) {
            throw new JsonParseException(field + " must be a UUID", exception);
        }
    }

    private static void requireExactFields(JsonObject object, Set<String> expected, String name) {
        requireAllowedFields(object, expected, name);
        if (!object.keySet().containsAll(expected)) {
            throw new JsonParseException(name + " is missing required fields");
        }
    }

    private static void requireAllowedFields(JsonObject object, Set<String> allowed, String name) {
        if (!allowed.containsAll(object.keySet())) {
            throw new JsonParseException(name + " contains unknown fields");
        }
    }
}

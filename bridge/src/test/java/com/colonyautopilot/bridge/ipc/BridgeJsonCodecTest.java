package com.colonyautopilot.bridge.ipc;

import com.colonyautopilot.bridge.colony.ColonySnapshotService;
import com.google.gson.JsonParseException;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BridgeJsonCodecTest {
    private final BridgeJsonCodec codec = new BridgeJsonCodec();

    @Test
    void decodesTypedStatusRequest() {
        String json = """
            {
              "type": "controller.action.v1",
              "request_id": "status-20260821-01",
              "idempotency_key": "status-20260821-01",
              "nonce": "046583ec-522b-45d2-8655-3cd7c83e8f42",
              "issued_at_epoch_ms": 1787342400000,
              "action": "status",
              "payload": {},
              "rationale": "health poll"
            }
            """;

        BridgeProtocol.Request request = codec.decodeRequest(json);

        assertInstanceOf(BridgeProtocol.StatusAction.class, request.action());
        assertEquals("status-20260821-01", request.requestId());
    }

    @Test
    void decodesGrantBoundToExistingRequestAndBuilding() {
        String json = """
            {
              "type": "controller.action.v1",
              "request_id": "grant-20260821-01",
              "idempotency_key": "grant-20260821-01",
              "nonce": "046583ec-522b-45d2-8655-3cd7c83e8f42",
              "issued_at_epoch_ms": 1787342400000,
              "action": "grant",
              "payload": {
                "colony_id": 7,
                "dimension": "minecraft:overworld",
                "building_pos": {"x": 10, "y": 64, "z": 20},
                "minecolonies_request_id": "1d6514f4-17b9-4f06-ad27-d3c199e9bf55",
                "item": "minecraft:oak_log",
                "quantity": 8,
                "actor_uuid": "591e02e4-5297-4b20-9105-690748338427"
              },
              "rationale": "fulfill one existing request"
            }
            """;

        BridgeProtocol.Request request = codec.decodeRequest(json);
        BridgeProtocol.GrantAction grant =
            assertInstanceOf(BridgeProtocol.GrantAction.class, request.action());

        assertEquals(7, grant.colonyId());
        assertEquals(8, grant.quantity());
    }

    @Test
    void rejectsUnknownFields() {
        String json = """
            {
              "type": "controller.action.v1",
              "request_id": "status-20260821-01",
              "idempotency_key": "status-20260821-01",
              "nonce": "046583ec-522b-45d2-8655-3cd7c83e8f42",
              "issued_at_epoch_ms": 1787342400000,
              "action": "status",
              "payload": {},
              "rationale": "health poll",
              "unexpected": true
            }
            """;

        assertThrows(JsonParseException.class, () -> codec.decodeRequest(json));
    }

    @Test
    void rejectsMismatchedIdempotencyKey() {
        String json = """
            {
              "type": "controller.action.v1",
              "request_id": "one",
              "idempotency_key": "two",
              "nonce": "046583ec-522b-45d2-8655-3cd7c83e8f42",
              "issued_at_epoch_ms": 1787342400000,
              "action": "status",
              "payload": {},
              "rationale": "health poll"
            }
            """;

        assertThrows(JsonParseException.class, () -> codec.decodeRequest(json));
    }

    @Test
    void serializesControllerSnapshotMarkersInSnakeCase() {
        ColonySnapshotService.RequestSnapshot request =
            new ColonySnapshotService.RequestSnapshot(
                "1d6514f4-17b9-4f06-ad27-d3c199e9bf55",
                "IN_PROGRESS",
                "Stone shovel",
                1,
                1,
                List.of(new ColonySnapshotService.ItemSnapshot(
                    "minecraft:stone_shovel",
                    1
                )),
                4,
                "citizen",
                true
            );
        ColonySnapshotService.BuildingSnapshot building =
            new ColonySnapshotService.BuildingSnapshot(
                new ColonySnapshotService.Position(1, 64, 2),
                "Builder",
                "minecolonies:builder",
                true,
                true,
                List.of(42),
                1,
                5,
                true,
                false,
                List.of(request)
            );

        var json = codec.payload(building).getAsJsonObject();

        assertEquals("minecolonies:builder", json.get("building_type").getAsString());
        assertTrue(json.get("is_builder").getAsBoolean());
        assertTrue(json.get("builder_idle").getAsBoolean());
        assertEquals(42, json.getAsJsonArray("compatible_work_order_ids").get(0).getAsInt());
        var requestJson = json.getAsJsonArray("open_requests")
            .get(0)
            .getAsJsonObject();
        assertEquals(4, requestJson.get("citizen_id").getAsInt());
        assertEquals("citizen", requestJson.get("request_origin").getAsString());
        assertTrue(requestJson.get("deliverable").getAsBoolean());

        ColonySnapshotService.ColonySnapshot colony =
            new ColonySnapshotService.ColonySnapshot(
                7,
                "Test Colony",
                "minecraft:overworld",
                "591e02e4-5297-4b20-9105-690748338427",
                new ColonySnapshotService.OwnerEvidence(
                    Map.of(),
                    List.of(),
                    Map.of("minecraft:oak_log", 4),
                    List.of("minecraft:overworld")
                ),
                new ColonySnapshotService.Position(0, 64, 0),
                true,
                false,
                10.0,
                1,
                1,
                List.of(building),
                List.of(),
                List.of()
            );
        var colonyJson = codec.payload(colony).getAsJsonObject();
        assertEquals(
            "591e02e4-5297-4b20-9105-690748338427",
            colonyJson.get("owner_uuid").getAsString()
        );
        assertTrue(colonyJson.get("owner_evidence").isJsonObject());
        assertTrue(colonyJson.get("citizens").isJsonArray());
    }
}

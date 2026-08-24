package com.colonyautopilot.bridge.action;

import com.colonyautopilot.bridge.ipc.BridgeProtocol.BlockPosition;
import com.colonyautopilot.bridge.ipc.BridgeProtocol.GrantAction;
import com.colonyautopilot.bridge.ipc.BridgeProtocol.PauseAction;
import com.colonyautopilot.bridge.ipc.BridgeProtocol.Request;
import com.colonyautopilot.bridge.ipc.BridgeProtocol.StatusAction;
import com.colonyautopilot.bridge.ipc.BridgeProtocol.UpgradeAction;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ActionValidatorTest {
    private static final Instant NOW = Instant.parse("2026-08-21T20:00:00Z");
    private final ActionValidator validator =
        new ActionValidator(Clock.fixed(NOW, ZoneOffset.UTC));

    @Test
    void rejectsStaleRequests() {
        Request request = request(
            NOW.minusSeconds(31).toEpochMilli(),
            new StatusAction()
        );

        ActionValidator.Rejection rejection = validator.validateEnvelope(request, 30_000).orElseThrow();

        assertEquals("STALE_REQUEST", rejection.code());
    }

    @Test
    void acceptsStrictUpgradeTarget() {
        UpgradeAction action = new UpgradeAction(
            7,
            "minecraft:overworld",
            new BlockPosition(1, 64, 2),
            new BlockPosition(5, 64, 6),
            UUID.randomUUID()
        );

        assertTrue(validator.validateEnvelope(request(NOW.toEpochMilli(), action), 30_000).isEmpty());
    }

    @Test
    void rejectsGrantWithoutPositiveQuantity() {
        GrantAction action = new GrantAction(
            7,
            "minecraft:overworld",
            new BlockPosition(1, 64, 2),
            UUID.randomUUID(),
            "minecraft:oak_log",
            0,
            UUID.randomUUID()
        );

        ActionValidator.Rejection rejection =
            validator.validateEnvelope(request(NOW.toEpochMilli(), action), 30_000).orElseThrow();

        assertEquals("INVALID_TARGET", rejection.code());
    }

    @Test
    void rejectsPauseOutsideConfiguredLimit() {
        ActionValidator.Rejection rejection = validator.validateEnvelope(
            request(NOW.toEpochMilli(), new PauseAction(72_001, UUID.randomUUID())),
            30_000
        ).orElseThrow();

        assertEquals("INVALID_PAUSE", rejection.code());
    }

    private static Request request(long issuedAt, com.colonyautopilot.bridge.ipc.BridgeProtocol.Action action) {
        String requestId = UUID.randomUUID().toString();
        return new Request(
            "controller.action.v1",
            requestId,
            requestId,
            UUID.randomUUID(),
            issuedAt,
            action,
            "test"
        );
    }
}

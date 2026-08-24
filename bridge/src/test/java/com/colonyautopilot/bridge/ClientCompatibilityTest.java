package com.colonyautopilot.bridge;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

class ClientCompatibilityTest {
    @Test
    void acceptsBridgePresenceOrAbsenceFromEitherSide() {
        assertTrue(ClientCompatibility.acceptsRemoteVersion("", false));
        assertTrue(ClientCompatibility.acceptsRemoteVersion("SERVER_ONLY", true));
        assertTrue(ClientCompatibility.acceptsRemoteVersion("0.1.0", false));
    }
}

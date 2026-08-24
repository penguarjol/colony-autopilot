package com.colonyautopilot.bridge.ipc;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FileSpoolTransportTest {
    @TempDir
    Path root;

    @Test
    void createsCanonicalPrivateSpoolDirectories() throws Exception {
        new FileSpoolTransport(root).close();

        assertTrue(Files.isDirectory(root.resolve("requests")));
        assertTrue(Files.isDirectory(root.resolve("responses")));
        assertTrue(Files.isDirectory(root.resolve("rejected")));
    }

    @Test
    void completesAClaimUsingTheOriginalRequestToken() throws Exception {
        try (FileSpoolTransport transport = new FileSpoolTransport(root)) {
            Path request = root.resolve("requests/status-request.json");
            Files.writeString(request, "{\"request\":\"status\"}");
            Files.setPosixFilePermissions(request, PosixFilePermissions.fromString("rw-------"));

            BridgeTransport.InboundMessage message = transport.poll(1).get(0);
            assertEquals("status-request.json", message.token());

            transport.complete(message, "{\"status\":\"completed\"}");

            assertTrue(Files.isRegularFile(root.resolve("responses/status-request.json")));
            assertFalse(Files.exists(root.resolve("requests/.status-request.json.processing")));
        }
    }
}
